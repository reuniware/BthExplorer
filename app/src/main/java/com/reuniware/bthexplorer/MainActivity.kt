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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
// Import pour la navigation Compose
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

    private var allBluetoothPermissionsGranted = false
    private var allLocationPermissionsGrantedForService = false

    object NavRoutes {
        const val HOME_SCREEN = "home"
        const val BLUETOOTH_DEVICES_SCREEN = "bluetooth_devices"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initializePermissionLaunchers()
        setContent {
            BthExplorerTheme {
                AppNavigator()
            }
        }
    }

    private fun initializePermissionLaunchers() {
        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    Toast.makeText(this, "Permission de notification accordée.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Permission de notification refusée.", Toast.LENGTH_LONG).show()
                }
            }

        bluetoothPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                allBluetoothPermissionsGranted = permissions.entries.all { it.value }
                if (allBluetoothPermissionsGranted) {
                    Toast.makeText(this, "Permissions Bluetooth accordées.", Toast.LENGTH_SHORT).show()
                    tryToStartServiceIfAllPermissionsGranted()
                } else {
                    Toast.makeText(this, "Permissions Bluetooth refusées.", Toast.LENGTH_LONG).show()
                    permissions.forEach { (perm, granted) ->
                        if (!granted) Log.w("BluetoothPermissions", "Permission refusée: $perm")
                    }
                    allBluetoothPermissionsGranted = false
                }
            }

        foregroundLocationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
                val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

                if (fineLocationGranted || coarseLocationGranted) {
                    Toast.makeText(this, "Permission de localisation au premier plan accordée.", Toast.LENGTH_SHORT).show()
                    // Si les perms au premier plan sont OK, vérifier/demander celle en arrière-plan
                    checkAndRequestBackgroundLocationPermission()
                } else {
                    Toast.makeText(this, "Permission de localisation au premier plan refusée.", Toast.LENGTH_LONG).show()
                    allLocationPermissionsGrantedForService = false
                    // Il faut informer l'utilisateur que sans localisation, certaines fonctionnalités ne marcheront pas.
                }
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationPermissionLauncher =
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                    if (isGranted) {
                        Toast.makeText(this, "Permission de localisation en arrière-plan accordée.", Toast.LENGTH_SHORT).show()
                        allLocationPermissionsGrantedForService = true
                        tryToStartServiceIfAllPermissionsGranted()
                    } else {
                        Toast.makeText(this, "Permission de localisation en arrière-plan refusée.", Toast.LENGTH_LONG).show()
                        allLocationPermissionsGrantedForService = false
                        // Expliquer pourquoi c'est important ou comment l'activer manuellement
                    }
                }
        } else {
            // Pour les versions antérieures à Android Q, la permission de localisation au premier plan
            // est suffisante si le service est un service de premier plan.
            // On considère donc la permission de localisation en arrière-plan comme "accordée" par défaut
            // si celle au premier plan l'est. La logique de `checkAndRequestBackgroundLocationPermission`
            // et `isBackgroundLocationPermissionGranted` gère cela.
        }
    }

    // --- Vérification et Demande Groupée des Permissions ---
    fun requestAllNecessaryPermissions() {
        // 1. Vérifier les permissions de localisation en premier
        checkAndRequestLocationPermissions()
        // 2. Ensuite, vérifier les permissions Bluetooth.
        // La fonction `checkAndRequestBluetoothPermissions` essaiera de démarrer le service
        // via `tryToStartServiceIfAllPermissionsGranted` si les perms BT sont accordées.
        // `tryToStartServiceIfAllPermissionsGranted` vérifiera alors si les perms de localisation sont aussi OK.
        checkAndRequestBluetoothPermissions()
    }


    // --- Logique pour les permissions de LOCALISATION ---
    private fun areForegroundLocationPermissionsGranted(): Boolean {
        val fineLocationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseLocationGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineLocationGranted || coarseLocationGranted
    }

    private fun isBackgroundLocationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Avant Android Q, si les perms au premier plan sont accordées, c'est suffisant pour le scan BT
            // si le service est un service de premier plan.
            areForegroundLocationPermissionsGranted()
        }
    }

    private fun checkAndRequestLocationPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (!areForegroundLocationPermissionsGranted()) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION) // L'utilisateur choisira
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d("LocationPermissions", "Demande des permissions de localisation au premier plan: ${permissionsToRequest.joinToString()}")
            foregroundLocationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d("LocationPermissions", "Permissions de localisation au premier plan déjà accordées.")
            // Directement vérifier/demander la permission en arrière-plan
            checkAndRequestBackgroundLocationPermission()
        }
    }

    private fun checkAndRequestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (areForegroundLocationPermissionsGranted()) { // On ne demande BG que si FG est OK
                if (!isBackgroundLocationPermissionGranted()) {
                    // TODO: Afficher une explication à l'utilisateur avant de demander la permission en arrière-plan.
                    // Par exemple, avec un AlertDialog.
                    Log.d("LocationPermissions", "Demande de la permission de localisation en arrière-plan.")
                    backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    Log.d("LocationPermissions", "Permission de localisation en arrière-plan déjà accordée.")
                    allLocationPermissionsGrantedForService = true
                    tryToStartServiceIfAllPermissionsGranted()
                }
            } else {
                Log.w("LocationPermissions", "Impossible de demander la localisation en arrière-plan sans la permission au premier plan.")
                allLocationPermissionsGrantedForService = false
            }
        } else {
            // Avant Android Q, si les perms au premier plan sont OK, c'est suffisant.
            allLocationPermissionsGrantedForService = areForegroundLocationPermissionsGranted()
            if(allLocationPermissionsGrantedForService) {
                Log.d("LocationPermissions", "Pas besoin de permission BG spécifique pour cette version d'Android, FG suffit.")
                tryToStartServiceIfAllPermissionsGranted()
            } else {
                Log.w("LocationPermissions", "Permissions de localisation au premier plan manquantes.")
            }
        }
    }


    // --- Logique pour les permissions BLUETOOTH (votre code existant légèrement adapté) ---
    fun checkAndRequestBluetoothPermissions() {
        val requiredPermissions = getRequiredBluetoothPermissions()
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            Log.d("BluetoothPermissions", "Toutes les permissions Bluetooth sont déjà accordées.")
            allBluetoothPermissionsGranted = true
            tryToStartServiceIfAllPermissionsGranted()
        } else {
            Log.d("BluetoothPermissions", "Demande des permissions Bluetooth: ${permissionsToRequest.joinToString()}")
            bluetoothPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun getRequiredBluetoothPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            // Note: ACCESS_FINE_LOCATION est gérée séparément maintenant pour plus de clarté,
            // mais BLUETOOTH_SCAN peut la nécessiter implicitement pour les résultats.
            // Si le scan ne fonctionne pas sans, il faudra la remettre ici OU s'assurer que
            // la logique de `requestAllNecessaryPermissions` a bien fait accorder la localisation.
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            // Pour les versions antérieures à S, ACCESS_FINE_LOCATION est obligatoire pour le scan.
            // Assurons-nous qu'elle est demandée.
            if (!areForegroundLocationPermissionsGranted()) {
                // Cette condition est un peu délicate ici. La logique principale
                // de demande de localisation devrait déjà s'en charger.
                // On peut l'ajouter ici comme filet de sécurité si la logique externe ne l'a pas fait.
                // permissions.add(Manifest.permission.ACCESS_FINE_LOCATION) // Plutôt gérée par checkAndRequestLocationPermissions
            }
        }
        // Ajout de la permission de localisation si nécessaire pour le scan BT, surtout avant Android S.
        // La logique est complexe car Android S a changé la donne avec BLUETOOTH_SCAN.
        // Pour simplifier, on s'assure que si on est avant S, FINE_LOCATION est demandée.
        // Si on est S ou plus, BLUETOOTH_SCAN peut suffire pour les noms/adresses, mais pas pour la localisation dérivée.
        // Le plus sûr est de s'assurer que les permissions de localisation sont gérées séparément et accordées.
        // Pour cet exemple, on suppose que `checkAndRequestLocationPermissions` s'en est occupé.

        return permissions.toTypedArray()
    }

    // --- Démarrage du Service ---
    private fun tryToStartServiceIfAllPermissionsGranted() {
        // Mettre à jour allLocationPermissionsGrantedForService en fonction de l'état actuel des perms
        allLocationPermissionsGrantedForService = areForegroundLocationPermissionsGranted() && isBackgroundLocationPermissionGranted()

        if (allBluetoothPermissionsGranted && allLocationPermissionsGrantedForService) {
            Log.i("MainActivity", "Toutes les permissions nécessaires (BT et Localisation) sont accordées. Démarrage du service.")
            startBluetoothScanningService()
        } else {
            var missingPerms = ""
            if (!allBluetoothPermissionsGranted) missingPerms += "Bluetooth "
            if (!allLocationPermissionsGrantedForService) missingPerms += "Localisation "
            Log.w("MainActivity", "Permissions manquantes pour démarrer le service: $missingPerms")
            if (missingPerms.isNotEmpty()) {
                Toast.makeText(this, "Permissions manquantes: $missingPerms", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun startBluetoothScanningService() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Log.e("BluetoothService", "Cet appareil ne supporte pas Bluetooth.")
            Toast.makeText(this, "Bluetooth non supporté.", Toast.LENGTH_LONG).show()
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Log.w("BluetoothService", "Bluetooth n'est pas activé.")
            Toast.makeText(this, "Veuillez activer Bluetooth.", Toast.LENGTH_LONG).show()
            // Intent pour activer BT:
            // val enableBtIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT_CODE) // Gérer le résultat
            return
        }
        actuallyStartTheService()
    }

    private fun actuallyStartTheService() {
        Log.i("MainActivity", "Démarrage du BluetoothScanningService.")
        val serviceIntent = Intent(this, BluetoothScanningService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Service de scan Bluetooth démarré.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur au démarrage du BluetoothScanningService", e)
            Toast.makeText(this, "Erreur démarrage service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun stopBluetoothScanningService() {
        Log.d("MainActivity", "Arrêt du BluetoothScanningService.")
        val serviceIntent = Intent(this, BluetoothScanningService::class.java)
        try {
            stopService(serviceIntent)
            Toast.makeText(this, "Service de scan Bluetooth arrêté.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur à l'arrêt du BluetoothScanningService", e)
            Toast.makeText(this, "Erreur arrêt service.", Toast.LENGTH_LONG).show()
        }
    }
}


// --- Composables pour la navigation et les écrans ---

@SuppressLint("ContextCastToActivity")
@Composable
fun AppNavigator() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = MainActivity.NavRoutes.HOME_SCREEN) {
        composable(MainActivity.NavRoutes.HOME_SCREEN) {
            val mainActivity = LocalContext.current as? MainActivity
            HomeScreen(navController = navController, mainActivity = mainActivity)
        }
        composable(MainActivity.NavRoutes.BLUETOOTH_DEVICES_SCREEN) {
            BluetoothUiScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, mainActivity: MainActivity?) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BthExplorer Accueil") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Bienvenue dans BthExplorer!",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            Button(onClick = {
                navController.navigate(MainActivity.NavRoutes.BLUETOOTH_DEVICES_SCREEN)
            }) {
                Text("Voir les appareils Bluetooth")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                // Demander toutes les permissions nécessaires (Localisation puis Bluetooth)
                mainActivity?.requestAllNecessaryPermissions()
            }) {
                Text("Démarrer Scan (Vérif Permissions)")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                mainActivity?.stopBluetoothScanningService()
            }) {
                Text("Arrêter Scan Service")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    BthExplorerTheme {
        HomeScreen(navController = rememberNavController(), mainActivity = null)
    }
}