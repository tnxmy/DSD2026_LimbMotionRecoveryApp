package com.example.limbmotionrecoveryapp.test

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.example.limbmotionrecoveryapp.R
import com.example.limbmotionrecoveryapp.view.LegView

class LegViewTestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leg_view_test)

        val legLeft = findViewById<LegView>(R.id.legLeft).apply {
            setSide(LegView.LegSide.LEFT)
        }
        val legRight = findViewById<LegView>(R.id.legRight).apply {
            setSide(LegView.LegSide.RIGHT)
        }

        // Static acceptance: set a fixed angle and screenshot for comparison
        legLeft.setAngle(90f)
        legRight.setAngle(90f)

        // Dynamic acceptance: cycle through angles every 800ms
        val angles = listOf(0f, 45f, 90f, 135f, 180f, 90f, 0f)
        var idx = 0
        Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
            override fun run() {
                legLeft.setAngle(angles[idx])
                legRight.setAngle(angles[idx])
                idx = (idx + 1) % angles.size
                Handler(Looper.getMainLooper()).postDelayed(this, 800)
            }
        }, 800)
    }
}