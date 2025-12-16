package com.example.limon

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SystemInfoActivity : AppCompatActivity() {

    private lateinit var tvTime: TextView
    private lateinit var tvSignal: TextView
    private lateinit var tvLocation: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvServerStatus: TextView
    private lateinit var tvBufferCount: TextView
    private lateinit var switchOnlineMode: Switch

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var ws: WebSocketClient

    private var signalStrengthValue: Int = 0
    private var currentLocation: Location? = null
    private val BUFFER_FILE = "data_buffer_service.json"
    private var isServiceRunning = false

    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 5000
    ).setMinUpdateIntervalMillis(2000).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            currentLocation = result.lastLocation
            updateUI()
        }
    }

    @Suppress("DEPRECATION")
    private val legacySignalListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(s: SignalStrength) {
            updateSignal(s)
        }
    }

    private val newSignalListener =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(s: SignalStrength) {
                    updateSignal(s)
                }
            }
        } else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_system_info)

        initViews()
        initWebSocket()
        checkPermissions()
    }

    private fun initWebSocket() {
        ws = WebSocketClient("ws://192.168.0.11:8000/ws", object : ConnectionListener {
            override fun onConnected() {
                runOnUiThread {
                    tvServerStatus.text = "Сервер: подключен"
                    tvServerStatus.setTextColor(ContextCompat.getColor(this@SystemInfoActivity, android.R.color.holo_green_dark))
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    tvServerStatus.text = "Сервер: отключен"
                    tvServerStatus.setTextColor(ContextCompat.getColor(this@SystemInfoActivity, android.R.color.holo_red_dark))
                }
            }
        })
        ws.connect()
    }

    private fun initViews() {
        tvTime = findViewById(R.id.tvTime)
        tvSignal = findViewById(R.id.tvSignal)
        tvLocation = findViewById(R.id.tvLocation)
        tvStatus = findViewById(R.id.tvStatus)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvBufferCount = findViewById(R.id.tvBufferCount)
        switchOnlineMode = findViewById(R.id.switchOnlineMode)

        switchOnlineMode.isChecked = true
        switchOnlineMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startTrackingService(true)
                tvStatus.text = "Фоновый режим: ВКЛ"
            } else {
                stopTrackingService()
                tvStatus.text = "Фоновый режим: ВЫКЛ"
                stopLocationUpdates()
            }
        }
    }

    private fun checkPermissions() {
        if (requiredPermissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            startEverything()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, 101)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startEverything() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates()

        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.registerTelephonyCallback(mainExecutor, newSignalListener!!)
        } else {
            telephonyManager.listen(legacySignalListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        }

        // Запускаем сервис при старте если переключатель включен
        if (switchOnlineMode.isChecked) {
            startTrackingService(true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun startTrackingService(onlineMode: Boolean) {
        val intent = Intent(this, LocationTrackingService::class.java)
        intent.action = "START_TRACKING"
        intent.putExtra("online_mode", onlineMode)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isServiceRunning = true
    }

    private fun stopTrackingService() {
        val intent = Intent(this, LocationTrackingService::class.java)
        intent.action = "STOP_TRACKING"
        stopService(intent)
        isServiceRunning = false
    }

    private fun updateSignal(s: SignalStrength) {
        signalStrengthValue = if (s.cellSignalStrengths.isNotEmpty())
            s.cellSignalStrengths[0].level else s.level

        tvSignal.text = "Signal: $signalStrengthValue"
    }

    private fun updateUI() {
        tvTime.text = "Time: " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        currentLocation?.let {
            val lat = "%.6f".format(it.latitude)
            val lon = "%.6f".format(it.longitude)
            tvLocation.text = "Lat: $lat, Lon: $lon"
        }

        updateBufferCount()
    }

    private fun updateBufferCount() {
        try {
            val file = File(filesDir, BUFFER_FILE)
            val count = if (file.exists()) {
                val content = file.readText()
                if (content.isNotEmpty()) {
                    JSONArray(content).length()
                } else {
                    0
                }
            } else {
                0
            }

            runOnUiThread {
                tvBufferCount.text = "Буфер: $count"
            }
        } catch (e: Exception) {
            Log.e("SystemInfo", "Ошибка чтения буфера: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isServiceRunning) {
            ws.close()
        }
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}