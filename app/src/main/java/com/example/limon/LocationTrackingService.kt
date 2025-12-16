package com.example.limon

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class LocationTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var ws: WebSocketClient

    private var signalStrengthValue: Int = 0
    private var lastRecordedLocation: Location? = null
    private val MIN_DISTANCE_METERS = 0.1
    private val BUFFER_FILE = "data_buffer_service.json"
    private var isOnlineMode = true // Будет управляться из активити

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 5000
    ).setMinUpdateIntervalMillis(2000).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val newLocation = result.lastLocation
            if (shouldRecordLocation(newLocation)) {
                lastRecordedLocation = newLocation
                createAndSendJson(newLocation)
            }
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

    override fun onCreate() {
        super.onCreate()
        Log.d("LocationService", "Сервис создан")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        ws = WebSocketClient("ws://192.168.0.11:8000/ws", object : ConnectionListener {
            override fun onConnected() {
                Log.d("LocationService", "WebSocket подключен")
                flushBuffer()
            }

            override fun onDisconnected() {
                Log.d("LocationService", "WebSocket отключен")
            }
        })
        ws.connect()

        startForegroundService()
        startTracking()
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "location_tracking_channel"
            val channelName = "Отслеживание местоположения"
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            val notificationIntent = Intent(this, SystemInfoActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Отслеживание местоположения")
                .setContentText("Сбор данных в фоновом режиме")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .build()

            startForeground(1, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (hasLocationPermission()) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager.registerTelephonyCallback(
                    mainExecutor,
                    newSignalListener!!
                )
            } else {
                telephonyManager.listen(
                    legacySignalListener,
                    PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                )
            }

            Log.d("LocationService", "Отслеживание запущено")
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateSignal(s: SignalStrength) {
        signalStrengthValue = if (s.cellSignalStrengths.isNotEmpty())
            s.cellSignalStrengths[0].level else s.level
    }

    private fun shouldRecordLocation(newLocation: Location?): Boolean {
        if (newLocation == null) return false

        lastRecordedLocation?.let { lastLocation ->
            val distance = lastLocation.distanceTo(newLocation)
            return distance >= MIN_DISTANCE_METERS
        }
        return true
    }

    private fun createAndSendJson(location: Location?) {
        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("signal", signalStrengthValue)
            location?.let {
                put("latitude", it.latitude)
                put("longitude", it.longitude)
                put("accuracy", it.accuracy)
                put("speed", it.speed)
            }
            put("device", Build.MODEL)
            put("android_version", Build.VERSION.SDK_INT)
            put("source", "service")
        }.toString()

        if (ws.isConnected() && isOnlineMode) {
            if (ws.send(json)) {
                Log.d("LocationService", "Данные отправлены")
            }
        } else {
            saveToBuffer(json)
            Log.d("LocationService", "Данные сохранены в буфер")
        }
    }

    private fun saveToBuffer(json: String) {
        try {
            val file = File(filesDir, BUFFER_FILE)
            val buffer: JSONArray

            if (file.exists()) {
                val content = file.readText()
                buffer = if (content.isNotEmpty()) JSONArray(content) else JSONArray()
            } else {
                buffer = JSONArray()
            }

            buffer.put(JSONObject(json))

            FileOutputStream(file).use { fos ->
                fos.write(buffer.toString().toByteArray())
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Ошибка сохранения: ${e.message}")
        }
    }

    private fun flushBuffer() {
        try {
            val file = File(filesDir, BUFFER_FILE)
            if (!file.exists()) return

            val content = file.readText()
            if (content.isEmpty()) return

            val buffer = JSONArray(content)

            val newBuffer = JSONArray()
            var sentCount = 0

            for (i in 0 until buffer.length()) {
                val json = buffer.getJSONObject(i).toString()
                if (ws.isConnected() && ws.send(json)) {
                    sentCount++
                } else {
                    newBuffer.put(buffer.getJSONObject(i))
                }
            }

            FileOutputStream(file).use { fos ->
                fos.write(newBuffer.toString().toByteArray())
            }

            Log.d("LocationService", "Отправлено $sentCount записей из буфера")
        } catch (e: Exception) {
            Log.e("LocationService", "Ошибка отправки буфера: ${e.message}")
        }
    }

    fun setOnlineMode(online: Boolean) {
        isOnlineMode = online
        Log.d("LocationService", "Режим изменен: ${if (online) "онлайн" else "офлайн"}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                "START_TRACKING" -> {
                    val online = it.getBooleanExtra("online_mode", true)
                    setOnlineMode(online)
                }
                "STOP_TRACKING" -> {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        ws.close()
        Log.d("LocationService", "Сервис остановлен")
    }
}