package com.example.limon

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MusicActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music)

        val textView: TextView = findViewById(R.id.textViewmp3)
        textView.text = "MP3 Плеер (заглушка)"
    }
}