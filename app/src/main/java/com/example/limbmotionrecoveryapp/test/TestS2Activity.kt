package com.example.limbmotionrecoveryapp.test

import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.limbmotionrecoveryapp.R
import com.example.limbmotionrecoveryapp.session.SessionController
import kotlinx.coroutines.*

/**
 * SessionController Integration Test Activity
 *
 * Tests the full session lifecycle through SessionController:
 * - Singleton initialization
 * - Sensor mode switching (real vs simulator)
 * - State machine transitions: IDLE -> RUNNING <-> PAUSED -> ENDED -> IDLE
 * - Real-time data accessors (latest angle, cumulative stats, cache)
 * - V2 upload/download loop behavior (observed via queue size and recommendations)
 */
class TestS2Activity : AppCompatActivity() {

    // UI References
    private lateinit var tvData: TextView      // Top: real-time data panel
    private lateinit var tvLog: TextView       // Bottom: operation log
    private lateinit var btnMode: Button
    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnResume: Button
    private lateinit var btnStop: Button
    private lateinit var btnReset: Button
    private lateinit var btnClear: Button

    // Controller singleton (initialized in onCreate)
    private lateinit var controller: SessionController

    // UI refresh coroutine (200ms)
    private var uiRefreshJob: Job? = null

    // Tracks whether simulator mode is currently active
    private var isSimulatorMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_s2)

        initViews()
        setupButtons()

        // Initialize singleton with Context on first creation
        controller = SessionController.getInstance(this)

        startUiRefresh()
        log("Test page loaded")
        log("Current state: ${controller.getState()}")
    }

    /** Bind all views */
    private fun initViews() {
        tvData = findViewById(R.id.tvData)
        tvLog = findViewById(R.id.tvLog)
        btnMode = findViewById(R.id.btnMode)
        btnStart = findViewById(R.id.btnStart)
        btnPause = findViewById(R.id.btnPause)
        btnResume = findViewById(R.id.btnResume)
        btnStop = findViewById(R.id.btnStop)
        btnReset = findViewById(R.id.btnReset)
        btnClear = findViewById(R.id.btnClear)
    }

    /** Wire all button click handlers */
    private fun setupButtons() {

        // --- Toggle Sensor Mode (simulator / real) ---
        btnMode.setOnClickListener {
            try {
                isSimulatorMode = !isSimulatorMode
                // false = simulator, true = real BLE
                controller.setSensorMode(!isSimulatorMode)
                val modeText = if (isSimulatorMode) "SIMULATOR" else "REAL SENSOR"
                log("Switched to: $modeText")
                btnMode.text = if (isSimulatorMode) "Switch Real" else "Switch Sim"
            } catch (e: Exception) {
                log("Mode switch failed: ${e.message}")
            }
        }

        // --- Start Session ---
        btnStart.setOnClickListener {
            lifecycleScope.launch {
                log("Starting session...")
                val result = controller.start()
                result.onSuccess {
                    log("Session started | ID=${controller.getCurrentSessionId()}")
                }.onFailure {
                    log("Start failed: ${it.message}")
                }
            }
        }

        // --- Pause Session ---
        btnPause.setOnClickListener {
            val result = controller.pause()
            result.onSuccess {
                log("Paused (S2 read stopped; upload/download loops continue)")
            }.onFailure {
                log("Pause failed: ${it.message}")
            }
        }

        // --- Resume Session ---
        btnResume.setOnClickListener {
            val result = controller.resume()
            result.onSuccess {
                log("Resumed (stale buffer drained)")
            }.onFailure {
                log("Resume failed: ${it.message}")
            }
        }

        // --- Stop Session ---
        btnStop.setOnClickListener {
            lifecycleScope.launch {
                log("Stopping session...")
                val result = controller.stop()
                result.onSuccess { summary ->
                    log("Session ended")
                    log("Summary:")
                    log("  Session ID: ${summary.sessionId}")
                    log("  Samples: ${summary.sampleCount}")
                    log("  Errors: ${summary.errorCount}")
                    log("  Exercise: ${summary.exerciseType}")
                    log("  Start: ${summary.startTime}")
                    log("  End: ${summary.endTime}")

                    // Display AI recommendations fetched at session end
                    val recs = controller.getLocalRecommendations()
                    log("AI Recommendations: ${recs.size} items")
                    recs.forEachIndexed { i, rec ->
                        val movement = rec["movement"] as? String ?: "unknown"
                        val confidence = (rec["confidence"] as? Double) ?: 0.0
                        val status = rec["status"] as? String ?: "pending"
                        log("  [$i] $movement | confidence:${"%.0f".format(confidence * 100)}% | $status")
                    }
                }.onFailure {
                    log("Stop failed: ${it.message}")
                }
            }
        }

        // --- Reset Session ---
        btnReset.setOnClickListener {
            val result = controller.reset()
            result.onSuccess {
                log("Reset complete | State=${controller.getState()}")
            }.onFailure {
                log("Reset failed: ${it.message}")
            }
        }

        // --- Clear Log ---
        btnClear.setOnClickListener {
            tvLog.text = ""
            log("Log cleared")
        }
    }

    // -------------------------------------------------------------------------
    // UI Refresh Loop (200ms)
    // -------------------------------------------------------------------------

    private fun startUiRefresh() {
        uiRefreshJob = lifecycleScope.launch {
            while (isActive) {
                refreshDataPanel()
                delay(200)
            }
        }
    }

    /** Refresh the top data panel with latest controller state */
    private fun refreshDataPanel() {
        val sb = StringBuilder()

        // --- Metadata ---
        sb.appendLine("[STATE] ${controller.getState()}")
        sb.appendLine("[SESSION] ${controller.getCurrentSessionId()}")
        sb.appendLine("[EXERCISE] ${controller.getCurrentExerciseType()}")
        sb.appendLine("[PLAN_ID] ${controller.getCurrentPlanId()}")
        sb.appendLine("------------------------------")

        // --- Latest Instantaneous Data ---
        sb.appendLine("[LATEST DATA]")
        val angleVal = controller.getLatestAngleValue()
        val angleId = controller.getLatestAngleId()
        val angleTs = controller.getLatestTimestamp()
        if (angleVal != null) {
            sb.appendLine("  AngleID: $angleId")
            sb.appendLine("  Value: ${"%.2f".format(angleVal)} deg")
            sb.appendLine("  Timestamp: $angleTs")
        } else {
            sb.appendLine("  (none)")
        }
        sb.appendLine("------------------------------")

        // --- Cumulative Statistics ---
        sb.appendLine("[STATISTICS]")
        sb.appendLine("  Total Samples: ${controller.getTotalSampleCount()}")
        sb.appendLine("  Total Angles: ${controller.getTotalAngleCount()}")
        sb.appendLine("  Total Errors: ${controller.getTotalErrorCount()}")
        sb.appendLine("------------------------------")

        // --- Cache Overview ---
        sb.appendLine("[CACHE]")
        sb.appendLine("  FormatData chunks: ${controller.getLocalCache().size}")
        sb.appendLine("  All angles: ${controller.getAllAngles().size}")
        sb.appendLine("  All errors: ${controller.getAllErrors().size}")
        sb.appendLine("  All sensor samples: ${controller.getAllSensorSamples().size}")
        sb.appendLine("------------------------------")

        // --- Live Recommendations & Queue ---
        sb.appendLine("[V2 SYNC]")
        sb.appendLine("  Upload queue: ${controller.getUploadQueueSize()}")
        sb.appendLine("  Live recommendations: ${controller.getLatestLiveRecommendations().size}")

        tvData.text = sb.toString()
    }

    /** Append a line to the log view and auto-scroll to bottom */
    private fun log(msg: String) {
        tvLog.append("$msg\n")
        val scrollView = tvLog.parent as? ScrollView
        scrollView?.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiRefreshJob?.cancel()
    }
}