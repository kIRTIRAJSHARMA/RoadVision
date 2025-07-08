package com.example.roadvision

import android.content.Intent
import android.os.Bundle
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_welcome)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val logo = findViewById<ImageView>(R.id.logoImage)
        val startButton = findViewById<Button>(R.id.getStartedButton)

        val bounce = AnimationUtils.loadAnimation(this, R.anim.bounce)
        val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade)

        logo.startAnimation(bounce)
        startButton.startAnimation(fadeIn)

        startButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
