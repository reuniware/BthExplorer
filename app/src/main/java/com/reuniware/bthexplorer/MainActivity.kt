package com.reuniware.bthexplorer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.reuniware.bthexplorer.ui.theme.BthExplorerTheme
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// --- Début des ajouts/modifications pour la connexion ---

// Modèle pour représenter un appareil scanné, incluant le raw BluetoothDevice
// Remplacez votre DeviceInfo par cela si vous voulez garder une seule structure,
// ou adaptez BluetoothScanningService pour qu'il fournisse des ScannedBluetoothDevice.
// Pour cet exemple, BluetoothUiScreen s'attendra à des ScannedBluetoothDevice.
data class ScannedBluetoothDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val timestamp: Long = System.currentTimeMillis(),
    @Transient var rawDevice: BluetoothDevice? = null // Important pour la connexion
)

// Statut de connexion
sealed class ConnectionStatus {
    data class Connecting(val deviceName: String) : ConnectionStatus()
    data class Connected(val deviceName: String, val servicesDiscovered: Boolean = false) : ConnectionStatus()
    object Disconnected : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

// ViewModel pour gérer l'état de la connexion Bluetooth
open class BluetoothConnectionViewModel(private val context: Context) : ViewModel() {
    var connectionState by mutableStateOf<ConnectionStatus>(ConnectionStatus.Disconnected)
    var connectedDeviceName by mutableStateOf<String?>(null)
    var discoveredServices = mutableStateListOf<BluetoothGattService>()
    private var bluetoothGatt: BluetoothGatt? = null

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    @SuppressLint("MissingPermission")
    open fun connectToDevice(deviceAddress: String) {
        try {
            if (!hasBluetoothConnectPermission(context)) {
                Log.e("BluetoothVM", "BLUETOOTH_CONNECT permission manquante.")
                connectionState = ConnectionStatus.Error("Permission manquante")
                return
            }

            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            if (device == null) {
                connectionState = ConnectionStatus.Error("Appareil non trouvé")
                return
            }

            connectionState = ConnectionStatus.Connecting(device.name ?: device.address)
            connectedDeviceName = device.name ?: device.address

            bluetoothGatt?.close()
            discoveredServices.clear()
            Log.d("BluetoothVM", "Connexion à ${device.address}")
            bluetoothGatt = device.connectGatt(context.applicationContext, false, gattCallback)
        } catch (e: Exception) {
            Log.e("BluetoothVM", "Erreur lors de la connexion: ${e.message}")
            connectionState = ConnectionStatus.Error("Erreur: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    open fun disconnect() {
        if (!hasBluetoothConnectPermission(context)) {
            Log.e("BluetoothVM", "BLUETOOTH_CONNECT permission manquante pour déconnecter.")
            return
        }
        bluetoothGatt?.disconnect()
        // La fermeture se fait dans onConnectionStateChange
    }

    @SuppressLint("MissingPermission")
    fun pairDevice(deviceAddress: String) {
        try {
            if (!hasBluetoothConnectPermission(context)) {
                Log.e("BluetoothVM", "Permission manquante pour l'appairage.")
                return
            }
            val device = bluetoothAdapter?.getRemoteDevice(deviceAddress)
            device?.let {
                Log.d("BluetoothVM", "Appairage avec ${it.address}")
                it.createBond()
            }
        } catch (e: Exception) {
            Log.e("BluetoothVM", "Erreur appairage: ${e.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceName = try { gatt.device.name ?: gatt.device.address } catch (e: SecurityException) { gatt.device.address }
            Log.d("GattCallback", "onConnectionStateChange: device=${gatt.device.address}, status=$status, newState=$newState")

            CoroutineScope(Dispatchers.Main).launch {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectionState = ConnectionStatus.Connected(deviceName)
                        connectedDeviceName = deviceName
                        Log.i("BluetoothGattCallback", "Connecté à $deviceName. Découverte des services...")
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connectionState = ConnectionStatus.Disconnected
                        connectedDeviceName = null
                        discoveredServices.clear()
                        gatt.close()
                        bluetoothGatt = null
                        Log.i("BluetoothGattCallback", "Déconnecté de $deviceName.")
                    }
                    BluetoothProfile.STATE_CONNECTING -> {
                        connectionState = ConnectionStatus.Connecting(deviceName)
                        Log.i("BluetoothGattCallback", "Connexion à $deviceName...")
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val deviceName = try { gatt.device.name ?: gatt.device.address } catch (e: SecurityException) { gatt.device.address }
            Log.d("GattCallback", "onServicesDiscovered: device=${gatt.device.address}, status=$status")
            CoroutineScope(Dispatchers.Main).launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i("BluetoothGattCallback", "Services découverts pour $deviceName")
                    if (connectionState is ConnectionStatus.Connected) {
                        connectionState = ConnectionStatus.Connected(deviceName, servicesDiscovered = true)
                    }
                    discoveredServices.clear()
                    discoveredServices.addAll(gatt.services)
                    
                    // Enregistrer les services dans Firebase
                    updateServicesInFirebase(gatt.device.address, gatt.services)

                    // Vous pouvez lister les services ici si nécessaire
                    gatt.services.forEach { service ->
                        Log.d("ServiceUUID", service.uuid.toString())
                    }
                } else {
                    Log.w("BluetoothGattCallback", "Échec de la découverte des services pour $deviceName, status: $status")
                    disconnect() // Déconnecter si la découverte échoue
                }
            }
        }
        // Implémentez onCharacteristicRead, etc. ici si nécessaire
    }

    private fun updateServicesInFirebase(deviceAddress: String, services: List<BluetoothGattService>) {
        val serviceUuids = services.map { it.uuid.toString() }
        val firestore = FirebaseFirestore.getInstance()
        firestore.collection("detected_devices")
            .document(deviceAddress)
            .update("services", serviceUuids)
            .addOnSuccessListener {
                Log.d("BluetoothVM", "Services mis à jour dans Firebase pour $deviceAddress")
            }
            .addOnFailureListener { e ->
                Log.e("BluetoothVM", "Erreur lors de la mise à jour des services dans Firebase", e)
            }
    }
}

// Fonction utilitaire pour vérifier la permission (peut être dans un fichier Utils)
fun hasBluetoothConnectPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        true // Non requis explicitement avant S, mais BLUETOOTH et BLUETOOTH_ADMIN le sont
    }
}
// --- Fin des ajouts/modifications pour la connexion ---


class MainActivity : ComponentActivity() {

    private lateinit var bluetoothPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var foregroundLocationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var backgroundLocationPermissionLauncher: ActivityResultLauncher<String>

    internal var allBluetoothPermissionsGranted by mutableStateOf(false)
    private var locationPermissionsGrantedForService by mutableStateOf(false)

    object NavRoutes {
        const val HOME_SCREEN = "home"
        const val BLUETOOTH_DEVICES_SCREEN = "bluetooth_devices"
        const val FIREBASE_DEVICES_SCREEN = "firebase_devices"
    }

    // Fournir le BluetoothConnectionViewModel à l'AppNavigator
    // Vous pouvez utiliser Hilt pour une injection de dépendances plus propre
    private val bluetoothConnectionViewModel: BluetoothConnectionViewModel by lazy {
        BluetoothConnectionViewModel(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initializePermissionLaunchers()
        allBluetoothPermissionsGranted = checkInitialBluetoothPermissions()
        locationPermissionsGrantedForService = areForegroundLocationPermissionsGranted() && isBackgroundLocationPermissionActuallyGranted()

        setContent {
            BthExplorerTheme {
                AppNavigator(
                    initialBluetoothPermissionsGranted = allBluetoothPermissionsGranted,
                    initialNotificationPermissionsGranted = areNotificationPermissionsGranted(),
                    connectionViewModel = bluetoothConnectionViewModel // Passer le ViewModel
                )
            }
        }
    }

    private fun checkInitialBluetoothPermissions(): Boolean {
        val requiredPermissions = getRequiredBluetoothPermissions()
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun initializePermissionLaunchers() {
        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    Toast.makeText(this, "Permission de notification accordée.", Toast.LENGTH_SHORT).show()
                    tryToStartServiceIfPermissionsSufficient()
                } else {
                    Toast.makeText(this, "Permission de notification refusée.", Toast.LENGTH_LONG).show()
                }
            }

        bluetoothPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                allBluetoothPermissionsGranted = permissions.entries.all { it.value }
                if (allBluetoothPermissionsGranted) {
                    Toast.makeText(this, "Permissions Bluetooth accordées.", Toast.LENGTH_SHORT).show()
                    tryToStartServiceIfPermissionsSufficient()
                } else {
                    Toast.makeText(this, "Permissions Bluetooth refusées.", Toast.LENGTH_LONG).show()
                    permissions.forEach { (perm, granted) ->
                        if (!granted) Log.w("BluetoothPermissions", "Permission refusée: $perm")
                    }
                }
            }

        foregroundLocationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
                val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

                if (fineLocationGranted || coarseLocationGranted) {
                    Toast.makeText(this, "Permission de localisation au premier plan accordée.", Toast.LENGTH_SHORT).show()
                    checkAndRequestBackgroundLocationPermission()
                } else {
                    Toast.makeText(this, "Permission de localisation au premier plan refusée.", Toast.LENGTH_LONG).show()
                    locationPermissionsGrantedForService = false
                    tryToStartServiceIfPermissionsSufficient()
                }
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationPermissionLauncher =
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                    if (isGranted) {
                        Toast.makeText(this, "Permission de localisation en arrière-plan accordée.", Toast.LENGTH_SHORT).show()
                        locationPermissionsGrantedForService = true
                    } else {
                        Toast.makeText(this, "Permission de localisation en arrière-plan refusée.", Toast.LENGTH_LONG).show()
                        locationPermissionsGrantedForService = areForegroundLocationPermissionsGranted() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    }
                    tryToStartServiceIfPermissionsSufficient()
                }
        }
    }

    fun requestEssentialPermissions() {
        checkAndRequestBluetoothPermissions()
        checkAndRequestNotificationPermission()
    }

    internal fun tryToStartServiceIfPermissionsSufficient() {
        if (allBluetoothPermissionsGranted && areNotificationPermissionsGranted()) {
            Log.d("ServiceManager", "Tentative de démarrage du service Bluetooth.")
            val serviceIntent = Intent(this, BluetoothScanningService::class.java)
            serviceIntent.putExtra("location_granted", locationPermissionsGrantedForService)
            try {
                ContextCompat.startForegroundService(this, serviceIntent)
            } catch (e: Exception) {
                Log.e("ServiceManager", "Impossible de démarrer le service de premier plan", e)
                // Gérer l'erreur, par exemple, si l'application est en arrière-plan sur Android 12+
                // et n'a pas la permission de démarrer les services de premier plan.
            }
        } else {
            if (!allBluetoothPermissionsGranted) {
                Log.w("ServiceManager", "Permissions Bluetooth manquantes pour démarrer le service.")
            }
            if (!areNotificationPermissionsGranted()) {
                Log.w("ServiceManager", "Permission de notification manquante.")
            }
        }
    }

    internal fun areForegroundLocationPermissionsGranted(): Boolean {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineLocationGranted || coarseLocationGranted
    }

    private fun isBackgroundLocationPermissionActuallyGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Pour les versions antérieures à Q, la permission de premier plan est suffisante si accordée
            areForegroundLocationPermissionsGranted()
        }
    }

    // La fonction updateLocationPermissionStatus n'est pas utilisée actuellement, mais peut être conservée si besoin.
    /*
    private fun updateLocationPermissionStatus() {
        val newStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            areForegroundLocationPermissionsGranted() && isBackgroundLocationPermissionActuallyGranted()
        } else {
            areForegroundLocationPermissionsGranted()
        }
        if (locationPermissionsGrantedForService != newStatus) {
            locationPermissionsGrantedForService = newStatus
        }
        Log.d("LocationPermissions", "Statut mis à jour des permissions de localisation pour le service: $locationPermissionsGrantedForService")
    }
    */

    internal fun checkAndRequestLocationPermissions() {
        val permissionsToRequestFg = mutableListOf<String>()
        if (!areForegroundLocationPermissionsGranted()) {
            permissionsToRequestFg.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequestFg.add(Manifest.permission.ACCESS_COARSE_LOCATION) // Peut être utile pour certains appareils
        }

        if (permissionsToRequestFg.isNotEmpty()) {
            Log.d("LocationPermissions", "Demande des permissions de localisation au premier plan: ${permissionsToRequestFg.joinToString()}")
            foregroundLocationPermissionLauncher.launch(permissionsToRequestFg.toTypedArray())
        } else {
            Log.d("LocationPermissions", "Permissions de localisation au premier plan déjà accordées.")
            checkAndRequestBackgroundLocationPermission()
        }
    }

    private fun checkAndRequestBackgroundLocationPermission() {
        // Mettre à jour l'état avant de vérifier/demander
        locationPermissionsGrantedForService = areForegroundLocationPermissionsGranted() && isBackgroundLocationPermissionActuallyGranted()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (areForegroundLocationPermissionsGranted()) { // Condition nécessaire
                if (!isBackgroundLocationPermissionActuallyGranted()) {
                    Log.d("LocationPermissions", "Demande de la permission de localisation en arrière-plan.")
                    backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    Log.d("LocationPermissions", "Permission de localisation en arrière-plan déjà accordée.")
                    tryToStartServiceIfPermissionsSufficient() // Peut-être démarrer le service si toutes les conditions sont remplies
                }
            } else {
                Log.w("LocationPermissions", "Impossible de demander la localisation en arrière-plan sans la permission au premier plan.")
            }
        } else {
            // Avant Q, la permission de premier plan suffit pour le scan en arrière-plan (si le service est bien configuré)
            Log.d("LocationPermissions", "Pas besoin de permission BG spécifique pour cette version d'Android, FG suffit si accordé.")
            if (areForegroundLocationPermissionsGranted()) {
                locationPermissionsGrantedForService = true // Mettre à jour l'état
                tryToStartServiceIfPermissionsSufficient()
            }
        }
    }


    fun checkAndRequestBluetoothPermissions() {
        val requiredPermissions = getRequiredBluetoothPermissions()
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            Log.d("BluetoothPermissions", "Toutes les permissions Bluetooth sont déjà accordées.")
            if (!allBluetoothPermissionsGranted) allBluetoothPermissionsGranted = true
            tryToStartServiceIfPermissionsSufficient()
        } else {
            Log.d("BluetoothPermissions", "Demande des permissions Bluetooth: ${permissionsToRequest.joinToString()}")
            if (allBluetoothPermissionsGranted) allBluetoothPermissionsGranted = false
            bluetoothPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun getRequiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Pour pre-S, fine location est souvent nécessaire pour le scan.
            // BLUETOOTH et BLUETOOTH_ADMIN sont pour le scan et la connexion.
            mutableListOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION // Crucial pour le scan avant Android S
            ).// Assurez-vous d'avoir une logique pour ACCESS_FINE_LOCATION si vous ciblez < S pour le scan
                // et que ce n'est pas déjà géré par checkAndRequestLocationPermissions
                // Si checkAndRequestLocationPermissions ne le demande que pour le service,
                // vous pourriez avoir besoin de le demander ici aussi pour le scan direct
                // ou vous assurer que l'utilisateur l'accorde via le bouton "Localisation".
                // Pour la connexion, BLUETOOTH est la permission clé avant S.
                // BLUETOOTH_CONNECT couvre la connexion pour S+.
            toTypedArray()

        }
    }

    internal fun areNotificationPermissionsGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!areNotificationPermissionsGranted()) {
                Log.d("NotificationPermission", "Demande de la permission de notification.")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d("NotificationPermission", "Permission de notification déjà accordée.")
                tryToStartServiceIfPermissionsSufficient()
            }
        } else {
            tryToStartServiceIfPermissionsSufficient() // Démarrer si les autres permissions sont ok
        }
    }

    @Composable
    fun AppNavigator(
        initialBluetoothPermissionsGranted: Boolean,
        initialNotificationPermissionsGranted: Boolean,
        connectionViewModel: BluetoothConnectionViewModel // Injection du ViewModel
    ) {
        val navController = rememberNavController()
        var currentBluetoothPermissionsGranted by remember { mutableStateOf(initialBluetoothPermissionsGranted) }
        var currentNotificationPermissionsGranted by remember { mutableStateOf(initialNotificationPermissionsGranted) }

        LaunchedEffect(allBluetoothPermissionsGranted) {
            currentBluetoothPermissionsGranted = allBluetoothPermissionsGranted
        }
        // Il est préférable de vérifier l'état actuel de la permission plutôt que d'utiliser LaunchedEffect(areNotificationPermissionsGranted())
        // car la fonction areNotificationPermissionsGranted() pourrait ne pas être appelée si la permission est accordée après le lancement initial.
        // MainActivity.areNotificationPermissionsGranted() est déjà utilisée.
        // Pour être sûr que l'UI réagit, vous pouvez créer un état dans MainActivity pour la permission de notification.
        // Pour cet exemple, nous supposons que la vérification initiale et LaunchedEffect suffisent.

        NavHost(navController = navController, startDestination = NavRoutes.HOME_SCREEN) {
            composable(NavRoutes.HOME_SCREEN) {
                HomeScreen(
                    areBluetoothPermissionsGranted = currentBluetoothPermissionsGranted,
                    areNotificationsGranted = currentNotificationPermissionsGranted,
                    onNavigateToScan = {
                        // Vous pouvez démarrer le scan ici si ce n'est pas automatique avec le service
                        // Par exemple, envoyer un intent au service pour démarrer le scan
                        this@MainActivity.sendBroadcast(Intent(this@MainActivity, BluetoothScanningService::class.java).setAction("START_SCAN_ACTION"))
                        this@MainActivity.tryToStartServiceIfPermissionsSufficient() // Assurez-vous que le service est démarré
                        navController.navigate(NavRoutes.BLUETOOTH_DEVICES_SCREEN)
                    },
                    onNavigateToFirebase = {
                        navController.navigate(NavRoutes.FIREBASE_DEVICES_SCREEN)
                    },
                    mainActivity = this@MainActivity
                )
            }
            composable(NavRoutes.BLUETOOTH_DEVICES_SCREEN) {
                // Ici, nous supposons que BluetoothScanningService.discoveredDevicesList
                // est un StateFlow<List<DeviceInfo>>. Vous devrez l'adapter pour qu'il soit
                // un StateFlow<List<ScannedBluetoothDevice>> ou convertir les DeviceInfo.
                // Pour cet exemple, je vais créer une conversion à la volée.

                // IMPORTANT: BluetoothScanningService.discoveredDevicesList devrait être adapté
                // pour fournir des ScannedBluetoothDevice ou vous devez faire la conversion.
                // Ici, nous allons supposer que BluetoothUiScreen s'attend à une liste de ScannedBluetoothDevice
                // et que vous avez un moyen de l'obtenir depuis votre service.

                // Si BluetoothScanningService.discoveredDevicesList est toujours List<DeviceInfo>
                // vous devrez le convertir.
                // Exemple de conversion (à adapter selon la source réelle):
                val rawDeviceInfos by BluetoothScanningService.discoveredDevicesList.collectAsStateWithLifecycle()
                val scannedDevices = remember(rawDeviceInfos) {
                    val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                    rawDeviceInfos.map { deviceInfo ->
                        ScannedBluetoothDevice(
                            address = deviceInfo.address,
                            name = deviceInfo.name,
                            rssi = deviceInfo.rssi,
                            timestamp = deviceInfo.timestamp,
                            rawDevice = try { btAdapter?.getRemoteDevice(deviceInfo.address) } catch (e: Exception) { null }
                        )
                    }
                }

                BluetoothUiScreen(
                    devices = scannedDevices, // Passer la liste convertie ou directement si le service fournit le bon type
                    connectionViewModel = connectionViewModel,
                    previewDevices = null // Mettre à null pour la version réelle
                )
            }
            composable(NavRoutes.FIREBASE_DEVICES_SCREEN) {
                FirebaseDevicesScreen(onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}

// HomeScreen reste globalement le même, s'assure juste d'appeler tryToStartService
// et navigue correctement.
@Composable
fun HomeScreen(
    areBluetoothPermissionsGranted: Boolean,
    areNotificationsGranted: Boolean,
    onNavigateToScan: () -> Unit,
    onNavigateToFirebase: () -> Unit,
    mainActivity: MainActivity
) {
    LaunchedEffect(Unit) {
        // Demande les permissions au démarrage de l'écran si elles ne sont pas déjà accordées.
        // Ou vous pouvez les demander explicitement via un bouton.
        if (!areBluetoothPermissionsGranted || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !areNotificationsGranted)) {
            mainActivity.requestEssentialPermissions()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Bienvenue dans BTH Explorer", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = {
                if (areBluetoothPermissionsGranted && areNotificationsGranted) {
                    // S'assurer que le service est démarré et qu'un scan est initié
                    mainActivity.tryToStartServiceIfPermissionsSufficient()
                    // L'action START_SCAN_ACTION est déjà envoyée dans AppNavigator,
                    // mais vous pouvez la confirmer ici si nécessaire.
                    onNavigateToScan()
                } else {
                    Toast.makeText(mainActivity, "Permissions Bluetooth et/ou Notification requises.", Toast.LENGTH_LONG).show()
                    mainActivity.requestEssentialPermissions() // Redemander si nécessaire
                }
            },
            enabled = areBluetoothPermissionsGranted && areNotificationsGranted
        ) {
            Text("Scanner les appareils Bluetooth")
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(onClick = { mainActivity.checkAndRequestLocationPermissions() }) {
            Text("Gérer permissions de localisation")
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(onClick = onNavigateToFirebase) {
            Text("Voir les appareils sur Firebase")
        }

        if (!areBluetoothPermissionsGranted) {
            Text(
                "Permissions Bluetooth requises pour le scan.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !areNotificationsGranted) {
            Text(
                "Permission de notification requise (Android 13+).",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}


@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    BthExplorerTheme {
        // Vous devrez peut-être créer une instance factice de MainActivity pour le preview de HomeScreen
        // ou passer des valeurs factices pour les callbacks et les permissions.
        // Pour l'instant, je commente la partie qui cause une erreur de compilation dans le preview.
        /*
        HomeScreen(
            areBluetoothPermissionsGranted = true,
            areNotificationsGranted = true,
            onNavigateToScan = {},
            mainActivity = MainActivity() // This will cause issues in Preview as MainActivity is an Activity
        )
        */
        Text("Preview de l'écran d'accueil (contenu factice)")
    }
}