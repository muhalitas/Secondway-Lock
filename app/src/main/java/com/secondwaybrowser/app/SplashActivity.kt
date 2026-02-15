package com.secondwaybrowser.app

import app.secondway.lock.R

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LanguageHelper.applySavedLocale(this)
        setContentView(R.layout.activity_splash)
        Handler(Looper.getMainLooper()).postDelayed({
            val next = if (SecondwayOnboardingPrefs.isCompleted(this)) {
                Intent(this, MainActivity::class.java)
            } else {
                Intent(this, OnboardingActivity::class.java)
            }
            startActivity(next)
            overridePendingTransition(0, 0)
            finish()
        }, 550L)
    }
}
