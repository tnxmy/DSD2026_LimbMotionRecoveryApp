package com.example.limbmotionrecoveryapp.test

import android.os.Bundle
import android.widget.Button
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

    private lateinit var legLeft: LegView
    private lateinit var legRight: LegView
    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnResume: Button
    private lateinit var btnStop: Button
    private lateinit var btnReset: Button

    private val controller by lazy { SessionController.getInstance(applicationContext) }
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exercise)

        legLeft = findViewById<LegView>(R.id.legLeft).apply {
            setSide(LegView.LegSide.LEFT)
        }
        legRight = findViewById<LegView>(R.id.legRight).apply {
            setSide(LegView.LegSide.RIGHT)
        }

        btnStart = findViewById<Button>(R.id.btnStart)
        btnPause = findViewById<Button>(R.id.btnPause)
        btnResume = findViewById<Button>(R.id.btnResume)
        btnStop = findViewById<Button>(R.id.btnStop)
        btnReset = findViewById<Button>(R.id.btnReset)

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
                    if (angles.size >= 2) {
                        // Assumption: latest = left, second-latest = right
                        val leftCandidate = angles[angles.size - 1]
                        val rightCandidate = angles[angles.size - 2]

                        if (leftCandidate.angleID == "left_knee") {
                            legLeft.setAngle(leftCandidate.angle.toFloat())
                        }
                        if (rightCandidate.angleID == "right_knee") {
                            legRight.setAngle(rightCandidate.angle.toFloat())
                        }
                    } else if (angles.size == 1) {
                        // Fallback single angle
                        when (angles[0].angleID) {
                            "left_knee" -> legLeft.setAngle(angles[0].angle.toFloat())
                            "right_knee" -> legRight.setAngle(angles[0].angle.toFloat())
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