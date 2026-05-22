package com.example.limbmotionrecoveryapp.test

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.limbmotionrecoveryapp.R
import com.example.limbmotionrecoveryapp.session.SessionController
import com.example.limbmotionrecoveryapp.view.LegView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExerciseActivity : AppCompatActivity() {

    private lateinit var legView: LegView
    private lateinit var rgMode: RadioGroup
    private lateinit var cbShowLeft: CheckBox
    private lateinit var rgColorLeft: RadioGroup
    private lateinit var cbShowRight: CheckBox
    private lateinit var rgColorRight: RadioGroup
    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnResume: Button
    private lateinit var btnStop: Button
    private lateinit var btnReset: Button

    private val controller by lazy { SessionController.getInstance(applicationContext) }
    private var refreshJob: Job? = null
    private val blueLimb = intArrayOf(
        Color.parseColor("#87CEEB"),
        Color.parseColor("#4A90D9"),
        Color.parseColor("#1A3A5C")
    )
    private val blueCap = intArrayOf(
        Color.parseColor("#A8D8FF"),
        Color.parseColor("#2C5F8A")
    )

    private val redLimb = intArrayOf(
        Color.parseColor("#FFB6C1"),
        Color.parseColor("#D94A4A"),
        Color.parseColor("#5C1A1A")
    )
    private val redCap = intArrayOf(
        Color.parseColor("#FFD8D8"),
        Color.parseColor("#8A2C2C")
    )

    private val greenLimb = intArrayOf(
        Color.parseColor("#90EE90"),
        Color.parseColor("#32CD32"),
        Color.parseColor("#006400")
    )
    private val greenCap = intArrayOf(
        Color.parseColor("#98FB98"),
        Color.parseColor("#228B22")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exercise)

        legView = findViewById(R.id.legView)
        rgMode = findViewById(R.id.rgMode)
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbSquat -> LegView.DisplayMode.SQUAT
                R.id.rbStepping -> LegView.DisplayMode.STEPPING
                else -> LegView.DisplayMode.HORIZONTAL
            }
            legView.setDisplayMode(mode)
        }
        cbShowLeft = findViewById(R.id.cbShowLeft)
        cbShowLeft.setOnCheckedChangeListener { _, isChecked ->
            legView.setShowLeft(isChecked)
        }

        rgColorLeft = findViewById(R.id.rgColorLeft)
        rgColorLeft.setOnCheckedChangeListener { _, checkedId ->
            val (limb, cap) = when (checkedId) {
                R.id.rbLeftRed -> Pair(redLimb, redCap)
                R.id.rbLeftGreen -> Pair(greenLimb, greenCap)
                else -> Pair(blueLimb, blueCap)
            }
            legView.setLeftLimbColors(limb)
            legView.setLeftCapColors(cap)
        }
        cbShowRight = findViewById(R.id.cbShowRight)
        cbShowRight.setOnCheckedChangeListener { _, isChecked ->
            legView.setShowRight(isChecked)
        }

        rgColorRight = findViewById(R.id.rgColorRight)
        rgColorRight.setOnCheckedChangeListener { _, checkedId ->
            val (limb, cap) = when (checkedId) {
                R.id.rbRightBlue -> Pair(blueLimb, blueCap)
                R.id.rbRightGreen -> Pair(greenLimb, greenCap)
                else -> Pair(redLimb, redCap)
            }
            legView.setRightLimbColors(limb)
            legView.setRightCapColors(cap)
        }
        btnStart = findViewById(R.id.btnStart)
        btnPause = findViewById(R.id.btnPause)
        btnResume = findViewById(R.id.btnResume)
        btnStop = findViewById(R.id.btnStop)
        btnReset = findViewById(R.id.btnReset)

        btnStart.setOnClickListener { startSession() }
        btnPause.setOnClickListener { controller.pause() }
        btnResume.setOnClickListener { controller.resume() }
        btnStop.setOnClickListener { stopSession() }
        btnReset.setOnClickListener { controller.reset() }
    }

    private fun startSession() {
        lifecycleScope.launch(Dispatchers.IO) {
            controller.setSensorMode(true)
            controller.setExerciseType("bend_knee_10")

            val result = controller.start()
            if (result.isSuccess) {
                withContext(Dispatchers.Main) {
                    startRefreshLoop()
                }
            }
        }
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                val data = controller.getLatestData()

                data?.targetAngles?.let { angles ->
                    if (angles.isNotEmpty()) {
                        val latest = angles.last()
                        if (latest.angleID == "left_knee") {
                            legView.setLeftAngle(latest.angle.toFloat())
                        }
                    }
                }

                delay(100)
            }
        }
    }

    private fun stopSession() {
        lifecycleScope.launch(Dispatchers.IO) {
            controller.stop()
            withContext(Dispatchers.Main) {
                refreshJob?.cancel()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshJob?.cancel()
    }
}