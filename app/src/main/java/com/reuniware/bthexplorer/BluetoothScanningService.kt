package com.reuniware.bthexplorer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat // MODIFIÉ: Utiliser ContextCompat.checkSelfPermission
import androidx.core.app.NotificationCompat // Pas ActivityCompat ici pour les permissions
//import androidx.privacysandbox.tools.core.generator.build
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
    private val loggedDevicesInThisScanSession = mutableSetOf<String>() // Pour éviter les logs DB multiples par session

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
                    // En cas d'erreur, recréer la table peut entraîner une perte de données.
                    // Une migration plus robuste serait préférable pour une application en production.
                    db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
                    onCreate(db)
                }
            }
        }
    }

    private fun calculateDistance(rssi: Int, txPower: Int = TX_POWER_AT_1_METER): Double {
        if (rssi == 0) return -1.0 // RSSI de 0 est invalide pour le calcul
        val ratio = (txPower.toDouble() - rssi) / (10 * ENVIRONMENTAL_FACTOR_N)
        return 10.0.pow(ratio).coerceIn(0.01, 200.0) // Limiter la distance pour éviter les valeurs extrêmes
    }

    private fun addOrUpdateDeviceToStaticList(deviceInfo: DeviceInfo) {
        serviceScope.launch {
            _discoveredDevicesMap.update { currentDevices ->
                currentDevices.toMutableMap().apply { this[deviceInfo.address] = deviceInfo }
            }
        }
    }

    private fun logDeviceToDatabase(deviceInfo: DeviceInfo, scanSessionId: String) {
        serviceScope.launch {
            try {
                val db = dbHelper.writableDatabase
                val values = ContentValues().apply {
                    put(COLUMN_ID, UUID.randomUUID().toString()) // Clé primaire unique pour chaque enregistrement
                    put(COLUMN_ADDRESS, deviceInfo.address)
                    put(COLUMN_NAME, deviceInfo.name)
                    put(COLUMN_RSSI, deviceInfo.rssi)
                    put(COLUMN_DISTANCE, deviceInfo.estimatedDistance)
                    put(COLUMN_TIMESTAMP, deviceInfo.timestamp)
                    put(COLUMN_SCAN_SESSION, scanSessionId)
                    put(COLUMN_LATITUDE, deviceInfo.latitude)
                    put(COLUMN_LONGITUDE, deviceInfo.longitude)
                }

                // Utiliser insert standard. insertWithOnConflict avec CONFLICT_REPLACE sur une PK
                // signifie que si un UUID identique est généré (extrêmement improbable), il serait remplacé.
                // Ici, l'objectif est d'enregistrer chaque découverte unique par session.
                val id = db.insert(TABLE_NAME, null, values)
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

    @SuppressLint("MissingPermission") // Permissions vérifiées avant de démarrer le scan
    private val leScanCallback = object : ScanCallback() {
        private val currentScanSessionId by lazy { // Un ID unique pour cette instance de session de scan
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        }

        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            result?.let { scanResult ->
                val device = scanResult.device ?: return@let
                val deviceAddress = device.address ?: return@let // L'adresse MAC est essentielle

                // Récupérer le nom de l'appareil si la permission BLUETOOTH_CONNECT est accordée
                var deviceName: String? = null
                if (ContextCompat.checkSelfPermission(this@BluetoothScanningService, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        deviceName = device.name
                    } catch (e: SecurityException) {
                        Log.w("BluetoothScanService", "SecurityException for device.name for ${deviceAddress}: ${e.message}")
                    }
                } else {
                    // Si BLUETOOTH_CONNECT n'est pas accordé sur S+ (ou si on ne la demande pas), le nom peut être null
                    Log.w("BluetoothScanService", "BLUETOOTH_CONNECT permission missing for device.name on ${deviceAddress}. Name might be null or 'Unknown Device'.")
                }
                if (deviceName.isNullOrBlank()) deviceName = "Unknown Device"


                val rssi = scanResult.rssi
                val estimatedDistance = calculateDistance(rssi)
                val validDistance = if (estimatedDistance > 0) estimatedDistance else null
                val lat = currentLocation?.latitude // Peut être null si la permission de localisation est absente ou la localisation désactivée
                val lon = currentLocation?.longitude

                val deviceInfo = DeviceInfo(
                    address = deviceAddress,
                    name = deviceName,
                    rssi = rssi,
                    estimatedDistance = validDistance,
                    timestamp = System.currentTimeMillis(), // Toujours le timestamp actuel de la découverte
                    latitude = lat,
                    longitude = lon
                )

                addOrUpdateDeviceToStaticList(deviceInfo) // Mettre à jour la liste pour l'UI

                // Loguer dans la base de données une seule fois par appareil unique par session de scan
                // L'identifiant unique pour le set est `deviceAddress + "_" + currentScanSessionId`
                // pour permettre de retrouver le même appareil dans des sessions différentes
                // tout en évitant les duplications dans la même session.
                if (loggedDevicesInThisScanSession.add("${deviceAddress}_${currentScanSessionId}")) {
                    logDeviceToDatabase(deviceInfo, currentScanSessionId)
                }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) } // Traiter chaque résultat du lot
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            val errorText = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
                SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
                ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
                ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "SCANNING_TOO_FREQUENTLY (API 30+)" // API 30, mais constante ajoutée en API 31
                else -> "UNKNOWN_ERROR ($errorCode)"
            }
            Log.e("BluetoothScanService", "Scan failed: $errorText")
            scanning = false
            // Envisager d'arrêter le service ou de tenter un redémarrage différé si l'erreur est critique
            // stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BluetoothScanService", "Service onCreate")
        createNotificationChannel()

        dbHelper = DeviceDatabaseHelper(this)
        // Note: La fonction loadPreviouslyDiscoveredDevices() a été retirée pour simplifier
        // la demande actuelle, mais pourrait être réintégrée si nécessaire.

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
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
            Log.w("BluetoothScanService", "Bluetooth is disabled. Service will run but scan might fail or not start.")
        }

        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
                // Ce check est important car bluetoothLeScanner peut être null si le BLE n'est pas supporté
            ?: run {
                Log.e("BluetoothScanService", "BluetoothLeScanner not available (BLE not supported on this device?). Stopping service.")
                stopSelf()
                return
            }
        Log.i("BluetoothScanService", "Service resources initialized.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Bluetooth Scanning Activity"
            val channelDescription = "Notifications for ongoing Bluetooth device scanning"
            val importance = NotificationManager.IMPORTANCE_LOW // Pour une notification moins intrusive
            val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
                description = channelDescription
                // Options supplémentaires: setSound(null, null) pour désactiver le son par défaut
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.i("BluetoothScanService", "Notification channel $CHANNEL_ID created.")
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // locationResult.lastLocation peut être null dans de rares cas.
                locationResult.lastLocation?.let {
                    currentLocation = it
                    Log.d("BluetoothScanService", "Location Updated: Lat: ${it.latitude}, Lon: ${it.longitude}")
                }
            }
        }
    }

    @SuppressLint("MissingPermission") // Permissions vérifiées par hasLocationPermissions()
    private fun startLocationUpdates() {
        if (!hasLocationPermissions()) {
            Log.w("BluetoothScanService", "Missing location permissions to start updates. GPS data will be unavailable.")
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
            if (!::fusedLocationClient.isInitialized) { // Vérification de sécurité
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            }
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            requestingLocationUpdates = true
            Log.i("BluetoothScanService", "Requested location updates.")
        } catch (e: SecurityException) { // Peut arriver si les permissions sont révoquées pendant l'exécution
            Log.e("BluetoothScanService", "SecurityException while requesting location updates: ${e.message}", e)
            requestingLocationUpdates = false
        } catch (e: Exception) { // Autre exception
            Log.e("BluetoothScanService", "Exception while requesting location updates: ${e.message}", e)
            requestingLocationUpdates = false
        }
    }

    private fun stopLocationUpdates() {
        if (!requestingLocationUpdates) {
            // Log.d("BluetoothScanService", "Location updates were not running or already stopped.")
            return
        }
        if (!::fusedLocationClient.isInitialized) {
            Log.w("BluetoothScanService", "FusedLocationProviderClient not initialized, cannot stop updates.")
            return
        }
        fusedLocationClient.removeLocationUpdates(locationCallback)
            .addOnCompleteListener { task ->
                requestingLocationUpdates = false // Mettre à jour l'état que la tâche réussisse ou échoue
                if (task.isSuccessful) {
                    Log.i("BluetoothScanService", "Stopped location updates successfully.")
                } else {
                    Log.w("BluetoothScanService", "Failed to stop location updates: ${task.exception?.message}")
                }
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("BluetoothScanService", "Service onStartCommand, Action: ${intent?.action}")

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Explorateur Bluetooth Actif")
            .setContentText("Scan des appareils Bluetooth en cours...")
            //.setSmallIcon(R.drawable.ic_bluetooth_searching) // ASSUREZ-VOUS QUE CETTE ICÔNE EXISTE
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // La notification ne peut pas être balayée par l'utilisateur
            .build()

        // Déterminer le type de service de premier plan
        // Si les permissions de localisation sont là, on peut le spécifier.
        // Sinon, on ne spécifie pas de type particulier lié à la localisation.
        // Pour le scan Bluetooth pur (sans dérivation de localisation) sur API 31+,
        // FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE pourrait être plus approprié si votre cas d'usage correspond.
        // Pour l'instant, on se base sur la localisation si disponible pour le type.
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (hasLocationPermissions()) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            // Pour API 34+ : ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING (si approprié)
            // Ou ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE (API 31+) si le cas d'usage est la connexion à un appareil.
            // Si aucune permission spécifique n'est présente, 0 (ou pas de type) est ok.
        } else 0


        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && foregroundServiceType != 0) {
                startForeground(NOTIFICATION_ID, notification, foregroundServiceType)
                Log.i("BluetoothScanService", "Service started in foreground with type: $foregroundServiceType.")
            } else {
                startForeground(NOTIFICATION_ID, notification)
                Log.i("BluetoothScanService", "Service started in foreground (no specific type or pre-Q).")
            }
        } catch (e: Exception) {
            Log.e("BluetoothScanService", "Error starting foreground service: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasRequiredBluetoothPermissions()) {
            Log.e("BluetoothScanService", "Missing required Bluetooth permissions. Stopping service.")
            // Notifier l'utilisateur ou l'UI serait une bonne pratique ici.
            stopSelf()
            return START_NOT_STICKY
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.w("BluetoothScanService", "Bluetooth is disabled. Scan will not start.")
            // Il est souvent préférable de ne pas s'arrêter ici, l'utilisateur pourrait l'activer.
            // Le startBleScan() vérifiera à nouveau.
            // Si c'est un prérequis absolu pour que le service tourne, alors stopSelf().
        }

        bluetoothLeScanner ?: run {
            Log.e("BluetoothScanService", "BluetoothLeScanner not available. BLE might not be supported. Stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (hasLocationPermissions()) {
            startLocationUpdates()
        } else {
            Log.w("BluetoothScanService", "Location permissions not granted or location disabled. GPS data will not be available.")
            // S'assurer d'arrêter les updates si les permissions ont été révoquées entre-temps
            if (requestingLocationUpdates) stopLocationUpdates()
        }

        startBleScan()
        return START_STICKY // Le service sera redémarré s'il est tué par le système.
    }

    /**
     * Vérifie les permissions Bluetooth nécessaires pour le scan.
     * Pour Android S (API 31) et +, BLUETOOTH_SCAN est nécessaire.
     * BLUETOOTH_CONNECT est nécessaire pour obtenir le nom de l'appareil et d'autres interactions.
     * Pour les versions antérieures à S, BLUETOOTH, BLUETOOTH_ADMIN, et ACCESS_FINE_LOCATION sont nécessaires.
     */
    private fun hasRequiredBluetoothPermissions(): Boolean {
        val permissionsToCheck = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToCheck.add(Manifest.permission.BLUETOOTH_SCAN)
            // BLUETOOTH_CONNECT n'est pas strictement requis pour *juste scanner* les annonces,
            // mais il est requis pour obtenir le nom de l'appareil (`device.name`)
            // et pour toute connexion future. Il est donc bon de le vérifier ici.
            permissionsToCheck.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsToCheck.add(Manifest.permission.BLUETOOTH)
            permissionsToCheck.add(Manifest.permission.BLUETOOTH_ADMIN)
            // Pour les API < 31, ACCESS_FINE_LOCATION est requise pour que le scan BLE retourne des résultats.
            permissionsToCheck.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val allPermissionsGranted = permissionsToCheck.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }

        if (!allPermissionsGranted) {
            val missingPermissions = permissionsToCheck.filterNot { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
            Log.w("BluetoothScanService", "Missing Bluetooth permissions: $missingPermissions. Scan might not start or work correctly.")
        }
        return allPermissionsGranted
    }

    /**
     * Vérifie si les permissions de localisation (fine ou approximative) sont accordées.
     * Pour Android Q (API 29) et +, vérifie également ACCESS_BACKGROUND_LOCATION si
     * le service est destiné à fonctionner en arrière-plan de manière prolongée.
     * Note: Pour le scan BLE sur API < 31, la localisation est nécessaire.
     * Sur API >= 31, elle est facultative pour le scan mais nécessaire pour obtenir la localisation.
     */
    private fun hasLocationPermissions(): Boolean {
        val fineLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        // Au moins une permission de localisation de premier plan doit être accordée.
        val foregroundLocationOK = fineLocationGranted || coarseLocationGranted

        // Pour le fonctionnement en arrière-plan sur Q+, la permission de localisation en arrière-plan est également requise.
        // Cependant, la simple présence de la permission de premier plan suffit pour que FusedLocationProviderClient fonctionne
        // tant que l'application est au premier plan ou qu'un service de premier plan de type 'location' est actif.
        // Si vous avez besoin d'accéder à la localisation lorsque l'application est *complètement* en arrière-plan
        // sans service de premier plan de type location, alors ACCESS_BACKGROUND_LOCATION est indispensable.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val backgroundLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            // Pour cet usage (service de premier plan qui *peut* utiliser la localisation),
            // la permission de premier plan est la plus directement pertinente.
            // Si foregroundServiceType est 'location', alors backgroundLocation est implicitement géré.
            // Ici, on vérifie si on *peut* obtenir la localisation.
            (foregroundLocationOK).also { // On ne conditionne pas à backgroundLocation ici, car le service est en premier plan.
                if (!it) Log.w("BluetoothScanService", "Foreground location permission missing (Fine or Coarse).")
                if (it && !backgroundLocationGranted && foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) {
                    // Ce log est informatif : si le type est location, on s'attendrait à avoir aussi BG,
                    // mais le système peut quand même fournir la loc si l'app est visible ou service FG.
                    Log.i("BluetoothScanService", "Background location permission not granted, but foreground is. Location updates might be restricted in deep background without FG service of type location.")
                }
            }
        } else {
            foregroundLocationOK.also {
                if (!it) Log.w("BluetoothScanService", "Location permission missing (Fine or Coarse) for pre-Q.")
            }
        }
    }


    @SuppressLint("MissingPermission") // Permissions vérifiées par hasRequiredBluetoothPermissions()
    private fun startBleScan() {
        if (!bluetoothAdapter.isEnabled) {
            Log.w("BluetoothScanService", "Bluetooth is not enabled. Cannot start BLE scan.")
            scanning = false // Assurer la cohérence de l'état
            return
        }
        if (!hasRequiredBluetoothPermissions()) {
            Log.e("BluetoothScanService", "Attempted to start scan without required Bluetooth permissions. Scan aborted.")
            // Il est important de ne pas continuer si les permissions de base ne sont pas là.
            // stopSelf() pourrait être envisagé ici si le scan est la seule raison d'être du service.
            return
        }

        // Vérification redondante mais sûre pour bluetoothLeScanner
        bluetoothLeScanner ?: run {
            Log.e("BluetoothScanService", "BluetoothLeScanner is null when trying to start scan. Re-checking adapter.")
            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner // Tentative de réassignation
            if (bluetoothLeScanner == null) {
                Log.e("BluetoothScanService", "BluetoothLeScanner still null. BLE might not be supported or error. Stopping service.")
                stopSelf()
                return
            }
        }

        if (!scanning) {
            // loggedDevicesInThisScanSession.clear() // Effacé au début de chaque onScanResult via currentScanSessionId
            // ou plutôt, on ne l'efface pas ici, mais on s'assure que currentScanSessionId change si le scan est redémarré.
            // La logique actuelle avec 'by lazy' pour currentScanSessionId dans le ScanCallback le gère.

            val scanFilters = mutableListOf<ScanFilter>() // Pas de filtres spécifiques pour l'instant pour découvrir tous les appareils
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Privilégier la faible latence pour des résultats rapides
                .setReportDelay(0) // Rapporter les résultats immédiatement (pas de traitement par lot différé par le système)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE) // Plus de correspondances, potentiellement plus de puissance
                        setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT) // Rapporter autant de correspondances que possible
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setLegacy(false) // Préférer le scanner LE moderne si disponible (meilleure gestion de l'énergie et des filtres)
                    }
                }
                .build()

            try {
                bluetoothLeScanner?.startScan(scanFilters, settings, leScanCallback)
                scanning = true
                Log.i("BluetoothScanService", "BLE scan started successfully.")
            } catch (e: SecurityException) { // Rare si les permissions sont bien vérifiées avant
                Log.e("BluetoothScanService", "SecurityException starting BLE scan: ${e.message}", e)
                scanning = false; stopSelf() // Arrêter le service en cas d'erreur critique
            } catch (e: IllegalStateException) { // Peut arriver si le Bluetooth est désactivé juste avant l'appel
                Log.e("BluetoothScanService", "IllegalStateException starting BLE scan (Bluetooth off or other state issue?): ${e.message}", e)
                scanning = false; stopSelf()
            } catch (e: Exception) { // Capturer toute autre exception inattendue
                Log.e("BluetoothScanService", "Generic error starting BLE scan: ${e.message}", e)
                scanning = false; stopSelf()
            }
        } else {
            Log.i("BluetoothScanService", "BLE scan already running.")
        }
    }

    @SuppressLint("MissingPermission") // Permissions vérifiées au moment du startScan
    private fun stopBleScan() {
        // Vérifier si le scan est actif, si l'adaptateur est initialisé et activé, et si le scanner existe.
        if (scanning && ::bluetoothAdapter.isInitialized && bluetoothAdapter.isEnabled && bluetoothLeScanner != null) {
            try {
                bluetoothLeScanner?.stopScan(leScanCallback)
                scanning = false
                Log.i("BluetoothScanService", "BLE scan stopped successfully.")
            } catch (e: SecurityException) { // Rare mais possible
                Log.e("BluetoothScanService", "SecurityException stopping BLE scan: ${e.message}", e)
            } catch (e: IllegalStateException) { // Peut arriver si le Bluetooth est désactivé pendant le scan
                Log.e("BluetoothScanService", "IllegalStateException stopping BLE scan (BT off or other state issue?): ${e.message}", e)
            } catch (e: Exception) { // Capturer toute autre exception
                Log.e("BluetoothScanService", "Generic error stopping BLE scan: ${e.message}", e)
            }
        } else {
            // Log informatif si on essaie d'arrêter un scan non actif ou avec des prérequis manquants.
            Log.d("BluetoothScanService", "Attempted to stop scan, but conditions not met. Scanning: $scanning, BT Enabled: ${if(::bluetoothAdapter.isInitialized) bluetoothAdapter.isEnabled else "N/A"}, Scanner Null: ${bluetoothLeScanner == null}")
            scanning = false // Assurer la cohérence de l'état
        }
    }

    override fun onDestroy() {
        Log.i("BluetoothScanService", "Service onDestroy starting.")
        stopBleScan()
        stopLocationUpdates()

        // Gérer stopForeground de manière robuste
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE) // API 24+
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true) // Versions antérieures
        }

        serviceJob.cancel() // Annuler toutes les coroutines lancées dans serviceScope
        if (::dbHelper.isInitialized) {
            try {
                dbHelper.close()
            } catch (e: Exception) {
                Log.e("BluetoothScanService", "Error closing database: ${e.message}", e)
            }
        }

        Log.i("BluetoothScanService", "Service destroyed and resources released.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null // Ce service ne permet pas le binding.
}