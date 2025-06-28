package com.reuniware.bthexplorer

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue // Import for observing state changes if using mutableStateOf
import androidx.compose.runtime.mutableStateOf // Import for creating observable state
import androidx.compose.runtime.remember // Import for remember
import androidx.compose.runtime.setValue // Import for observing state changes if using mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.reuniware.bthexplorer.ui.theme.BthExplorerTheme

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var foregroundLocationPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var backgroundLocationPermissionLauncher: ActivityResultLauncher<String>

    // Make this observable if Composables need to react to its changes
    // If only read once during composition, 'var' is fine.
    // For dynamic UI updates based on this, use mutableStateOf.
    // For now, keeping it as 'var' as the original, but will pass it down.
    // If HomeScreen dynamically enables/disables button based on this,
    // it should be a State<Boolean> passed down.
    // For simplicity of this fix, let's keep it 'internal' for now,
    // though passing state is preferred for pure composables.
    internal var allBluetoothPermissionsGranted by mutableStateOf(false) // MODIFIED: Made it observable
    private var locationPermissionsGrantedForService by mutableStateOf(false) // MODIFIED: Made it observable

    object NavRoutes {
        const val HOME_SCREEN = "home"
        const val BLUETOOTH_DEVICES_SCREEN = "bluetooth_devices"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initializePermissionLaunchers() // Initialize launchers first
        // Check initial permission status
        allBluetoothPermissionsGranted = checkInitialBluetoothPermissions()
        locationPermissionsGrantedForService = areForegroundLocationPermissionsGranted() && isBackgroundLocationPermissionActuallyGranted()

        setContent {
            BthExplorerTheme {
                // Pass the current state to AppNavigator
                AppNavigator(
                    initialBluetoothPermissionsGranted = allBluetoothPermissionsGranted,
                    initialNotificationPermissionsGranted = areNotificationPermissionsGranted()
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
                allBluetoothPermissionsGranted = permissions.entries.all { it.value } // Update the state
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
                    Toast.makeText(this, "Permission de localisation au premier plan refusée. Le scan fonctionnera sans données GPS.", Toast.LENGTH_LONG).show()
                    locationPermissionsGrantedForService = false // Update state
                    tryToStartServiceIfPermissionsSufficient()
                }
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationPermissionLauncher =
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                    if (isGranted) {
                        Toast.makeText(this, "Permission de localisation en arrière-plan accordée.", Toast.LENGTH_SHORT).show()
                        locationPermissionsGrantedForService = true // Update state
                    } else {
                        Toast.makeText(this, "Permission de localisation en arrière-plan refusée. Le scan fonctionnera sans données GPS en arrière-plan.", Toast.LENGTH_LONG).show()
                        locationPermissionsGrantedForService = areForegroundLocationPermissionsGranted() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q // Update state
                    }
                    tryToStartServiceIfPermissionsSufficient()
                }
        }
    }

    fun requestEssentialPermissions() {
        checkAndRequestBluetoothPermissions()
        // Location is optional for starting scan, but good to request early if needed
        // checkAndRequestLocationPermissions() // User can click the separate button for this
        checkAndRequestNotificationPermission()
    }

    // Renamed to reflect it checks both BT and Notification for service start
// In MainActivity.kt
    internal fun tryToStartServiceIfPermissionsSufficient() {
        if (allBluetoothPermissionsGranted && areNotificationPermissionsGranted()) {
            Log.d("ServiceManager", "Tentative de démarrage du service Bluetooth.")
            val serviceIntent = Intent(this, BluetoothScanningService::class.java)
            serviceIntent.putExtra("location_granted", locationPermissionsGrantedForService)
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            if (!allBluetoothPermissionsGranted) {
                Log.w("ServiceManager", "Permissions Bluetooth manquantes pour démarrer le service.")
            }
            if (!areNotificationPermissionsGranted()) {
                Log.w("ServiceManager", "Permission de notification manquante (nécessaire pour le service de premier plan sur A13+).")
            }
        }
    }

    internal fun areForegroundLocationPermissionsGranted(): Boolean { // Made internal for HomeScreen
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
            areForegroundLocationPermissionsGranted()
        }
    }

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


    internal fun checkAndRequestLocationPermissions() { // Made internal for HomeScreen
        val permissionsToRequestFg = mutableListOf<String>()
        if (!areForegroundLocationPermissionsGranted()) {
            permissionsToRequestFg.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequestFg.add(Manifest.permission.ACCESS_COARSE_LOCATION)
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
        updateLocationPermissionStatus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (areForegroundLocationPermissionsGranted()) {
                if (!isBackgroundLocationPermissionActuallyGranted()) {
                    Log.d("LocationPermissions", "Demande de la permission de localisation en arrière-plan.")
                    backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    Log.d("LocationPermissions", "Permission de localisation en arrière-plan déjà accordée.")
                    tryToStartServiceIfPermissionsSufficient()
                }
            } else {
                Log.w("LocationPermissions", "Impossible de demander la localisation en arrière-plan sans la permission au premier plan.")
            }
        } else {
            Log.d("LocationPermissions", "Pas besoin de permission BG spécifique pour cette version d'Android, FG suffit si accordé.")
            tryToStartServiceIfPermissionsSufficient()
        }
    }

    fun checkAndRequestBluetoothPermissions() {
        val requiredPermissions = getRequiredBluetoothPermissions()
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            Log.d("BluetoothPermissions", "Toutes les permissions Bluetooth sont déjà accordées.")
            if (!allBluetoothPermissionsGranted) allBluetoothPermissionsGranted = true // Ensure state is correct
            tryToStartServiceIfPermissionsSufficient()
        } else {
            Log.d("BluetoothPermissions", "Demande des permissions Bluetooth: ${permissionsToRequest.joinToString()}")
            if (allBluetoothPermissionsGranted) allBluetoothPermissionsGranted = false // Ensure state is correct before launch
            bluetoothPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun getRequiredBluetoothPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            // For pre-S, fine location is often needed for scan results
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.toTypedArray()
    }

    internal fun areNotificationPermissionsGranted(): Boolean { // Made internal for HomeScreen
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
            tryToStartServiceIfPermissionsSufficient()
        }
    }

    @Composable
    fun AppNavigator(
        initialBluetoothPermissionsGranted: Boolean, // Pass initial state
        initialNotificationPermissionsGranted: Boolean
    ) {
        val navController = rememberNavController()
        // These states in AppNavigator allow HomeScreen to recompose if MainActivity's state changes.
        var currentBluetoothPermissionsGranted by remember { mutableStateOf(initialBluetoothPermissionsGranted) }
        var currentNotificationPermissionsGranted by remember { mutableStateOf(initialNotificationPermissionsGranted) }

        // Observe MainActivity's state changes
        // This is a common pattern but can be complex.
        // A ViewModel shared between Activity and Composables is often a cleaner solution for complex state.
        LaunchedEffect(allBluetoothPermissionsGranted) {
            currentBluetoothPermissionsGranted = allBluetoothPermissionsGranted
        }
        LaunchedEffect(areNotificationPermissionsGranted()) { // Re-check if it can change by other means
            currentNotificationPermissionsGranted = areNotificationPermissionsGranted()
        }


        NavHost(navController = navController, startDestination = NavRoutes.HOME_SCREEN) {
            composable(NavRoutes.HOME_SCREEN) {
                HomeScreen(
                    // Pass the reactive states
                    areBluetoothPermissionsGranted = currentBluetoothPermissionsGranted,
                    areNotificationsGranted = currentNotificationPermissionsGranted,
                    onNavigateToScan = { navController.navigate(NavRoutes.BLUETOOTH_DEVICES_SCREEN) },
                    mainActivity = this@MainActivity
                )
            }
            composable(NavRoutes.BLUETOOTH_DEVICES_SCREEN) {
                BluetoothUiScreen()
            }
        }
    }
}

@Composable
fun HomeScreen(
    areBluetoothPermissionsGranted: Boolean, // Receive the state
    areNotificationsGranted: Boolean,      // Receive the state
    onNavigateToScan: () -> Unit,
    mainActivity: MainActivity // Keep for actions like requesting permissions
) {
    LaunchedEffect(Unit) {
        mainActivity.requestEssentialPermissions()
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
                    // Call the renamed function in MainActivity
                    mainActivity.sendBroadcast(Intent(mainActivity, BluetoothScanningService::class.java).setAction("START_SCAN_ACTION"))
                    mainActivity.tryToStartServiceIfPermissionsSufficient()
                    onNavigateToScan()
                } else {
                    Toast.makeText(mainActivity, "Permissions Bluetooth et/ou Notification requises pour le scan.", Toast.LENGTH_LONG).show()
                    mainActivity.requestEssentialPermissions()
                }
            },
            // Enable button based on passed state
            enabled = areBluetoothPermissionsGranted && areNotificationsGranted
        ) {
            Text("Scanner les appareils Bluetooth")
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(onClick = { mainActivity.checkAndRequestLocationPermissions() }) {
            Text("Accorder/Vérifier les permissions de localisation (Optionnel)")
        }
        if (!areBluetoothPermissionsGranted) {
            Text("Les permissions Bluetooth sont nécessaires pour le scan.", color = MaterialTheme.colorScheme.error)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !areNotificationsGranted) {
            Text("La permission de notification est requise sur Android 13+.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    BthExplorerTheme {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Aperçu de l'écran d'accueil")
            // Example of how HomeScreen might look with permissions granted
            HomeScreen(
                areBluetoothPermissionsGranted = true,
                areNotificationsGranted = true,
                onNavigateToScan = {},
                mainActivity = MainActivity() /* This is not a real instance, for preview only */
            )
        }
    }
}