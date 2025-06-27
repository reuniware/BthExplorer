package com.reuniware.bthexplorer

import android.util.Log // Ajout pour le débogage dans la Preview
import kotlinx.coroutines.flow.MutableStateFlow
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color // Pour le test de couleur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Assurez-vous que DeviceInfo est correctement définie ou importée
// Ex: import com.reuniware.bthexplorer.BluetoothScanningService.DeviceInfo
// ou décommentez et définissez-la ici si elle est partagée.
/*
data class DeviceInfo(
    val address: String,
    val name: String?,
    val rssi: Int,
    val estimatedDistance: Double?,
    val timestamp: Long = System.currentTimeMillis()
)
*/

enum class SortCriterion {
    NAME, ADDRESS, RSSI, DISTANCE, LAST_SEEN
}

enum class SortOrder {
    ASCENDING, DESCENDING
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothUiScreen(
    // Paramètre optionnel pour injecter des données dans la Preview
    previewDevices: List<DeviceInfo>? = null
) {
    // Collecte l'état du service uniquement si previewDevices n'est pas fourni
    val devicesFromServiceState by if (previewDevices == null) {
        BluetoothScanningService.discoveredDevicesList.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(previewDevices) } // Utilise les données de la preview directement
    }

    // La liste actuelle à utiliser pour l'UI
    val currentDeviceList = devicesFromServiceState ?: emptyList() // Fallback sur liste vide si null
    val numberOfDevices = currentDeviceList.size

    var currentSortCriterion by remember { mutableStateOf(SortCriterion.LAST_SEEN) }
    var currentSortOrder by remember { mutableStateOf(SortOrder.DESCENDING) }

    val context = LocalContext.current

    // Le Toast ne s'exécutera que si nous ne sommes pas en mode Preview avec des données injectées
    // Le Toast ne s'exécutera que si nous ne  pas en mode Preview avec des données injectées
    if (previewDevices == null) {
        LaunchedEffect(numberOfDevices) {
            // Ce log s'exécutera uniquement dans l'application réelle, pas avec previewDevices
            Log.d("BluetoothUiScreenReal", "Real app - numberOfDevices: $numberOfDevices")
            if (numberOfDevices > 0) {
                Toast.makeText(context, "Nombre d'appareils : $numberOfDevices", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val sortedDevicesList = remember(currentDeviceList, currentSortCriterion, currentSortOrder) {
        val comparator = when (currentSortCriterion) {
            SortCriterion.NAME -> compareBy<DeviceInfo, String?>(nullsLast(), { it.name?.lowercase() })
            SortCriterion.ADDRESS -> compareBy { it.address }
            SortCriterion.RSSI -> compareBy { it.rssi }
            SortCriterion.DISTANCE -> compareBy(nullsLast()) { it.estimatedDistance }
            SortCriterion.LAST_SEEN -> compareBy { it.timestamp }
        }
        if (currentSortOrder == SortOrder.ASCENDING) {
            currentDeviceList.sortedWith(comparator)
        } else {
            currentDeviceList.sortedWith(comparator.reversed())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth() // Assurez-vous que la Row peut prendre toute la largeur
                    ) {
                        Text(
                            text = "Appareils Détectés", // Votre titre complet
                            style = MaterialTheme.typography.titleLarge, // Ou le style que vous utilisiez
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis, // Important
                            modifier = Modifier.weight(1f, fill = false) // Le titre prend l'espace nécessaire, s'arrête si pas assez de place pour le reste
                        )
                        // Espaceur optionnel si vous voulez un peu d'air entre le titre et le nombre
                        // Spacer(modifier = Modifier.width(4.dp))
                        if (numberOfDevices > 0) {
                            Text(
                                text = " ($numberOfDevices)",
                                style = MaterialTheme.typography.titleMedium, // Peut-être un style un peu plus petit
                                color = Color.Red // Ou MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    // Les actions peuvent être conditionnelles si elles dépendent de l'état réel du service
                    if (previewDevices == null) { // N'afficher le bouton "Vider" que en mode réel
                        Button(onClick = { BluetoothScanningService.clearDeviceList() }) {
                            Text("Vider")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (sortedDevicesList.isEmpty()) { // Important: utiliser sortedDevicesList ici
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (previewDevices != null && previewDevices.isEmpty()) "Aucun appareil mocké pour la preview."
                        else "Aucun appareil détecté ou scan non démarré.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // EN-TÊTES DE COLONNES CLIQUABLES (poids ajustés comme dans votre code)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HeaderCell(text = "Nom", weight = 2.5f, criterion = SortCriterion.NAME, currentCriterion = currentSortCriterion, currentOrder = currentSortOrder) {
                        /* ... logique de tri ... */
                        if (currentSortCriterion == SortCriterion.NAME) currentSortOrder = if (currentSortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
                        else { currentSortCriterion = SortCriterion.NAME; currentSortOrder = SortOrder.ASCENDING }
                    }
                    HeaderCell(text = "Adresse", weight = 2.0f, /* Votre code avait 2.0f ici */ criterion = SortCriterion.ADDRESS, currentCriterion = currentSortCriterion, currentOrder = currentSortOrder) {
                        /* ... logique de tri ... */
                        if (currentSortCriterion == SortCriterion.ADDRESS) currentSortOrder = if (currentSortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
                        else { currentSortCriterion = SortCriterion.ADDRESS; currentSortOrder = SortOrder.ASCENDING }
                    }
                    HeaderCell(text = "RSSI", weight = 1.2f, alignment = Alignment.CenterHorizontally, criterion = SortCriterion.RSSI, currentCriterion = currentSortCriterion, currentOrder = currentSortOrder) {
                        /* ... logique de tri ... */
                        if (currentSortCriterion == SortCriterion.RSSI) currentSortOrder = if (currentSortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
                        else { currentSortCriterion = SortCriterion.RSSI; currentSortOrder = SortOrder.DESCENDING }
                    }
                    HeaderCell(text = "Dist.", weight = 1.3f, alignment = Alignment.CenterHorizontally, criterion = SortCriterion.DISTANCE, currentCriterion = currentSortCriterion, currentOrder = currentSortOrder) {
                        /* ... logique de tri ... */
                        if (currentSortCriterion == SortCriterion.DISTANCE) currentSortOrder = if (currentSortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
                        else { currentSortCriterion = SortCriterion.DISTANCE; currentSortOrder = SortOrder.ASCENDING }
                    }
                    HeaderCell(text = "Vu", weight = 2.2f, /* Votre code avait 2.2f ici */ alignment = Alignment.CenterHorizontally, criterion = SortCriterion.LAST_SEEN, currentCriterion = currentSortCriterion, currentOrder = currentSortOrder) {
                        /* ... logique de tri ... */
                        if (currentSortCriterion == SortCriterion.LAST_SEEN) currentSortOrder = if (currentSortOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
                        else { currentSortCriterion = SortCriterion.LAST_SEEN; currentSortOrder = SortOrder.DESCENDING }
                    }
                }
                Divider(color = MaterialTheme.colorScheme.outlineVariant)

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = sortedDevicesList,
                        key = { device -> device.address }
                    ) { device ->
                        DeviceRow(device = device) // Poids dans DeviceRow doivent correspondre aux en-têtes
                        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
fun RowScope.HeaderCell(
    text: String,
    weight: Float,
    criterion: SortCriterion,
    currentCriterion: SortCriterion,
    currentOrder: SortOrder,
    alignment: Alignment.Horizontal = Alignment.Start,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .weight(weight)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = when (alignment) {
            Alignment.CenterHorizontally -> Arrangement.Center
            Alignment.End -> Arrangement.End
            else -> Arrangement.Start
        }
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = when(alignment) {
                Alignment.CenterHorizontally -> androidx.compose.ui.text.style.TextAlign.Center
                Alignment.End -> androidx.compose.ui.text.style.TextAlign.End
                else -> androidx.compose.ui.text.style.TextAlign.Start
            },
            modifier = if (alignment != Alignment.Start) Modifier.weight(1f, fill = false) else Modifier
        )
        if (currentCriterion == criterion) {
            Icon(
                imageVector = if (currentOrder == SortOrder.ASCENDING) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = "Sort order",
                modifier = Modifier
                    .size(16.dp)
                    .padding(start = 2.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun DeviceRow(device: DeviceInfo) { // Les poids ici doivent correspondre à ceux des HeaderCell
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TableCell(text = device.name ?: "N/A", weight = 2.5f)
        TableCell(text = device.address, weight = 2.0f, fontSize = 12.sp) // Match HeaderCell
        TableCell(text = "${device.rssi}", weight = 1.2f, alignment = Alignment.CenterHorizontally)
        TableCell(text = device.estimatedDistance?.let { "%.1fm".format(Locale.US, it) } ?: "-", weight = 1.3f, alignment = Alignment.CenterHorizontally)
        TableCell(text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(device.timestamp)), weight = 2.2f, fontSize = 12.sp, alignment = Alignment.CenterHorizontally) // Match HeaderCell
    }
}

@Composable
fun RowScope.TableCell(
    text: String,
    weight: Float,
    alignment: Alignment.Horizontal = Alignment.Start,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    Text(
        text = text,
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 4.dp),
        fontWeight = FontWeight.Normal,
        fontSize = if (fontSize != TextUnit.Unspecified) fontSize else MaterialTheme.typography.bodyMedium.fontSize,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = when(alignment) {
            Alignment.CenterHorizontally -> androidx.compose.ui.text.style.TextAlign.Center
            Alignment.End -> androidx.compose.ui.text.style.TextAlign.End
            else -> androidx.compose.ui.text.style.TextAlign.Start
        },
        maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
    )
}

@Preview(showBackground = true, name = "UI Avec Appareils Mockés", widthDp = 420)
@Composable
fun BluetoothUiScreenPreviewWithDevices() {
    val mockDevicesForPreview = remember {
        listOf(
            com.reuniware.bthexplorer.DeviceInfo("00:11:22:33:44:55", "Preview Alpha", -55, 2.5, System.currentTimeMillis() - 10000),
            com.reuniware.bthexplorer.DeviceInfo("AA:BB:CC:DD:EE:FF", "Preview Beta Long Name", -78, 12.1, System.currentTimeMillis() - 5000),
            com.reuniware.bthexplorer.DeviceInfo("A1:B2:C3:D4:E5:F6", null, -60, 4.0, System.currentTimeMillis())
        )
    }
    MaterialTheme {
        BluetoothUiScreen(previewDevices = mockDevicesForPreview)
    }
}

@Preview(showBackground = true, name = "UI Sans Appareils Mockés", widthDp = 420)
@Composable
fun BluetoothUiScreenPreviewNoDevices() {
    MaterialTheme {
        BluetoothUiScreen(previewDevices = emptyList()) // Test avec une liste vide
    }
}

@Preview(showBackground = true, name = "UI (Mode Réel - Vide au début)", widthDp = 420)
@Composable
fun BluetoothUiScreenPreviewRealEmpty() {
    // Cette preview simulera le comportement initial de l'app réelle où la liste du service est vide.
    // Pour que cela fonctionne, BluetoothScanningService.discoveredDevicesList doit avoir une valeur initiale
    // (par ex. _discoveredDevicesList = MutableStateFlow(emptyList()) dans le service)
    MaterialTheme {
        BluetoothUiScreen(previewDevices = null) // Ne passe pas de liste, donc utilise le service.
    }
}