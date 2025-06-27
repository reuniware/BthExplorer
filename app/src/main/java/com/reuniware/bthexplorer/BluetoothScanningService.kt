package com.reuniware.bthexplorer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
                map.values.sortedByDescending { it.rssi }
            }.stateIn(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                started = SharingStarted.WhileSubscribed(2000),
                initialValue = emptyList()
            )

        fun clearDeviceList() {
            _discoveredDevicesMap.update { emptyMap() }
        }
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private lateinit var dbHelper: DeviceDatabaseHelper

    private val CHANNEL_ID = "BluetoothScanChannel"
    private val NOTIFICATION_ID = 123
    private val loggedDevicesInThisScanSession = mutableSetOf<String>()

    // Distance calculation constants
    private val TX_POWER_AT_1_METER = -59
    private val ENVIRONMENTAL_FACTOR_N = 2.0

    // Database constants
    private val DATABASE_NAME = "BluetoothDevices.db"
    private val DATABASE_VERSION = 1
    private val TABLE_NAME = "discovered_devices"
    private val COLUMN_ID = "id"
    private val COLUMN_ADDRESS = "address"
    private val COLUMN_NAME = "name"
    private val COLUMN_RSSI = "rssi"
    private val COLUMN_DISTANCE = "distance"
    private val COLUMN_TIMESTAMP = "timestamp"
    private val COLUMN_SCAN_SESSION = "scan_session"

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
                    $COLUMN_SCAN_SESSION TEXT NOT NULL
                )
            """.trimIndent()
            db.execSQL(createTable)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }

    private fun calculateDistance(rssi: Int, txPower: Int): Double {
        if (rssi == 0) return -1.0
        val ratio = (txPower - rssi) / (10 * ENVIRONMENTAL_FACTOR_N)
        return 10.0.pow(ratio).coerceIn(0.1, 100.0)
    }

    private fun addOrUpdateDeviceToStaticList(deviceInfo: DeviceInfo) {
        serviceScope.launch {
            _discoveredDevicesMap.update { currentDevices ->
                currentDevices.toMutableMap().apply {
                    this[deviceInfo.address] = deviceInfo
                }
            }
        }
    }

    private fun logDeviceToDatabase(deviceInfo: DeviceInfo, scanSessionId: String) {
        serviceScope.launch(Dispatchers.IO) {
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
                }

                db.insertWithOnConflict(
                    TABLE_NAME,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            } catch (e: Exception) {
                Log.e("BluetoothScanService", "Error logging device to database: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val leScanCallback = object : ScanCallback() {
        private val currentScanSessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let { scanResult ->
                val device = scanResult.device
                val deviceAddress = device.address

                if (loggedDevicesInThisScanSession.add(deviceAddress)) {
                    val rssi = scanResult.rssi
                    var deviceName = "N/A"

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ActivityCompat.checkSelfPermission(
                                this@BluetoothScanningService,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            try {
                                deviceName = device.name ?: "Unknown Device"
                            } catch (e: SecurityException) {
                                Log.e("BluetoothScanService", "SecurityException for device.name: ${e.message}")
                            }
                        }
                    } else {
                        try {
                            deviceName = device.name ?: "Unknown Device"
                        } catch (e: SecurityException) {
                            Log.e("BluetoothScanService", "SecurityException for device.name (pre-S): ${e.message}")
                        }
                    }

                    val estimatedDistance = calculateDistance(rssi, TX_POWER_AT_1_METER)
                    val validDistance = if (estimatedDistance > 0) estimatedDistance else null

                    val deviceInfo = DeviceInfo(
                        address = deviceAddress,
                        name = deviceName,
                        rssi = rssi,
                        estimatedDistance = validDistance
                    )

                    addOrUpdateDeviceToStaticList(deviceInfo)
                    logDeviceToDatabase(deviceInfo, currentScanSessionId)

                    val logDetails = """
                        New Device:
                        Name: $deviceName
                        Address: $deviceAddress
                        RSSI: $rssi dBm
                        Distance: ${validDistance?.let { "%.2f m".format(it) } ?: "N/A"}
                        Timestamp: ${deviceInfo.timestamp}
                    """.trimIndent()

                    Log.i("BluetoothScanService", logDetails)
                    FileLogger.log(applicationContext, "BluetoothScanService", logDetails)
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { scanResult ->
                val device = scanResult.device
                val deviceAddress = device.address
                val rssi = scanResult.rssi
                var deviceName: String? = "N/A"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(
                            this@BluetoothScanningService,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        try {
                            deviceName = device.name
                        } catch (e: SecurityException) {
                            deviceName = "No Perm"
                        }
                    }
                } else {
                    try {
                        deviceName = device.name
                    } catch (e: SecurityException) {
                        deviceName = "No Perm (Old)"
                    }
                }

                val estimatedDistance = calculateDistance(rssi, TX_POWER_AT_1_METER)
                val validDistance = if (estimatedDistance > 0) estimatedDistance else null
                val deviceInfo = DeviceInfo(deviceAddress, deviceName, rssi, validDistance)
                addOrUpdateDeviceToStaticList(deviceInfo)
                logDeviceToDatabase(deviceInfo, currentScanSessionId)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            val errorText = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
                SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
                5 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES" else "UNKNOWN_ERROR"
                6 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "SCANNING_TOO_FREQUENTLY" else "UNKNOWN_ERROR"
                else -> "UNKNOWN_ERROR"
            }
            Log.e("BluetoothScanService", "Scan failed: $errorCode ($errorText)")
            scanning = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BluetoothScanService", "Service onCreate")
        dbHelper = DeviceDatabaseHelper(this)
        createNotificationChannel()

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            ?: run {
                Log.e("BluetoothScanService", "BluetoothManager not available")
                stopSelf()
                return
            }

        bluetoothAdapter = bluetoothManager.adapter

        if (!bluetoothAdapter.isEnabled) {
            Log.w("BluetoothScanService", "Bluetooth is disabled")
            stopSelf()
            return
        }

        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            ?: run {
                Log.e("BluetoothScanService", "BluetoothLeScanner not available")
                stopSelf()
                return
            }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth Scanning Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for Bluetooth scanning service notifications"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("BluetoothScanService", "Service onStartCommand")

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bluetooth Scan Active")
            .setContentText("Scanning for nearby Bluetooth devices...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("BluetoothScanService", "Error starting foreground service: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasRequiredPermissions()) {
            Log.e("BluetoothScanService", "Missing required permissions")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!bluetoothAdapter.isEnabled || bluetoothLeScanner == null) {
            Log.w("BluetoothScanService", "Bluetooth not ready for scanning")
            stopSelf()
            return START_NOT_STICKY
        }

        startBleScan()
        return START_STICKY
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    private fun startBleScan() {
        if (!hasRequiredPermissions()) {
            Log.e("BluetoothScanService", "Missing required permissions for scanning")
            stopSelf()
            return
        }

        bluetoothLeScanner ?: run {
            Log.e("BluetoothScanService", "BluetoothLeScanner not initialized")
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
                Log.i("BluetoothScanService", "BLE scan started")
            } catch (e: Exception) {
                Log.e("BluetoothScanService", "Error starting scan: ${e.message}")
                stopSelf()
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun stopBleScan() {
        if (scanning && bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner?.stopScan(leScanCallback)
                scanning = false
                Log.i("BluetoothScanService", "BLE scan stopped")
            } catch (e: Exception) {
                Log.e("BluetoothScanService", "Error stopping scan: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceJob.cancel()
        dbHelper.close()
        Log.i("BluetoothScanService", "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}