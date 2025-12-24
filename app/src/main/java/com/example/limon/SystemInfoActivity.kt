package com.example.limon

import android.Manifest
import android.annotation.SuppressLint
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
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

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var zmqClient: ZmqClient

    private var signalStrengthValue: Int = -120
    private var currentLocation: Location? = null
    private var lastRecordedLocation: Location? = null
    private val MIN_DISTANCE_METERS = 100.0
    private val APP_DIRECTORY_NAME = "LimonAppData"
    private val BUFFER_FILE = "data_buffer.json"
    private var isServiceRunning = false
    private var isDestroyed = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE
    )

    private val backgroundLocationPermission = arrayOf(
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    )

    private val storagePermission = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
        arrayOf<String>()
    }

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 5000
    ).setMinUpdateIntervalMillis(2000).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (isDestroyed) return

            val newLocation = result.lastLocation
            if (newLocation != null) {
                currentLocation = newLocation
                updateUI()

                val hasValidSignal = signalStrengthValue != -120
                val hasValidLocation = newLocation.hasAccuracy() && newLocation.accuracy < 100

                if (hasValidSignal && hasValidLocation && shouldRecordLocation(newLocation)) {
                    lastRecordedLocation = newLocation
                    sendJson()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private val legacySignalListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(s: SignalStrength) {
            if (isDestroyed) return

            val oldSignal = signalStrengthValue
            updateSignalWithTelephonyManager()

            val hasValidSignal = signalStrengthValue != -120
            val hasValidLocation = currentLocation != null && currentLocation!!.hasAccuracy() && currentLocation!!.accuracy < 100

            if (Math.abs(signalStrengthValue - oldSignal) > 2 && hasValidSignal && hasValidLocation) {
                sendJson()
            }
        }
    }

    @SuppressLint("NewApi")
    private val newSignalListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(s: SignalStrength) {
                if (isDestroyed) return

                val oldSignal = signalStrengthValue
                updateSignalWithTelephonyManager()

                val hasValidSignal = signalStrengthValue != -120
                val hasValidLocation = currentLocation != null && currentLocation!!.hasAccuracy() && currentLocation!!.accuracy < 100

                if (Math.abs(signalStrengthValue - oldSignal) > 2 && hasValidSignal && hasValidLocation) {
                    sendJson()
                }
            }
        }
    } else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_system_info)

        initViews()
        initZmq()
        checkPermissions()
    }

    private fun initZmq() {
        zmqClient = ZmqClient("tcp://192.168.139.222:5555", object : ConnectionListener {
            override fun onConnected() {
                tvStatus.text = "Сервер подключен"
            }

            override fun onDisconnected() {
                tvStatus.text = "Сервер отключен"
            }
        })
        zmqClient.connect()
    }

    private fun initViews() {
        tvTime = findViewById(R.id.tvTime)
        tvSignal = findViewById(R.id.tvSignal)
        tvLocation = findViewById(R.id.tvLocation)
        tvStatus = findViewById(R.id.tvStatus)

        startTimeUpdates()

        tvSignal.text = "Сигнал: -- dBm"
        tvLocation.text = "Локация: ожидание..."
        tvStatus.text = "Проверка разрешений..."
    }

    private fun startTimeUpdates() {
        scope.launch {
            while (isActive) {
                updateTime()
                delay(1000)
            }
        }
    }

    private fun updateTime() {
        tvTime.text = "Время: " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun checkPermissions() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startEverything()
            checkBackgroundLocationPermission()
            checkStoragePermission()
        } else {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 101)
        }
    }

    private fun checkBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, backgroundLocationPermission, 102)
            }
        }
    }

    private fun checkStoragePermission() {
        if (storagePermission.isNotEmpty()) {
            if (storagePermission.any {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }) {
                ActivityCompat.requestPermissions(this, storagePermission, 103)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            101 -> {
                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    startEverything()
                    checkBackgroundLocationPermission()
                    checkStoragePermission()
                } else {
                    val deniedPermissions = permissions.filterIndexed { index, _ ->
                        grantResults[index] != PackageManager.PERMISSION_GRANTED
                    }

                    runOnUiThread {
                        if (deniedPermissions.contains(Manifest.permission.ACCESS_FINE_LOCATION) ||
                            deniedPermissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION)
                        ) {
                            tvLocation.text = "Локация: нет разрешения"
                            tvStatus.text = "Нет разрешения на локацию"
                        }

                        if (deniedPermissions.contains(Manifest.permission.READ_PHONE_STATE)) {
                            tvSignal.text = "Сигнал: нет разрешения"
                        }

                        Toast.makeText(
                            this,
                            "Некоторые функции будут недоступны. Разрешения можно изменить в настройках приложения.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    startWithLimitedFunctionality()
                }
            }

            102 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Фоновая локация недоступна. Приложение будет работать только когда активно.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            103 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            "Сохранение в Documents недоступно. Данные будут сохраняться во внутреннем хранилище.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun startWithLimitedFunctionality() {
        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                updateSignalWithTelephonyManager()
            } else {
                runOnUiThread {
                    tvSignal.text = "Сигнал: нет разрешения"
                }
            }

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                        location?.let {
                            currentLocation = it
                            updateUI()
                        }
                    }
                } catch (e: Exception) {
                }
            }

            runOnUiThread {
                tvStatus.text = "Работает с ограничениями"
            }

        } catch (e: Exception) {
            runOnUiThread {
                tvStatus.text = "Ошибка: ${e.message}"
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startEverything() {
        try {
            runOnUiThread {
                tvStatus.text = "Запуск..."
            }

            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            startLocationUpdates()

            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (newSignalListener != null) {
                    telephonyManager.registerTelephonyCallback(mainExecutor, newSignalListener)
                }
            } else {
                telephonyManager.listen(
                    legacySignalListener,
                    PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                )
            }

            updateSignalWithTelephonyManager()

            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                location?.let {
                    currentLocation = it
                    updateUI()
                }
            }

            runOnUiThread {
                tvStatus.text = "Сбор данных..."
            }

        } catch (e: Exception) {
            runOnUiThread {
                tvStatus.text = "Ошибка запуска: ${e.message}"
                Toast.makeText(this, "Ошибка запуска: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
            } else {
                runOnUiThread {
                    tvLocation.text = "Локация: нет разрешения"
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
        }
    }

    private fun startTrackingService(onlineMode: Boolean) {
        try {
            val intent = Intent(this, LocationTrackingService::class.java)
            intent.action = "START_TRACKING"
            intent.putExtra("online_mode", onlineMode)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            isServiceRunning = true
        } catch (e: Exception) {
        }
    }

    private fun stopTrackingService() {
        try {
            val intent = Intent(this, LocationTrackingService::class.java)
            intent.action = "STOP_TRACKING"
            stopService(intent)
            isServiceRunning = false
        } catch (e: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateSignalWithTelephonyManager() {
        try {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val allCellInfo = telephonyManager.allCellInfo
                    if (allCellInfo != null) {
                        for (cellInfo in allCellInfo) {
                            if (cellInfo is CellInfoLte) {
                                val signal = cellInfo.cellSignalStrength
                                signalStrengthValue = signal.dbm

                                runOnUiThread {
                                    tvSignal.text = "Сигнал: ${signal.dbm}dBm"
                                }
                                return
                            }
                        }
                    }
                }

                val signalStrength = telephonyManager.signalStrength
                if (signalStrength != null) {
                    val rsrp = getRsrpFromSignalStrength(signalStrength)
                    signalStrengthValue = rsrp

                    runOnUiThread {
                        tvSignal.text = "Сигнал: ${rsrp}dBm"
                    }
                } else {
                    runOnUiThread {
                        tvSignal.text = "Сигнал: недоступен"
                    }
                }
            } else {
                runOnUiThread {
                    tvSignal.text = "Сигнал: нет разрешения"
                }
            }

        } catch (e: Exception) {
            runOnUiThread {
                tvSignal.text = "Ошибка сигнала"
            }
        }
    }

    private fun getRsrpFromSignalStrength(s: SignalStrength): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (s.cellSignalStrengths.isNotEmpty()) {
                    s.cellSignalStrengths[0].dbm
                } else {
                    parseRsrpFromString(s.toString())
                }
            } else {
                parseRsrpFromString(s.toString())
            }
        } catch (e: Exception) {
            -120
        }
    }

    private fun parseRsrpFromString(signalStr: String): Int {
        val patterns = listOf(
            "rsrp=(-?\\d+)",
            "LteRSRP=(-?\\d+)",
            "lte rsrp=(-?\\d+)"
        )

        for (pattern in patterns) {
            val regex = pattern.toRegex(RegexOption.IGNORE_CASE)
            val match = regex.find(signalStr)
            if (match != null) {
                return match.groupValues[1].toInt()
            }
        }

        return -120
    }

    private fun updateUI() {
        runOnUiThread {
            currentLocation?.let {
                val lat = "%.6f".format(it.latitude)
                val lon = "%.6f".format(it.longitude)
                tvLocation.text = "Локация: $lat, $lon"
            } ?: run {
                tvLocation.text = "Локация: получение..."
            }
        }
    }

    private fun shouldRecordLocation(newLocation: Location?): Boolean {
        if (newLocation == null) return false
        if (!newLocation.hasAccuracy() || newLocation.accuracy > 100) return false

        lastRecordedLocation?.let { lastLocation ->
            val distance = lastLocation.distanceTo(newLocation)
            return distance >= MIN_DISTANCE_METERS
        }
        return true
    }

    private fun sendJson() {
        scope.launch(Dispatchers.IO) {
            try {
                val hasValidSignal = signalStrengthValue != -120
                val hasValidLocation = currentLocation != null && currentLocation!!.hasAccuracy() && currentLocation!!.accuracy < 100

                if (!hasValidSignal || !hasValidLocation) {
                    return@launch
                }

                val jsonObject = JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("signal", signalStrengthValue)
                    currentLocation?.let {
                        put("latitude", it.latitude)
                        put("longitude", it.longitude)
                        put("accuracy", it.accuracy)
                        if (it.hasSpeed()) put("speed", it.speed)
                    }
                    put("device", Build.MODEL)
                    put("android_version", Build.VERSION.SDK_INT)
                }

                val json = jsonObject.toString()

                saveToDocuments(json)

                if (zmqClient.isConnected()) {
                    val sent = zmqClient.send(json)
                    withContext(Dispatchers.Main) {
                        tvStatus.text = if (sent) "✅ Данные отправлены" else "⚠️ Ошибка"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "🔴 Сервер отключен"
                    }
                }

            } catch (e: Exception) {
            }
        }
    }

    private fun saveToDocuments(json: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val canWriteToStorage = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    ContextCompat.checkSelfPermission(
                        this@SystemInfoActivity,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                if (!canWriteToStorage) {
                    saveToInternalStorage(json)
                    return@launch
                }

                val documentsDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                } else {
                    File(Environment.getExternalStorageDirectory(), "Documents")
                }

                val appDir = File(documentsDir, APP_DIRECTORY_NAME)
                if (!appDir.exists()) {
                    if (!appDir.mkdirs()) {
                        saveToInternalStorage(json)
                        return@launch
                    }
                }

                val dataFile = File(appDir, BUFFER_FILE)

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
            val file = File(filesDir, "backup_$BUFFER_FILE")
            val buffer: JSONArray = if (file.exists()) {
                try {
                    val content = file.readText()
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
            file.writeText(buffer.toString())

        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isDestroyed = true

        scope.cancel()

        if (!isServiceRunning) {
            zmqClient.close()
        }

        try {
            stopLocationUpdates()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (newSignalListener != null) {
                    telephonyManager.unregisterTelephonyCallback(newSignalListener)
                }
            } else {
                telephonyManager.listen(
                    legacySignalListener,
                    PhoneStateListener.LISTEN_NONE
                )
            }
        } catch (e: Exception) {
        }
    }
}