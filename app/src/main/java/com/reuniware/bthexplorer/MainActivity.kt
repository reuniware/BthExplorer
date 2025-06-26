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
import com.reuniware.bthexplorer.ui.theme.BthExplorerTheme // Assurez-vous que ce chemin est correct

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothPermissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String> // Si vous l'utilisez toujours

    // Constantes pour les routes de navigation
    object NavRoutes {
        const val HOME_SCREEN = "home"
        const val BLUETOOTH_DEVICES_SCREEN = "bluetooth_devices"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        initializePermissionLaunchers()

        // Déplacer la demande de permission à une action utilisateur ou à un moment plus opportun
        // que directement dans onCreate si l'utilisateur n'a pas encore interagi.
        // Pour cet exemple, nous la laissons, mais considérez l'UX.
        checkAndRequestBluetoothPermissions()

        setContent {
            BthExplorerTheme {
                // Le Scaffold principal peut être ici ou dans chaque écran
                // Pour cet exemple, nous le mettons dans chaque écran pour plus de flexibilité
                AppNavigator()
            }
        }
    }

    private fun initializePermissionLaunchers() {
        // ... (votre code pour notificationPermissionLauncher reste le même si besoin)
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
                val allGranted = permissions.entries.all { it.value }
                if (allGranted) {
                    Toast.makeText(this, "Permissions Bluetooth accordées.", Toast.LENGTH_SHORT).show()
                    startBluetoothScanningService()
                } else {
                    Toast.makeText(this, "Permissions Bluetooth refusées. Le scan ne peut pas démarrer.", Toast.LENGTH_LONG).show()
                    permissions.forEach { (perm, granted) ->
                        if (!granted) Log.w("BluetoothPermissions", "Permission refusée: $perm")
                    }
                }
            }
    }

    // --- Fonctions liées au Bluetooth (inchangées) ---
    // checkAndRequestBluetoothPermissions()
    // getRequiredBluetoothPermissions()
    // startBluetoothScanningService()
    // actuallyStartTheService()
    // stopBluetoothScanningService()
    // sont ici et restent telles que vous les avez fournies.
    // Je les omets pour la concision de cet exemple, mais elles doivent être présentes.

    // --- DEBUT : Logique pour les permissions et le service Bluetooth ---
    // (Copiez vos fonctions existantes ici)
    fun checkAndRequestBluetoothPermissions() { // Rendre public si appelé depuis un Composable via une instance de MainActivity
        val requiredPermissions = getRequiredBluetoothPermissions()
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            Log.d("BluetoothPermissions", "Toutes les permissions Bluetooth sont déjà accordées.")
            startBluetoothScanningService()
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
            // Optionnel : ACCESS_FINE_LOCATION si nécessaire
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.toTypedArray()
    }

    fun startBluetoothScanningService() { // Rendre public si appelé depuis un Composable
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
            // Ici, vous pourriez vouloir lancer un Intent pour demander l'activation de Bluetooth
            // val enableBtIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT_CODE) // Gérer le résultat dans onActivityResult
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

    fun stopBluetoothScanningService() { // Rendre public
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
    // --- FIN : Logique pour les permissions et le service Bluetooth ---
}


// --- Composables pour la navigation et les écrans ---

@SuppressLint("ContextCastToActivity")
@Composable
fun AppNavigator() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = MainActivity.NavRoutes.HOME_SCREEN) {
        composable(MainActivity.NavRoutes.HOME_SCREEN) {
            // Passez l'instance de MainActivity si HomeScreen a besoin d'appeler ses méthodes
            // Pour cela, vous devrez probablement passer `LocalContext.current as MainActivity`
            // ou une meilleure approche serait d'utiliser un ViewModel partagé.
            // Pour la simplicité de cet exemple direct :
            val mainActivity = LocalContext.current as? MainActivity // Attention: couplage fort
            HomeScreen(navController = navController, mainActivity = mainActivity)
        }
        composable(MainActivity.NavRoutes.BLUETOOTH_DEVICES_SCREEN) {
            BluetoothUiScreen() // Tel que défini dans BluetoothUiScreen.kt
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, mainActivity: MainActivity?) { // mainActivity peut être null si le cast échoue
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
                .padding(16.dp), // Padding supplémentaire pour le contenu
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
                mainActivity?.checkAndRequestBluetoothPermissions() // Demander les perms et démarrer le service
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

// L'ancien Greeting n'est plus directement utilisé par setContent, mais peut être gardé pour des previews ou autres.
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name! (Ancien Greeting)",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    BthExplorerTheme {
        // Preview de AppNavigator ou HomeScreen pour voir la structure
        // Pour preview HomeScreen, vous aurez besoin d'un NavController mocké et d'une instance de MainActivity.
        // val mockNavController = rememberNavController()
        // AppNavigator() // Pour preview la navigation de base
        HomeScreen(navController = rememberNavController(), mainActivity = null) // Preview simple de HomeScreen
    }
}