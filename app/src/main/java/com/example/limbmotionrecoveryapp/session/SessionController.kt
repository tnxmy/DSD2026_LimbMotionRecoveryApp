package com.example.limbmotionrecoveryapp.session

import android.content.Context
import android.util.Log
import com.dsd.m1.api.V2ApiClient
import com.dsd.s1.ble.SensorService
import com.dsd.s1.model.SensorSample
import com.dsd.s2.S1RealModule
import com.dsd.s2.S2Module
import com.dsd.s2.model.FormatData
import com.dsd.s2.model.TargetAngle
import com.dsd.s2.sim.SimulatedS1Module
import com.example.limbmotionrecoveryapp.sensor.SensorRepository
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "SessionController"
private const val DEFAULT_EXERCISE_TYPE = "bend_knee_10"

/**
 * Singleton session controller for M1.
 *
 * Responsibilities:
 * - Encapsulates S2 polling loop (S2 only exposes pull-based read())
 * - Manages V2 session lifecycle (create / end / upload measurements)
 * - Maintains a state machine (IDLE -> RUNNING <-> PAUSED -> ENDED -> IDLE)
 * - Caches rehabilitation data locally and exposes rich accessors to UI layers
 *
 * First call to [getInstance] must provide a [Context]; subsequent calls may pass null.
 */
class SessionController private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: SessionController? = null

        /**
         * Returns the singleton instance.
         * If it does not exist, creates it and auto-initializes S1/S2 with state = IDLE.
         *
         * @param context Required on first call; may be null afterwards.
         */
        fun getInstance(context: Context? = null): SessionController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val ctx = context?.applicationContext
                        ?: throw IllegalStateException("SessionController requires Context on first initialization")
                    SessionController(ctx).also { INSTANCE = it }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 0. Dependencies & S1/S2 construction
    // -------------------------------------------------------------------------

    private val appContext: Context = context.applicationContext
    private val v2Api = V2ApiClient()

    private var simS1: SimulatedS1Module? = null
    private var realS1: S1RealModule? = null
    private var s2Module: S2Module? = null

    init {
        buildS2(useReal = true)
    }

    /**
     * Builds S1 (real + simulator) and S2.
     * Falls back to simulator automatically if real BLE sensor is not connected.
     */
    private fun buildS2(useReal: Boolean) {
        simS1 = SimulatedS1Module()

        realS1 = null
        if (useReal) {
            val service: SensorService? = SensorRepository.getService()
            if (service != null) {
                realS1 = S1RealModule(service)
                Log.i(TAG, "Real sensor connected")
            } else {
                Log.w(TAG, "Real sensor unavailable; falling back to simulator")
            }
        }

        s2Module = S2Module(
            s1RealModule = realS1,
            s1SimModule = simS1!!,
            context = appContext
        )
        s2Module?.session?.setMode(realS1 == null)
    }

    // -------------------------------------------------------------------------
    // State machine
    // -------------------------------------------------------------------------

    sealed class State {
        object IDLE : State()
        object RUNNING : State()
        object PAUSED : State()
        object ENDED : State()
    }

    @Volatile
    var currentState: State = State.IDLE
        private set

    private fun transition(expected: State, next: State) {
        check(currentState == expected) {
            "Invalid state transition: current=${currentState::class.simpleName}, expected=${expected::class.simpleName}"
        }
        currentState = next
        Log.i(TAG, "State: ${expected::class.simpleName} -> ${next::class.simpleName}")
    }

    // -------------------------------------------------------------------------
    // Session-level identifiers (read-only from outside)
    // -------------------------------------------------------------------------

    private var currentUserId: Int = -1
    private var currentToken: String = ""
    private var currentSessionId: Int = -1
    private var currentPlanId: Int = -1
    private var currentExerciseType: String = DEFAULT_EXERCISE_TYPE
    private var sensorJointMapping: Map<String, String> = emptyMap()

    // -------------------------------------------------------------------------
    // Rehabilitation data cache (all private)
    // -------------------------------------------------------------------------

    /** In-memory cache of all FormatData chunks read during the session. */
    private val localDataCache = mutableListOf<FormatData>()

    /** AI recommendations fetched from V2 at session end. */
    private var localRecommendations: List<Map<String, Any?>> = emptyList()

    /** Live AI recommendations fetched periodically during the session. */
    private var latestLiveRecommendations: List<Map<String, Any?>> = emptyList()

    /** Session summary produced when stop() succeeds. Valid only in ENDED state. */
    private var sessionSummary: M1SessionSummary? = null

    /** Most recent FormatData for instantaneous UI access. Thread-safe. */
    private val latestDataRef: AtomicReference<FormatData?> = AtomicReference(null)

    /** Running counters for statistics. */
    private val totalSampleCount = AtomicInteger(0)
    private val totalAngleCount = AtomicInteger(0)
    private val totalErrorCount = AtomicInteger(0)

    // -------------------------------------------------------------------------
    // V2 upload queue & concurrency guards
    // -------------------------------------------------------------------------

    /** Pending FormatData chunks waiting to be uploaded to V2. */
    private val uploadQueue = ArrayDeque<FormatData>()

    /** True while an upload batch is in flight. */
    @Volatile
    private var isUploading: Boolean = false

    /** True while a download (recommendations fetch) is in flight. */
    @Volatile
    private var isDownloading: Boolean = false

    // -------------------------------------------------------------------------
    // Coroutine infrastructure (three independent 10 Hz loops)
    // -------------------------------------------------------------------------

    private var controllerScope: CoroutineScope? = null
    private var readJob: Job? = null
    private var uploadJob: Job? = null
    private var downloadJob: Job? = null

    // -------------------------------------------------------------------------
    // 1. Set sensor mode (real vs simulator)
    // -------------------------------------------------------------------------

    /**
     * Switches between real BLE sensor and simulator.
     * Precondition: current state must be [State.IDLE].
     */
    fun setSensorMode(useReal: Boolean) {
        transition(State.IDLE, State.IDLE)
        buildS2(useReal)
    }

    // -------------------------------------------------------------------------
    // 2. Start session
    // -------------------------------------------------------------------------

    /**
     * Starts a new session.
     * Workflow: IDLE -> RUNNING
     *  1. Fetch the nearest incomplete plan from V2
     *  2. Create a V2 session
     *  3. Start S2 acquisition
     *  4. Launch three 10 Hz loops (read / upload / download)
     */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            transition(State.IDLE, State.RUNNING)

            val prefs = appContext.getSharedPreferences("auth", Context.MODE_PRIVATE)
            currentToken = prefs.getString("token", "") ?: ""
            currentUserId = prefs.getInt("userId", 0)
            check(currentToken.isNotBlank() && currentUserId != 0) { "User not logged in" }

            val plan = fetchCurrentPlan()
            currentPlanId = plan?.first ?: -1
            currentExerciseType = plan?.second ?: DEFAULT_EXERCISE_TYPE
            Log.i(TAG, "Exercise type: $currentExerciseType, planId=$currentPlanId")

            val sessionResp = v2Api.createSession(currentUserId, currentToken)
            currentSessionId = parseId(sessionResp["id"])
            check(currentSessionId != -1) { "V2 createSession failed: $sessionResp" }
            Log.i(TAG, "V2 session created: id=$currentSessionId")

            val s2 = s2Module ?: throw IllegalStateException("S2 not initialized")
            sensorJointMapping = defaultJointMapping()
            val startResult = s2.session.start(
                sessionId = currentSessionId,
                userId = currentUserId,
                sensorJointMapping = sensorJointMapping,
                payloadStatus = currentExerciseType
            )
            check(startResult.success) { "S2 start failed: ${startResult.errorMessage}" }

            startAllLoops()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "start() failed", e)
            currentState = State.IDLE
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // 3. Pause session
    // -------------------------------------------------------------------------

    /**
     * Pauses the session.
     * Precondition: [State.RUNNING].
     * Postcondition: [State.PAUSED].
     * The S2 read loop stops; upload & download loops continue until queues drain.
     */
    fun pause(): Result<Unit> {
        return try {
            transition(State.RUNNING, State.PAUSED)
            stopReadLoop() // only stop S2 reading; upload/download keep running
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // 4. Resume session
    // -------------------------------------------------------------------------

    /**
     * Resumes the session.
     * Precondition: [State.PAUSED].
     * Postcondition: [State.RUNNING].
     * Drains and discards one stale buffer read to skip data accumulated during pause.
     */
    fun resume(): Result<Unit> {
        return try {
            transition(State.PAUSED, State.RUNNING)

            val s2 = s2Module ?: throw IllegalStateException("S2 not initialized")
            runBlocking(Dispatchers.IO) {
                try { s2.data.read() } catch (_: Exception) { }
            }

            startReadLoop()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // 5. Stop session
    // -------------------------------------------------------------------------

    /**
     * Ends the session.
     * Workflow: RUNNING/PAUSED -> ENDED
     *  1. Stop all loops
     *  2. Stop S2
     *  3. End V2 session
     *  4. Mark plan completed if exercise type matches
     *  5. Fetch recommendations from V2 and store locally
     */
    suspend fun stop(): Result<M1SessionSummary> = withContext(Dispatchers.IO) {
        try {
            val prev = currentState
            check(prev == State.RUNNING || prev == State.PAUSED) {
                "Cannot stop from state: $prev"
            }
            currentState = State.ENDED

            stopAllLoops()

            val s2 = s2Module ?: throw IllegalStateException("S2 not initialized")
            val s2Summary = s2.session.stop()
            Log.i(TAG, "S2 stopped: samples=${s2Summary.sampleCount}")

            v2Api.endSession(currentSessionId, currentToken)
            markPlanCompletedIfNeeded()

            // Final fetch of recommendations at session end
            fetchRecommendations()

            val summary = M1SessionSummary(
                sessionId = currentSessionId,
                sampleCount = s2Summary.sampleCount,
                errorCount = s2Summary.errorCount,
                startTime = s2Summary.startTime,
                endTime = s2Summary.endTime,
                exerciseType = currentExerciseType,
                planId = currentPlanId
            )
            sessionSummary = summary

            Result.success(summary)
        } catch (e: Exception) {
            Log.e(TAG, "stop() failed", e)
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // 6. Reset session
    // -------------------------------------------------------------------------

    /**
     * Resets the controller for the next session.
     * Precondition: [State.ENDED].
     * Postcondition: [State.IDLE].
     * Clears all session-specific data, queues, and counters.
     */
    fun reset(): Result<Unit> {
        return try {
            transition(State.ENDED, State.IDLE)

            currentSessionId = -1
            currentPlanId = -1
            currentExerciseType = DEFAULT_EXERCISE_TYPE
            sensorJointMapping = emptyMap()
            latestDataRef.set(null)
            sessionSummary = null

            synchronized(localDataCache) { localDataCache.clear() }
            synchronized(uploadQueue) { uploadQueue.clear() }
            localRecommendations = emptyList()
            latestLiveRecommendations = emptyList()
            totalSampleCount.set(0)
            totalAngleCount.set(0)
            totalErrorCount.set(0)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // 7. Three 10 Hz loops (read / upload / download)
    // -------------------------------------------------------------------------

    private fun startAllLoops() {
        stopAllLoops()
        controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        startReadLoop()
        startUploadLoop()
        startDownloadLoop()
    }

    /** 10 Hz S2 read loop: pure memory, never blocked by network. */
    private fun startReadLoop() {
        readJob = controllerScope?.launch {
            while (isActive && currentState == State.RUNNING) {
                try {
                    val s2 = s2Module ?: break
                    val data: FormatData = s2.data.read()

                    latestDataRef.set(data)
                    synchronized(localDataCache) { localDataCache.add(data) }
                    totalSampleCount.addAndGet(data.sensorData.size)
                    totalAngleCount.addAndGet(data.targetAngles.size)
                    totalErrorCount.addAndGet(data.errors.size)

                    // enqueue for upload; do NOT upload here
                    synchronized(uploadQueue) { uploadQueue.add(data) }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Read loop error", e)
                }
                delay(100)
            }
        }
    }

    /** 10 Hz V2 upload loop: skips if previous upload still in flight. */
    private fun startUploadLoop() {
        uploadJob = controllerScope?.launch {
            while (isActive && (currentState == State.RUNNING || currentState == State.PAUSED)) {
                val shouldFlush = synchronized(uploadQueue) { uploadQueue.isNotEmpty() } && !isUploading
                if (shouldFlush) {
                    isUploading = true
                    launch {
                        try {
                            flushUploadQueue()
                        } finally {
                            isUploading = false
                        }
                    }
                }
                delay(100)
            }
        }
    }

    /** 10 Hz V2 download loop: skips if previous download still in flight. */
    private fun startDownloadLoop() {
        downloadJob = controllerScope?.launch {
            while (isActive && (currentState == State.RUNNING || currentState == State.PAUSED)) {
                if (!isDownloading && currentSessionId != -1) {
                    isDownloading = true
                    launch {
                        try {
                            val recs = v2Api.getSessionRecommendations(currentSessionId, currentToken)
                            latestLiveRecommendations = recs
                        } catch (e: Exception) {
                            Log.w(TAG, "Download loop error: ${e.message}")
                        } finally {
                            isDownloading = false
                        }
                    }
                }
                delay(100)
            }
        }
    }

    private fun stopReadLoop() {
        readJob?.cancel()
        readJob = null
    }

    private fun stopAllLoops() {
        readJob?.cancel()
        readJob = null
        uploadJob?.cancel()
        uploadJob = null
        downloadJob?.cancel()
        downloadJob = null
        controllerScope?.cancel()
        controllerScope = null
    }

    /**
     * Drains the upload queue and sends to V2.
     * If any upload fails, the entire batch is re-queued at the head for the next cycle.
     */
    private suspend fun flushUploadQueue() {
        val batch = synchronized(uploadQueue) {
            if (uploadQueue.isEmpty()) return
            val snapshot = uploadQueue.toList()
            uploadQueue.clear()
            snapshot
        }

        try {
            // TODO: Replace with v2Api.uploadMeasurementsBatch() when available.
            // Current fallback: sequential single-item upload inside an async block.
            for (data in batch) {
                val payload = formatDataToPayload(data)
                v2Api.uploadMeasurement(payload, currentToken)
            }
            Log.i(TAG, "Uploaded ${batch.size} items")
        } catch (e: Exception) {
            // Re-queue at head so next flush retries in order
            synchronized(uploadQueue) {
                uploadQueue.addAll(0, batch)
            }
            Log.w(TAG, "Upload failed, re-queued ${batch.size} items", e)
        }
    }

    // -------------------------------------------------------------------------
    // 8. Rich accessors for rehabilitation data (read-only)
    // -------------------------------------------------------------------------

    // --- Latest instantaneous data ---

    /** Returns the most recent [FormatData] chunk, or null if none read yet. */
    fun getLatestData(): FormatData? = latestDataRef.get()

    /** Returns the most recent [TargetAngle], or null if none available. */
    fun getLatestAngle(): TargetAngle? = latestDataRef.get()?.targetAngles?.lastOrNull()

    /** Returns the numeric value of the latest angle in degrees, or null. */
    fun getLatestAngleValue(): Float? = getLatestAngle()?.angle?.toFloat()

    /** Returns the angle identifier (e.g. "left_knee") of the latest reading, or null. */
    fun getLatestAngleId(): String? = getLatestAngle()?.angleID

    /** Returns the timestamp (ms) of the latest reading, or null. */
    fun getLatestTimestamp(): Long? = getLatestAngle()?.timestamp

    // --- Cumulative statistics ---

    /** Returns the total count of raw sensor samples collected so far. */
    fun getTotalSampleCount(): Int = totalSampleCount.get()

    /** Returns the total count of computed target angles so far. */
    fun getTotalAngleCount(): Int = totalAngleCount.get()

    /** Returns the total count of error events so far. */
    fun getTotalErrorCount(): Int = totalErrorCount.get()

    // --- Full cache access ---

    /** Returns a defensive copy of the entire local data cache. */
    fun getLocalCache(): List<FormatData> = synchronized(localDataCache) { localDataCache.toList() }

    /** Returns only the target angles from the entire cache (flattened). */
    fun getAllAngles(): List<TargetAngle> = synchronized(localDataCache) {
        localDataCache.flatMap { it.targetAngles }
    }

    /** Returns only the error events from the entire cache (flattened). */
    fun getAllErrors(): List<com.dsd.s2.model.ErrorEvent> = synchronized(localDataCache) {
        localDataCache.flatMap { it.errors }
    }

    /** Returns only the raw sensor samples from the entire cache (flattened). */
    fun getAllSensorSamples(): List<SensorSample> = synchronized(localDataCache) {
        localDataCache.flatMap { it.sensorData }
    }

    // --- Recommendations & summary ---

    /** Returns AI recommendations fetched at session end. Empty if not yet fetched. */
    fun getLocalRecommendations(): List<Map<String, Any?>> = localRecommendations

    /** Returns the latest live AI recommendations fetched during the session. */
    fun getLatestLiveRecommendations(): List<Map<String, Any?>> = latestLiveRecommendations

    /** Returns the session summary produced by stop(). Null until ENDED. */
    fun getSessionSummary(): M1SessionSummary? = sessionSummary

    // --- Session metadata ---

    /** Returns the current V2 session ID, or -1 if none. */
    fun getCurrentSessionId(): Int = currentSessionId

    /** Returns the current exercise type identifier. */
    fun getCurrentExerciseType(): String = currentExerciseType

    /** Returns the current plan ID from V2, or -1 if none. */
    fun getCurrentPlanId(): Int = currentPlanId

    /** Returns true if a session is currently active (RUNNING or PAUSED). */
    fun hasActiveSession(): Boolean = currentState == State.RUNNING || currentState == State.PAUSED

    /** Returns the current controller state. */
    fun getState(): State = currentState

    /** Returns the number of FormatData chunks currently waiting in the upload queue. */
    fun getUploadQueueSize(): Int = synchronized(uploadQueue) { uploadQueue.size }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private suspend fun fetchCurrentPlan(): Pair<Int, String>? {
        return try {
            val now = java.time.LocalDate.now().toString()
            val schedules = v2Api.getSchedule(currentUserId, currentToken)
            val pending = schedules
                .filter {
                    val status = it["status"] as? String
                    val date = it["date"] as? String ?: ""
                    status != "completed" && date >= now
                }
                .sortedBy { it["date"] as? String ?: "" }
            val plan = pending.firstOrNull() ?: return null
            val id = parseId(plan["id"])
            val exercise = plan["exercise"] as? String ?: DEFAULT_EXERCISE_TYPE
            Pair(id, exercise)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch plan, using default", e)
            null
        }
    }

    private suspend fun markPlanCompletedIfNeeded() {
        try {
            val now = java.time.LocalDate.now().toString()
            val schedules = v2Api.getSchedule(currentUserId, currentToken)
            val target = schedules
                .filter {
                    val status = it["status"] as? String
                    val date = it["date"] as? String ?: ""
                    status != "completed" && date >= now
                }
                .minByOrNull { it["date"] as? String ?: "" } ?: return
            val id = parseId(target["id"])
            val exercise = target["exercise"] as? String
            if (id != -1 && exercise == currentExerciseType) {
                v2Api.updateSchedule(id, "completed", currentToken)
                Log.i(TAG, "Plan $id marked completed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark plan completed", e)
        }
    }

    private suspend fun fetchRecommendations() {
        try {
            localRecommendations = v2Api.getSessionRecommendations(currentSessionId, currentToken)
            Log.i(TAG, "Recommendations cached: ${localRecommendations.size} items")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch recommendations", e)
            localRecommendations = emptyList()
        }
    }

    private fun formatDataToPayload(data: FormatData): Map<String, Any> {
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)
        return mapOf(
            "sessionId" to currentSessionId,
            "targetAngles" to data.targetAngles.map {
                mapOf(
                    "timestamp" to formatter.format(Instant.ofEpochMilli(it.timestamp)),
                    "angleID" to it.angleID,
                    "angle" to it.angle
                )
            },
            "errors" to data.errors.map {
                mapOf(
                    "timestamp" to it.timestamp,
                    "errorType" to it.errorType,
                    "message" to it.message
                )
            },
            "sensorData" to data.sensorData.map {
                mapOf(
                    "timestamp" to it.timestamp,
                    "sensorId" to it.deviceId,
                    "accX" to it.accX, "accY" to it.accY, "accZ" to it.accZ,
                    "gyroX" to it.gyroX, "gyroY" to it.gyroY, "gyroZ" to it.gyroZ,
                    "roll" to it.roll, "pitch" to it.pitch, "yaw" to it.yaw
                )
            }
        )
    }

    private fun defaultJointMapping(): Map<String, String> {
        return mapOf(
            "SIM_SENSOR_A" to "left_knee",
            "SIM_SENSOR_B" to "left_knee"
        )
    }

    private fun parseId(raw: Any?): Int {
        return when (raw) {
            is Double -> raw.toInt()
            is Int -> raw
            is Long -> raw.toInt()
            else -> -1
        }
    }
}

// -----------------------------------------------------------------------------
// M1-layer session summary
// -----------------------------------------------------------------------------

data class M1SessionSummary(
    val sessionId: Int,
    val sampleCount: Int,
    val errorCount: Int,
    val startTime: String,
    val endTime: String,
    val exerciseType: String,
    val planId: Int
)