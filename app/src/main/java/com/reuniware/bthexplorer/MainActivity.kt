package com.reuniware.bthexplorer

import android.Manifest
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.reuniware.bthexplorer.ui.theme.BthExplorerTheme
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    // --- AJOUT pour les permissions Bluetooth ---
    private lateinit var bluetoothPermissionLauncher: ActivityResultLauncher<Array<String>>
    // --- FIN AJOUT ---

    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        initializePermissionLaunchers()

        // --- AJOUT : Vérifier et demander les permissions Bluetooth au démarrage ---
        // Vous pouvez déplacer cet appel si vous souhaitez le déclencher
        // sur une action utilisateur spécifique plutôt qu'au démarrage de l'activité.
        checkAndRequestBluetoothPermissions()
        // --- FIN AJOUT ---


        setContent {
            BthExplorerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun initializePermissionLaunchers() {
        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    Toast.makeText(this, "Permission de notification accordée.", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    Toast.makeText(this, "Permission de notification refusée.", Toast.LENGTH_LONG)
                        .show()
                }
            }

        // --- AJOUT : Initialisation du launcher pour les permissions Bluetooth ---
        bluetoothPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                val allGranted = permissions.entries.all { it.value }
                if (allGranted) {
                    Toast.makeText(this, "Permissions Bluetooth accordées.", Toast.LENGTH_SHORT)
                        .show()
                    startBluetoothScanningService() // Démarrer le service si les permissions sont accordées
                } else {
                    Toast.makeText(
                        this,
                        "Permissions Bluetooth refusées. Le scan ne peut pas démarrer.",
                        Toast.LENGTH_LONG
                    ).show()
                    // Logguer quelles permissions ont été refusées pour le débogage
                    permissions.forEach { (perm, granted) ->
                        if (!granted) Log.w("BluetoothPermissions", "Permission refusée: $perm")
                    }
                }
            }
        // --- FIN AJOUT ---

    }

    // --- AJOUT : Logique pour les permissions et le service Bluetooth ---
    private fun checkAndRequestBluetoothPermissions() {
        val requiredPermissions = getRequiredBluetoothPermissions()
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            // Toutes les permissions Bluetooth nécessaires sont déjà accordées
            Log.d("BluetoothPermissions", "Toutes les permissions Bluetooth sont déjà accordées.")
            startBluetoothScanningService()
        } else {
            Log.d(
                "BluetoothPermissions",
                "Demande des permissions Bluetooth: ${permissionsToRequest.joinToString()}"
            )
            bluetoothPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun getRequiredBluetoothPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+ (API 31)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            // Ajoutez BLUETOOTH_CONNECT si votre service en a besoin pour obtenir le nom, se connecter, etc.
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)

            // Selon la documentation Android :
            // "Si votre application utilise les résultats de la recherche Bluetooth pour déduire la position physique,
            // vous devez également déclarer la permission ACCESS_FINE_LOCATION."
            // Si vous n'utilisez PAS les résultats pour la localisation, vous pouvez ajouter
            // android:usesPermissionFlags="neverForLocation" à BLUETOOTH_SCAN dans le Manifest.
            // Pour être sûr, ou si vous pourriez déduire la localisation, incluez ACCESS_FINE_LOCATION.
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Si vous avez un mécanisme séparé pour demander ACCESS_FINE_LOCATION, vous n'avez peut-être pas besoin de l'ajouter ici.
                // Cependant, si ce service Bluetooth est le seul à en avoir besoin, c'est un bon endroit.
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        } else { // Android 11 (API 30) et versions antérieures
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION) // Crucial pour le scan sur ces versions
        }
        return permissions.toTypedArray()
    }

    private fun startBluetoothScanningService() {
        // Vérifier si l'adaptateur Bluetooth est activé avant de démarrer le service
        val bluetoothManager =
            getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Log.e("BluetoothService", "Cet appareil ne supporte pas Bluetooth.")
            Toast.makeText(this, "Bluetooth non supporté sur cet appareil.", Toast.LENGTH_LONG)
                .show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.w("BluetoothService", "Bluetooth n'est pas activé.")
            Toast.makeText(
                this,
                "Veuillez activer Bluetooth pour le scan des appareils.",
                Toast.LENGTH_LONG
            ).show()
            // Optionnel : Demander à l'utilisateur d'activer Bluetooth de manière programmatique
            // val enableBtIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            // val bluetoothEnableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            //     if (result.resultCode == Activity.RESULT_OK) {
            //         Log.d("BluetoothService", "Bluetooth activé par l'utilisateur.")
            //         actuallyStartTheService() // Une fonction interne pour vraiment démarrer après activation
            //     } else {
            //         Log.w("BluetoothService", "L'utilisateur n'a pas activé Bluetooth.")
            //         Toast.makeText(this, "Bluetooth non activé. Le scan ne peut pas démarrer.", Toast.LENGTH_SHORT).show()
            //     }
            // }
            // bluetoothEnableLauncher.launch(enableBtIntent)
            return // Ne pas démarrer le service si Bluetooth est désactivé
        }

        actuallyStartTheService()
    }

    private fun actuallyStartTheService() {
        Log.i(
            "MainActivity",
            "Permissions et état Bluetooth OK. Démarrage du BluetoothScanningService."
        )
        val serviceIntent = Intent(
            this,
            BluetoothScanningService::class.java
        ) // Assurez-vous que BluetoothScanningService.kt existe
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Service de scan Bluetooth démarré.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur au démarrage du BluetoothScanningService", e)
            Toast.makeText(
                this,
                "Erreur au démarrage du service de scan Bluetooth: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Optionnel: Méthode pour arrêter le service (par exemple dans onDestroy ou sur action utilisateur)
    private fun stopBluetoothScanningService() {
        Log.d("MainActivity", "Tentative d'arrêt du BluetoothScanningService.")
        val serviceIntent = Intent(this, BluetoothScanningService::class.java)
        try {
            stopService(serviceIntent)
            Toast.makeText(this, "Service de scan Bluetooth arrêté.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Erreur à l'arrêt du BluetoothScanningService", e)
            Toast.makeText(
                this,
                "Erreur à l'arrêt du service de scan Bluetooth.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    // --- FIN AJOUT ---
}

    @Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    BthExplorerTheme {
        Greeting("Android")
    }
}