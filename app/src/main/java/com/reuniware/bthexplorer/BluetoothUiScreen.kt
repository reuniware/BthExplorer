package com.reuniware.bthexplorer

import kotlinx.coroutines.flow.MutableStateFlow

import android.app.Application // Utilisé seulement pour le Preview ici, attention.
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit // Pour la signature de TableCell
import androidx.lifecycle.compose.collectAsStateWithLifecycle // IMPORTANT
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Définition de DeviceInfo - Si elle est dans BluetoothScanningService.kt, vous pouvez l'importer.
// Si vous la dupliquez, assurez-vous qu'elles sont compatibles.
// Il est préférable de la définir une seule fois dans un fichier commun ou dans le service et de l'importer.
/*
data class DeviceInfo(
    val address: String,
    val name: String?,
    val rssi: Int,
    val estimatedDistance: Double?,
    val timestamp: Long = System.currentTimeMillis()
)
*/
// Supposons que DeviceInfo soit importé depuis com.reuniware.bthexplorer.BluetoothScanningService
// ou un autre fichier partagé. Si ce n'est pas le cas, décommentez la définition ci-dessus.


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothUiScreen() {
    // Collecter la liste directement depuis l'objet compagnon du service
    // Utiliser collectAsStateWithLifecycle pour une collecte respectueuse du cycle de vie
    val devicesList by BluetoothScanningService.discoveredDevicesList.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appareils Bluetooth Détectés") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    Button(onClick = { BluetoothScanningService.clearDeviceList() }) { // Appel statique
                        Text("Vider")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding) // Appliquer le padding ici
        ) {
            if (devicesList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp), // Padding interne pour le message
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Aucun appareil détecté ou scan non démarré.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // En-têtes de colonnes
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TableCell(text = "Nom", weight = 2.5f, title = true)
                    TableCell(text = "Adresse", weight = 3.5f, title = true) // Un peu plus de place pour l'adresse
                    TableCell(text = "RSSI", weight = 1.2f, title = true)
                    TableCell(text = "Dist.", weight = 1.3f, title = true)
                    TableCell(text = "Vu", weight = 1.5f, title = true)
                }
                Divider(color = MaterialTheme.colorScheme.outlineVariant)

                LazyColumn(
                    modifier = Modifier.fillMaxSize() // Prendra l'espace restant
                ) {
                    items(
                        items = devicesList,
                        key = { device -> device.address } // Clé unique pour chaque élément
                    ) { device ->
                        DeviceRow(device = device)
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) // Diviseur plus léger entre les lignes
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceRow(device: DeviceInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp), // Un peu plus de padding vertical pour la lisibilité
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TableCell(text = device.name ?: "N/A", weight = 2.5f)
        TableCell(text = device.address, weight = 3.5f, fontSize = 12.sp)
        TableCell(text = "${device.rssi}", weight = 1.2f, alignment = Alignment.CenterHorizontally)
        TableCell(
            text = device.estimatedDistance?.let { "%.1fm".format(Locale.US, it) } ?: "-",
            weight = 1.3f,
            alignment = Alignment.CenterHorizontally
        )
        TableCell(
            text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(device.timestamp)),
            weight = 1.5f,
            fontSize = 12.sp,
            alignment = Alignment.CenterHorizontally
        )
    }
}

@Composable
fun RowScope.TableCell(
    text: String,
    weight: Float,
    alignment: Alignment.Horizontal = Alignment.Start, // Maintenant utilisé
    title: Boolean = false,
    fontSize: TextUnit = TextUnit.Unspecified // Type correct pour fontSize
) {
    Text(
        text = text,
        modifier = Modifier
            .weight(weight)
            .padding(end = 4.dp, start = 4.dp), // Padding horizontal pour chaque cellule
        fontWeight = if (title) FontWeight.Bold else FontWeight.Normal,
        fontSize = if (title) 14.sp else if (fontSize != TextUnit.Unspecified) fontSize else MaterialTheme.typography.bodyMedium.fontSize,
        color = if (title) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = when(alignment) { // Pour utiliser l'alignement horizontal
            Alignment.CenterHorizontally -> androidx.compose.ui.text.style.TextAlign.Center
            Alignment.End -> androidx.compose.ui.text.style.TextAlign.End
            else -> androidx.compose.ui.text.style.TextAlign.Start
        },
        maxLines = if (title) 1 else 2, // Permettre au nom de l'appareil de passer sur 2 lignes
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis // Pour les noms trop longs
    )
}


// --- Preview ---
// Mock DeviceInfo pour la Preview (si défini dans ce fichier)
/*
private val previewDevices = listOf(
    DeviceInfo("00:11:22:33:44:55", "Mon Super Tracker BLE", -55, 2.5, System.currentTimeMillis() - 10000),
    DeviceInfo("AA:BB:CC:DD:EE:FF", "Balise Inconnue XYZ", -78, 12.1, System.currentTimeMillis() - 5000),
    DeviceInfo("A1:B2:C3:D4:E5:F6", null, -60, 4.0, System.currentTimeMillis())
)
*/

@Preview(showBackground = true, widthDp = 380)
@Composable
fun BluetoothUiScreenPreview() {
    // Pour la preview, vous ne pourrez pas facilement alimenter le StateFlow statique
    // du service. Vous pouvez soit afficher un état vide, soit, si vous avez
    // une manière de mocker `BluetoothScanningService.discoveredDevicesList`
    // (par exemple, en ayant une version de debug du companion object), vous pourriez l'utiliser.
    // Pour l'instant, cette preview affichera l'état vide ou ce qui est
    // potentiellement dans le StateFlow si le service a tourné dans une exécution précédente.

    // Alternative pour la preview: Utiliser un StateFlow local avec des données mockées.
    // Ceci est UNIQUEMENT pour la preview et ne reflète pas le comportement réel
    // de connexion au service.
    val mockDevices = remember {
        listOf(
            com.reuniware.bthexplorer.DeviceInfo("00:11:22:33:44:55", "Tracker de Test BLE", -55, 2.5, System.currentTimeMillis() - 10000),
            com.reuniware.bthexplorer.DeviceInfo("AA:BB:CC:DD:EE:FF", "Balise Inconnue Long Nom", -78, 12.1, System.currentTimeMillis() - 5000),
            com.reuniware.bthexplorer.DeviceInfo("A1:B2:C3:D4:E5:F6", null, -60, 4.0, System.currentTimeMillis())
        )
    }
    val mockDiscoveredDevicesList = remember { MutableStateFlow(mockDevices) }


    MaterialTheme { // Assurez-vous d'utiliser votre thème d'application
        // Pour simuler le Composable réel dans la Preview:
        // BluetoothUiScreen()

        // Pour une Preview avec des données contrôlées (si BluetoothUiScreen était modifiable pour prendre une liste en paramètre)
        // BluetoothUiScreenWithData(devicesList = mockDiscoveredDevicesList.collectAsStateWithLifecycle().value)

        // Puisque BluetoothUiScreen utilise directement le StateFlow statique, la preview simple
        // est la plus directe, mais peut ne pas montrer de données à moins que le service
        // n'ait déjà peuplé le StateFlow.
        // Si vous avez importé DeviceInfo depuis le service, utilisez son chemin complet:
        // com.reuniware.bthexplorer.BluetoothScanningService.DeviceInfo(...)
        // Sinon, si DeviceInfo est défini localement pour la Preview, utilisez juste DeviceInfo(...)
        BluetoothUiScreen() // Va essayer de lire BluetoothScanningService.discoveredDevicesList
    }
}

// Si vous voulez une preview avec des données garanties, vous pourriez temporairement
// modifier BluetoothUiScreen pour accepter une liste en paramètre.
/*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothUiScreenWithData(devicesList: List<com.reuniware.bthexplorer.DeviceInfo>) { // Type complet si importé
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appareils Bluetooth (Preview)") },
                actions = { Button(onClick = { /* No-op pour preview */ }) { Text("Vider") } }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (devicesList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Aucun appareil pour la preview.")
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TableCell(text = "Nom", weight = 2.5f, title = true)
                    // ... autres cellules d'en-tête
                }
                Divider()
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = devicesList, key = { it.address }) { device ->
                        DeviceRow(device = device)
                        Divider()
                    }
                }
            }
        }
    }
}
*/