package com.example.limon

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.*
import android.telephony.CellInfoLte
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LocationTrackingService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var zmqClient: ZmqClient

    private var signalStrengthValue: Int = -120
    private var currentLocation: Location? = null

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 10000
    ).setMinUpdateIntervalMillis(5000).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            currentLocation = result.lastLocation
        }
    }

    @Suppress("DEPRECATION")
    private val legacySignalListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(s: SignalStrength) {
            updateSignalWithTelephonyManager()
        }
    }

    private val newSignalListener =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(s: SignalStrength) {
                    updateSignalWithTelephonyManager()
                }
            }
        } else null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var scheduledTaskJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        zmqClient = ZmqClient("tcp://192.168.139.222:5555")
        zmqClient.connect()

        startForegroundService()

        if (hasLocationPermission()) {
            startTracking()
            startScheduledTask()
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "location_channel"
            val channelName = "Сбор данных"
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Сбор данных")
                .setContentText("Работает в фоне")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()

            startForeground(1, notification)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        try {
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

            updateSignalWithTelephonyManager()

        } catch (e: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateSignalWithTelephonyManager() {
        try {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val allCellInfo = telephonyManager.allCellInfo
                allCellInfo?.forEach { cellInfo ->
                    if (cellInfo is CellInfoLte) {
                        signalStrengthValue = cellInfo.cellSignalStrength.dbm
                        return
                    }
                }
            }

            val signalStrength = telephonyManager.signalStrength
            if (signalStrength != null) {
                signalStrengthValue = getRsrpFromSignalStrength(signalStrength)
            }

        } catch (e: Exception) {
        }
    }

    private fun getRsrpFromSignalStrength(s: SignalStrength): Int {
        return try {
            val signalStr = s.toString()
            listOf("rsrp=(-?\\d+)", "LteRSRP=(-?\\d+)", "lte rsrp=(-?\\d+)")
                .forEach { pattern ->
                    val regex = pattern.toRegex(RegexOption.IGNORE_CASE)
                    regex.find(signalStr)?.let {
                        return it.groupValues[1].toInt()
                    }
                }
            -120
        } catch (e: Exception) {
            -120
        }
    }

    private fun startScheduledTask() {
        scheduledTaskJob = serviceScope.launch {
            while (isActive) {
                try {
                    delay(60000)

                    val hasValidSignal = signalStrengthValue != -120
                    val hasValidLocation = currentLocation != null &&
                            currentLocation!!.hasAccuracy() &&
                            currentLocation!!.accuracy < 100

                    if (!hasValidSignal || !hasValidLocation) {
                        continue
                    }

                    val jsonObject = JSONObject().apply {
                        put("timestamp", System.currentTimeMillis())
                        put("signal", signalStrengthValue)
                        currentLocation?.let {
                            put("latitude", it.latitude)
                            put("longitude", it.longitude)
                            put("accuracy", it.accuracy)
                        }
                        put("device", Build.MODEL)
                        put("source", "LocationTrackingService")
                    }

                    val json = jsonObject.toString()

                    if (zmqClient.isConnected()) {
                        val sent = runBlocking {
                            zmqClient.send(json)
                        }

                        if (!sent) {
                            saveToDocuments(json)
                        }
                    } else {
                        saveToDocuments(json)
                    }

                } catch (e: Exception) {
                    delay(10000)
                }
            }
        }
    }

    private fun saveToDocuments(json: String) {
        runBlocking(Dispatchers.IO) {
            try {
                val APP_DIRECTORY_NAME = "LimonAppData"
                val SERVICE_FILE = "service_data.json"

                val documentsDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                } else {
                    File(Environment.getExternalStorageDirectory(), "Documents")
                }

                val appDir = File(documentsDir, APP_DIRECTORY_NAME)
                if (!appDir.exists()) {
                    if (!appDir.mkdirs()) {
                        saveToInternalStorage(json)
                        return@runBlocking
                    }
                }

                val dataFile = File(appDir, SERVICE_FILE)

                val buffer: JSONArray = if (dataFile.exists()) {
                    try {
                        val content = dataFile.readText()
                        if (content.isNotEmpty()) {
                            JSONArray(content)
                        } else {
                            JSONArray()
                        }
                    } catch (e: Exception) {
                        JSONArray()
                    }
                } else {
                    JSONArray()
                }

                buffer.put(JSONObject(json))
                dataFile.writeText(buffer.toString())

            } catch (e: Exception) {
                saveToInternalStorage(json)
            }
        }
    }

    private fun saveToInternalStorage(json: String) {
        try {
            val file = File(filesDir, "service_backup.json")
            val buffer = if (file.exists()) {
                JSONArray(file.readText())
            } else {
                JSONArray()
            }
            buffer.put(JSONObject(json))
            file.writeText(buffer.toString())
        } catch (e: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                "START_TRACKING" -> {
                    if (hasLocationPermission()) {
                        startTracking()
                        startScheduledTask()
                    }
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
        try {
            scheduledTaskJob?.cancel()
            serviceScope.cancel()

            fusedLocationClient.removeLocationUpdates(locationCallback)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (newSignalListener != null) {
                    telephonyManager.unregisterTelephonyCallback(newSignalListener!!)
                }
            } else {
                telephonyManager.listen(legacySignalListener, PhoneStateListener.LISTEN_NONE)
            }

            zmqClient.close()

        } catch (e: Exception) {
        }
    }
}