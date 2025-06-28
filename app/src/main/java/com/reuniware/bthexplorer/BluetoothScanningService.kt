package com.reuniware.bthexplorer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification // Import manquant potentiel
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent // Import pour PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo // Pour FOREGROUND_SERVICE_TYPE_LOCATION
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.pow

class BluetoothScanningService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        private val _discoveredDevicesMap = MutableStateFlow<Map<String, DeviceInfo>>(emptyMap())
        val discoveredDevicesList: StateFlow<List<DeviceInfo>> =
            _discoveredDevicesMap.map { map ->
                map.values.sortedWith(compareByDescending<DeviceInfo> { it.rssi }.thenByDescending { it.timestamp })
            }.stateIn(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

        fun clearDeviceList() {
            _discoveredDevicesMap.update { emptyMap() }
        }

        private const val LOCATION_UPDATE_INTERVAL_MS = 10000L
        private const val FASTEST_LOCATION_UPDATE_INTERVAL_MS = 5000L
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private lateinit var dbHelper: DeviceDatabaseHelper

    private val CHANNEL_ID = "BluetoothScanChannel"
    private val NOTIFICATION_ID = 1234
    private val loggedDevicesInThisScanSession = mutableSetOf<String>()

    private val TX_POWER_AT_1_METER = -59
    private val ENVIRONMENTAL_FACTOR_N = 2.0

    private val DATABASE_NAME = "BluetoothDevices.db"
    private val DATABASE_VERSION = 2
    private val TABLE_NAME = "discovered_devices"
    private val COLUMN_ID = "id"
    private val COLUMN_ADDRESS = "address"
    private val COLUMN_NAME = "name"
    private val COLUMN_RSSI = "rssi"
    private val COLUMN_DISTANCE = "distance"
    private val COLUMN_TIMESTAMP = "timestamp"
    private val COLUMN_SCAN_SESSION = "scan_session"
    private val COLUMN_LATITUDE = "latitude"
    private val COLUMN_LONGITUDE = "longitude"

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: Location? = null
    private lateinit var locationCallback: LocationCallback
    private var requestingLocationUpdates = false


    private inner class DeviceDatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            val createTable = """
                CREATE TABLE $TABLE_NAME (
                    $COLUMN_ID TEXT PRIMARY KEY,
                    $COLUMN_ADDRESS TEXT NOT NULL,
                    $COLUMN_NAME TEXT,
                    $COLUMN_RSSI INTEGER NOT NULL,
                    $COLUMN_DISTANCE REAL,
                    $COLUMN_TIMESTAMP INTEGER NOT NULL,
                    $COLUMN_SCAN_SESSION TEXT NOT NULL,
                    $COLUMN_LATITUDE REAL,
                    $COLUMN_LONGITUDE REAL
                )
            """.trimIndent()
            db.execSQL(createTable)
            Log.i("DeviceDatabaseHelper", "Database table $TABLE_NAME created.")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            Log.i("DeviceDatabaseHelper", "Upgrading database from version $oldVersion to $newVersion")
            if (oldVersion < 2) {
                try {
                    db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COLUMN_LATITUDE REAL;")
                    db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COLUMN_LONGITUDE REAL;")
                    Log.i("DeviceDatabaseHelper", "Columns $COLUMN_LATITUDE and $COLUMN_LONGITUDE added to $TABLE_NAME.")
                } catch (e: Exception) {
                    Log.e("DeviceDatabaseHelper", "Error upgrading table $TABLE_NAME: ${e.message}")
                    db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
                    onCreate(db)
                }
            }
        }
    }

    private fun calculateDistance(rssi: Int, txPower: Int = TX_POWER_AT_1_METER): Double {
        if (rssi == 0) return -1.0
        val ratio = (txPower.toDouble() - rssi) / (10 * ENVIRONMENTAL_FACTOR_N)
        return 10.0.pow(ratio).coerceIn(0.01, 200.0)
    }

    private fun addOrUpdateDeviceToStaticList(deviceInfo: DeviceInfo) {
        serviceScope.launch {
            _discoveredDevicesMap.update { currentDevices ->
                val mutableMap = currentDevices.toMutableMap()
                mutableMap[deviceInfo.address] = deviceInfo
                mutableMap
            }
        }
    }

    private fun logDeviceToDatabase(deviceInfo: DeviceInfo, scanSessionId: String) {
        serviceScope.launch {
            try {
                val db = dbHelper.writableDatabase
                val values = ContentValues().apply {
                    put(COLUMN_ID, UUID.randomUUID().toString())
                    put(COLUMN_ADDRESS, deviceInfo.address)
                    put(COLUMN_NAME, deviceInfo.name)
                    put(COLUMN_RSSI, deviceInfo.rssi)
                    put(COLUMN_DISTANCE, deviceInfo.estimatedDistance)
                    put(COLUMN_TIMESTAMP, deviceInfo.timestamp)
                    put(COLUMN_SCAN_SESSION, scanSessionId)
                    put(COLUMN_LATITUDE, deviceInfo.latitude)
                    put(COLUMN_LONGITUDE, deviceInfo.longitude)
                }

                val id = db.insertWithOnConflict(
                    TABLE_NAME,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                if (id == -1L) {
                    Log.w("BluetoothScanService", "Failed to insert device ${deviceInfo.address} into database.")
                } else {
                    Log.d("BluetoothScanService", "Device ${deviceInfo.address} logged to database with id $id.")
                }
            } catch (e: Exception) {
                Log.e("BluetoothScanService", "Error logging device to database: ${e.message}", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val leScanCallback = object : ScanCallback() {
        private val currentScanSessionId by lazy {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        }

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let { scanResult ->
                val device = scanResult.device ?: return@let
                val deviceAddress = device.address ?: return@let

                if (loggedDevicesInThisScanSession.add(deviceAddress)) {
                    val rssi = scanResult.rssi
                    var deviceName: String? = null

                    if (ActivityCompat.checkSelfPermission(this@BluetoothScanningService, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        try {
                            deviceName = device.name
                        } catch (e: SecurityException) {
                            Log.w("BluetoothScanService", "SecurityException for device.name for ${deviceAddress}: ${e.message}")
                        }
                    } else {
                        Log.w("BluetoothScanService", "BLUETOOTH_CONNECT permission missing for device.name on ${deviceAddress}")
                    }
                    if (deviceName.isNullOrBlank()) deviceName = "Unknown Device"

                    val estimatedDistance = calculateDistance(rssi)
                    val validDistance = if (estimatedDistance > 0) estimatedDistance else null
                    val lat = currentLocation?.latitude
                    val lon = currentLocation?.longitude

                    val deviceInfo = DeviceInfo(
                        address = deviceAddress,
                        name = deviceName,
                        rssi = rssi,
                        estimatedDistance = validDistance,
                        latitude = lat,
                        longitude = lon
                    )

                    addOrUpdateDeviceToStaticList(deviceInfo)
                    logDeviceToDatabase(deviceInfo, currentScanSessionId)

                    // Optional verbose logging:
                    /*
                    val logDetails = """
                        New Device:
                        Name: ${deviceInfo.name}
                        Address: ${deviceInfo.address}
                        RSSI: ${deviceInfo.rssi} dBm
                        Distance: ${deviceInfo.estimatedDistance?.let { "%.2f m".format(it) } ?: "N/A"}
                        Location: ${if (lat != null && lon != null) "Lat: %.5f, Lon: %.5f".format(lat, lon) else "N/A"}
                        Timestamp: ${deviceInfo.timestamp}
                    """.trimIndent()
                    Log.i("BluetoothScanService", logDetails)
                    */
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            // Not typically used if reportDelay is 0
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            val errorText = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
                SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
                5 -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
                6 -> "SCANNING_TOO_FREQUENTLY (API 30+)"
                else -> "UNKNOWN_ERROR ($errorCode)"
            }
            Log.e("BluetoothScanService", "Scan failed: $errorText")
            scanning = false
            // Consider stopping the service or attempting a restart after a delay
            // stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BluetoothScanService", "Service onCreate")
        // CRITICAL: Create notification channel before starting foreground service (Oreo+)
        createNotificationChannel() // Moved here

        dbHelper = DeviceDatabaseHelper(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            ?: run {
                Log.e("BluetoothScanService", "BluetoothManager not available. Stopping service.")
                stopSelf()
                return
            }

        bluetoothAdapter = bluetoothManager.adapter
            ?: run {
                Log.e("BluetoothScanService", "BluetoothAdapter not available. Stopping service.")
                stopSelf()
                return
            }

        if (!bluetoothAdapter.isEnabled) {
            Log.w("BluetoothScanService", "Bluetooth is disabled. Service will attempt to run but scan might fail.")
        }

        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            ?: run {
                Log.e("BluetoothScanService", "BluetoothLeScanner not available (BLE not supported?). Stopping service.")
                stopSelf()
                return
            }
        Log.i("BluetoothScanService", "Service resources initialized.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Bluetooth Scanning Activity"
            val channelDescription = "Notifications for ongoing Bluetooth device scanning"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
                description = channelDescription
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.i("BluetoothScanService", "Notification channel $CHANNEL_ID created.")
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                currentLocation = locationResult.lastLocation
                Log.d("BluetoothScanService", "Location Updated: Lat: ${currentLocation?.latitude}, Lon: ${currentLocation?.longitude}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermissions()) {
            Log.w("BluetoothScanService", "Missing location permissions to start updates.")
            return
        }
        if (requestingLocationUpdates) {
            Log.d("BluetoothScanService", "Location updates already requested.")
            return
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_LOCATION_UPDATE_INTERVAL_MS)
            .build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            requestingLocationUpdates = true
            Log.i("BluetoothScanService", "Requested location updates.")
        } catch (e: SecurityException) {
            Log.e("BluetoothScanService", "SecurityException while requesting location updates: ${e.message}", e)
            requestingLocationUpdates = false
        }
    }

    private fun stopLocationUpdates() {
        if (!requestingLocationUpdates) {
            Log.d("BluetoothScanService", "Location updates were not requested.")
            return
        }
        fusedLocationClient.removeLocationUpdates(locationCallback)
            .addOnCompleteListener { task ->
                requestingLocationUpdates = false
                if (task.isSuccessful) {
                    Log.i("BluetoothScanService", "Stopped location updates.")
                } else {
                    Log.w("BluetoothScanService", "Failed to stop location updates: ${task.exception?.message}")
                }
            }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("BluetoothScanService", "Service onStartCommand, Action: ${intent?.action}")

        // --- CRITICAL SECTION: START FOREGROUND SERVICE ---
        val notificationIntent = Intent(this, MainActivity::class.java) // Or your desired activity
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Explorateur Bluetooth Actif")
            .setContentText("Scan des appareils Bluetooth en cours...")
            // IMPORTANT: Replace R.mipmap.ic_launcher with a valid small icon from your drawables
            // For example: .setSmallIcon(R.drawable.ic_stat_bluetooth_scanning)
            .setSmallIcon(R.mipmap.ic_launcher) // TODO: REPLACE THIS ICON
            .setContentIntent(pendingIntent) // So user can tap notification to open app
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // If your service uses location, specify the type. Otherwise, you might not need this.
                // If you don't use location in the background, you can remove foregroundServiceType
                // or use a more appropriate type if available for Bluetooth.
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i("BluetoothScanService", "Service started in foreground.")
        } catch (e: Exception) {
            Log.e("BluetoothScanService", "Error starting foreground service: ${e.message}", e)
            // This is a critical failure, stop the service.
            stopSelf()
            return START_NOT_STICKY // Don't restart if foreground setup fails.
        }
        // --- END CRITICAL SECTION ---


        if (!hasRequiredBluetoothPermissions()) {
            Log.e("BluetoothScanService", "Missing required Bluetooth permissions. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.w("BluetoothScanService", "Bluetooth is disabled. Cannot start scan.")
            stopSelf()
            return START_NOT_STICKY
        }

        bluetoothLeScanner ?: run {
            Log.e("BluetoothScanService", "BluetoothLeScanner not available. BLE might not be supported. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (hasLocationPermissions()) {
            startLocationUpdates()
        } else {
            Log.w("BluetoothScanService", "Location permissions not granted. GPS data will not be available.")
        }

        startBleScan()
        return START_STICKY
    }

    private fun hasRequiredBluetoothPermissions(): Boolean {
        val permissionsToCheck = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToCheck.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToCheck.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Before S, BLUETOOTH and BLUETOOTH_ADMIN were needed.
            // ACCESS_FINE_LOCATION is also critical for scanning before S.
            permissionsToCheck.add(Manifest.permission.BLUETOOTH)
            permissionsToCheck.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissionsToCheck.add(Manifest.permission.ACCESS_FINE_LOCATION) // Crucial for scanning pre-S
        }
        return permissionsToCheck.all { perm ->
            ActivityCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val fineLocationGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseLocationGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundLocationGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            return (fineLocationGranted || coarseLocationGranted) && backgroundLocationGranted
        }
        return fineLocationGranted || coarseLocationGranted
    }


    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (!hasRequiredBluetoothPermissions()) {
            Log.e("BluetoothScanService", "Attempted to start scan without required Bluetooth permissions.")
            stopSelf() // Stop if permissions are somehow lost after service start
            return
        }
        bluetoothLeScanner ?: run {
            Log.e("BluetoothScanService", "BluetoothLeScanner became null before starting scan.")
            stopSelf()
            return
        }

        if (!scanning) {
            loggedDevicesInThisScanSession.clear()
            val scanFilters = mutableListOf<ScanFilter>()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                        setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setLegacy(false)
                    }
                }
                .build()

            try {
                bluetoothLeScanner?.startScan(scanFilters, settings, leScanCallback)
                scanning = true
                Log.i("BluetoothScanService", "BLE scan started successfully.")
            } catch (e: SecurityException) {
                Log.e("BluetoothScanService", "SecurityException starting BLE scan: ${e.message}", e)
                scanning = false; stopSelf()
            } catch (e: IllegalStateException) {
                Log.e("BluetoothScanService", "IllegalStateException starting BLE scan (Bluetooth off?): ${e.message}", e)
                scanning = false; stopSelf()
            } catch (e: Exception) {
                Log.e("BluetoothScanService", "Generic error starting BLE scan: ${e.message}", e)
                scanning = false; stopSelf()
            }
        } else {
            Log.i("BluetoothScanService", "BLE scan already running.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (scanning && bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner?.stopScan(leScanCallback)
                scanning = false
                Log.i("BluetoothScanService", "BLE scan stopped.")
            } catch (e: SecurityException) {
                Log.e("BluetoothScanService", "SecurityException stopping BLE scan: ${e.message}", e)
            } catch (e: IllegalStateException) { // Can happen if BT is turned off
                Log.e("BluetoothScanService", "IllegalStateException stopping BLE scan: ${e.message}", e)
            } catch (e: Exception) {
                Log.e("BluetoothScanService", "Generic error stopping BLE scan: ${e.message}", e)
            }
        } else {
            Log.d("BluetoothScanService", "Attempted to stop scan but it was not running or scanner null.")
        }
    }

    override fun onDestroy() {
        Log.i("BluetoothScanService", "Service onDestroy starting.")
        stopBleScan()
        stopLocationUpdates()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        serviceJob.cancel()
        if (::dbHelper.isInitialized) {
            dbHelper.close()
        }
        Log.i("BluetoothScanService", "Service destroyed and resources released.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}