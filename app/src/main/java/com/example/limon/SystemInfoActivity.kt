package com.example.limon

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.telephony.TelephonyCallback
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@SuppressLint("MissingPermission", "BatteryLife")
class SystemInfoActivity : AppCompatActivity() {


    private lateinit var tvTime: TextView
    private lateinit var tvSignal: TextView
    private lateinit var tvLocation: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnRefresh: Button


    private lateinit var telephonyManager: TelephonyManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient


    private var signalStrengthValue = 0
    private var currentLocation: Location? = null
    private val executor = Executors.newSingleThreadExecutor()


    private val requiredPermissions = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_BASIC_PHONE_STATE
        } else {
            Manifest.permission.READ_PHONE_STATE
        }
    )


    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY,
        10000
    ).setMinUpdateIntervalMillis(5000).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            super.onLocationResult(locationResult)
            currentLocation = locationResult.lastLocation
            updateLocationText()
        }
    }


    @Suppress("DEPRECATION")
    private val phoneStateListener = object : PhoneStateListener() {
        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
            updateSignalStrength(signalStrength)
        }
    }

    private val signalStrengthCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                updateSignalStrength(signalStrength)
            }
        }
    } else {
        null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_system_info)

        initViews()
        setupButtons()
        checkPermissions()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    private fun initViews() {
        tvTime = findViewById(R.id.tvTime)
        tvSignal = findViewById(R.id.tvSignal)
        tvLocation = findViewById(R.id.tvLocation)
        tvStatus = findViewById(R.id.tvStatus)
        btnRefresh = findViewById(R.id.btnRefresh)
    }

    private fun setupButtons() {
        btnRefresh.setOnClickListener { updateAllData() }
    }

    private fun checkPermissions() {
        if (requiredPermissions.all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }) {
            setupTelephony()
            setupLocation()
            updateAllData()
        } else {
            ActivityCompat.requestPermissions(
                this,
                requiredPermissions,
                PERMISSION_REQUEST_CODE
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupTelephony() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                telephonyManager.registerTelephonyCallback(
                    mainExecutor,
                    signalStrengthCallback as TelephonyCallback
                )
            } catch (e: Exception) {
                Log.e("SystemInfoActivity", "TelephonyCallback error", e)
                setupLegacyTelephony()
            }
        } else {
            setupLegacyTelephony()
        }
    }

    @Suppress("DEPRECATION")
    private fun setupLegacyTelephony() {
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
    }

    @SuppressLint("MissingPermission")
    private fun setupLocation() {
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            currentLocation = location
            updateLocationText()
        }
    }

    private fun updateAllData() {
        updateTime()
        updateSignalText()
        updateLocationText()
    }

    private fun updateTime() {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        setTextWithResource(tvTime, R.string.current_time, time)
    }

    private fun updateSignalText() {
        setTextWithResource(tvSignal, R.string.signal_strength, signalStrengthValue.toString())
    }

    private fun updateLocationText() {
        currentLocation?.let {
            val lat = "%.6f".format(it.latitude)
            val lon = "%.6f".format(it.longitude)
            setTextWithResource(tvLocation, R.string.location_coords, lat, lon)
        } ?: run {
            tvLocation.setText(R.string.location_not_available)
        }
    }

    private fun updateSignalStrength(signalStrength: SignalStrength) {
        signalStrengthValue = when {
            signalStrength.cellSignalStrengths.isNotEmpty() ->
                signalStrength.cellSignalStrengths[0].level
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                signalStrength.level
            else -> signalStrength.gsmSignalStrength
        }
        runOnUiThread {
            updateSignalText()
        }
    }

    private fun saveDataToJson() {
        executor.execute {
            try {
                val jsonString = createJsonData()
                val fileName = "system_info_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.json"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveWithMediaStore(fileName, jsonString)
                } else {
                    saveLegacy(fileName, jsonString)
                }

                runOnUiThread {
                    setTextWithResource(tvStatus, R.string.status_auto_saved,
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.setText(R.string.status_error_saving)
                    Toast.makeText(this, R.string.error_saving_data, Toast.LENGTH_SHORT).show()
                }
                Log.e("SystemInfoActivity", "Error saving data", e)
            }
        }
    }

    private fun createJsonData(): String {
        return JSONObject().apply {
            put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            put("signal_strength", signalStrengthValue)
            currentLocation?.let {
                put("latitude", it.latitude)
                put("longitude", it.longitude)
                put("accuracy", it.accuracy)
            }
        }.toString()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(fileName: String, content: String) {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/SystemInfo")
        }

        resolver.insert(MediaStore.Files.getContentUri("external"), values)?.let { uri ->
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(content.toByteArray())
            }
        }
    }

    private fun saveLegacy(fileName: String, content: String) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "SystemInfo")
        if (!dir.exists()) dir.mkdirs()

        File(dir, fileName).outputStream().use {
            it.write(content.toByteArray())
        }
    }

    private fun setTextWithResource(textView: TextView, @StringRes resId: Int, vararg args: Any) {
        textView.text = getString(resId, *args)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            setupTelephony()
            setupLocation()
            updateAllData()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
    }
}