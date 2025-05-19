package com.example.limon

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val calcButton: Button = findViewById(R.id.calc_button)
        calcButton.setOnClickListener {
            val intent = Intent(this, CalcActivity::class.java)
            startActivity(intent)
        }

        val musicButton: Button = findViewById(R.id.music_button)
        musicButton.setOnClickListener {
            val intent = Intent(this, Mp3PlayerActivity::class.java)
            startActivity(intent)
        }

        val systemInfoButton: Button = findViewById(R.id.system_info_button)
        systemInfoButton.setOnClickListener {
            val intent = Intent(this, SystemInfoActivity::class.java)
            startActivity(intent)
        }
    }
}