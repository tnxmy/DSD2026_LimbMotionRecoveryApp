package com.example.limbmotionrecoveryapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.limbmotionrecoveryapp.screens.home.HomeFragment
import com.example.limbmotionrecoveryapp.screens.plans.PlansFragment
import com.example.limbmotionrecoveryapp.screens.profile.ProfileFragment
import com.example.limbmotionrecoveryapp.screens.progress.ProgressFragment
import com.google.android.material.bottomnavigation.BottomNavigationView

import android.content.Intent

class MainActivity : AppCompatActivity() {

    private val homeFragment = HomeFragment()
    private val plansFragment = PlansFragment()
    private val progressFragment = ProgressFragment()
    private val profileFragment = ProfileFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        //test-code-Yiding Wang
        //startActivity(Intent(this, com.example.limbmotionrecoveryapp.test.TestS2Activity::class.java))
        //startActivity(Intent(this, com.example.limbmotionrecoveryapp.test.LegViewTestActivity::class.java))
        startActivity(Intent(this, com.example.limbmotionrecoveryapp.test.ExerciseActivity::class.java))

        //test-code-end

        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            showFragment(homeFragment)
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.selectedItemId = R.id.nav_home

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> showFragment(homeFragment)
                R.id.nav_plans -> showFragment(plansFragment)
                R.id.nav_progress -> showFragment(progressFragment)
                R.id.nav_profile -> showFragment(profileFragment)
            }
            true
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
