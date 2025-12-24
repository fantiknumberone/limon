package com.example.limon

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException

class Mp3PlayerActivity : AppCompatActivity() {
    private var mediaPlayer: MediaPlayer? = null
    private var songs = mutableListOf<File>()
    private var currentSongIndex = 0
    private var isPlaying = false
    private lateinit var handler: Handler
    private val APP_FOLDER_NAME = "MP3Player"

    private lateinit var buttonPrev: Button
    private lateinit var buttonNext: Button
    private lateinit var buttonPause: Button
    private lateinit var volumeBar: SeekBar
    private lateinit var seekBar: SeekBar
    private lateinit var textViewMp3: TextView
    private lateinit var switchBackground: SwitchCompat

    companion object {
        private const val REQUEST_PERMISSION_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music)

        initViews()
        handler = Handler(Looper.getMainLooper())
        initMediaPlayer()
        checkPermissions()
    }

    private fun initViews() {
        buttonPrev = findViewById(R.id.buttonPrev)
        buttonNext = findViewById(R.id.buttonNext)
        buttonPause = findViewById(R.id.buttonPause)
        volumeBar = findViewById(R.id.volumeBar)
        seekBar = findViewById(R.id.remBar)
        textViewMp3 = findViewById(R.id.textViewmp3)
        switchBackground = findViewById(R.id.switchControl)

        buttonPrev.setOnClickListener { playPreviousSong() }
        buttonNext.setOnClickListener { playNextSong() }
        buttonPause.setOnClickListener { togglePlayPause() }

        volumeBar.progress = 50
        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                mediaPlayer?.setVolume(progress / 100f, progress / 100f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        switchBackground.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                Toast.makeText(this, "Background play enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Background play disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            setOnErrorListener { _, what, extra ->
                false
            }
            setOnCompletionListener {
                playNextSong()
            }
        }
    }

    private fun checkPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadSongs()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_PERMISSION_CODE)
        }
    }

    private fun loadSongs() {
        try {
            val musicDir = File(Environment.getExternalStorageDirectory(), APP_FOLDER_NAME)
            if (!musicDir.exists()) {
                if (!musicDir.mkdirs()) {
                    Toast.makeText(this, "Failed to create music directory", Toast.LENGTH_SHORT).show()
                    return
                }
                Toast.makeText(this, "Created $APP_FOLDER_NAME folder", Toast.LENGTH_SHORT).show()
            }

            songs = musicDir.listFiles()?.filter { file ->
                file.isFile && file.name.endsWith(".mp3", ignoreCase = true)
            }?.toMutableList() ?: mutableListOf()

            if (songs.isEmpty()) {
                Toast.makeText(this, "No MP3 files found in $APP_FOLDER_NAME", Toast.LENGTH_SHORT).show()
            } else {
                playSong(0)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load songs", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playSong(index: Int) {
        if (index !in songs.indices) return

        try {
            mediaPlayer?.let { player ->
                player.reset()
                player.setDataSource(songs[index].absolutePath)
                player.prepare()
                player.start()

                isPlaying = true
                currentSongIndex = index

                runOnUiThread {
                    buttonPause.text = "Pause"
                    textViewMp3.text = songs[index].name.replace(".mp3", "")
                    seekBar.max = player.duration
                    updateSeekBar()
                }
            }
        } catch (e: IllegalStateException) {
            Toast.makeText(this, "Player error", Toast.LENGTH_SHORT).show()
            initMediaPlayer()
            playSong(index)
        } catch (e: IOException) {
            Toast.makeText(this, "File error", Toast.LENGTH_SHORT).show()
            playNextSong()
        } catch (e: Exception) {
            Toast.makeText(this, "Playback failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSeekBar() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(object : Runnable {
            override fun run() {
                mediaPlayer?.let {
                    seekBar.progress = it.currentPosition
                    handler.postDelayed(this, 1000)
                }
            }
        }, 0)
    }

    private fun togglePlayPause() {
        mediaPlayer?.let { player ->
            if (isPlaying) {
                player.pause()
                buttonPause.text = "Play"
            } else {
                player.start()
                buttonPause.text = "Pause"
            }
            isPlaying = !isPlaying
        }
    }

    private fun playNextSong() {
        currentSongIndex = (currentSongIndex + 1) % songs.size
        playSong(currentSongIndex)
    }

    private fun playPreviousSong() {
        currentSongIndex = if (currentSongIndex - 1 < 0) songs.size - 1 else currentSongIndex - 1
        playSong(currentSongIndex)
    }

    override fun onPause() {
        super.onPause()
        if (!switchBackground.isChecked) {
            mediaPlayer?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        mediaPlayer = null
    }
}