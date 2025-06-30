package com.reuniware.bthexplorer // Assurez-vous que le package est correct

import android.Manifest // Ajouté pour la vérification de permission locale
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager // Ajouté pour la vérification de permission locale
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Error // Ajouté pour l'état d'erreur
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.core.content.ContextCompat // Ajouté pour la vérification de permission locale
import java.text.SimpleDateFormat
import java.util.*
import java.util.Comparator // For Comparator interface

// --- Reprise des éléments de MainActivity.kt pour la complétude du fichier autonome ---
// (Dans un vrai projet, ces classes/enums seraient dans leurs propres fichiers ou partagés)

// Assurez-vous que ScannedBluetoothDevice est accessible (ex: défini dans MainActivity.kt ou son propre fichier)
// data class ScannedBluetoothDevice(
//    val address: String,
//    val name: String?,
//    val rssi: Int,
//    val timestamp: Long = System.currentTimeMillis(),
//    @Transient var rawDevice: BluetoothDevice? = null
// )

// Assurez-vous que ConnectionStatus est accessible
// sealed class ConnectionStatus {
//    data class Connecting(val deviceName: String) : ConnectionStatus()
//    data class Connected(val deviceName: String, val servicesDiscovered: Boolean = false) : ConnectionStatus()
//    object Disconnected : ConnectionStatus()
//    data class Error(val message: String) : ConnectionStatus()
// }

// Assurez-vous que BluetoothConnectionViewModel est accessible
// class BluetoothConnectionViewModel(private val context: Context) : ViewModel() { ... }

// Assurez-vous que hasBluetoothConnectPermission est accessible
// fun hasBluetoothConnectPermission(context: Context): Boolean { ... }

// --- Fin de la reprise ---

// Vos enums locaux
enum class SortCriterion {
    NAME,
    ADDRESS,
    RSSI,
    LAST_SEEN
}

enum class SortOrder {
    ASCENDING, DESCENDING
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothUiScreen(
    devices: List<ScannedBluetoothDevice>,
    connectionViewModel: BluetoothConnectionViewModel,
    previewDevices: List<ScannedBluetoothDevice>? = null
) {
    val context = LocalContext.current
    val deviceList = previewDevices ?: devices
    val deviceCount = deviceList.size

    var sortCriterion by remember { mutableStateOf(SortCriterion.LAST_SEEN) }
    var sortOrderState by remember { mutableStateOf(SortOrder.DESCENDING) } // Utilisant votre enum local

    val currentConnectionStatus by rememberUpdatedState(connectionViewModel.connectionState)

    val sortedDevices = remember(deviceList, sortCriterion, sortOrderState) {
        val comparator: Comparator<ScannedBluetoothDevice> = when (sortCriterion) {
            SortCriterion.NAME -> compareBy<ScannedBluetoothDevice, String?>(nullsLast()) { it.name?.lowercase() }
            SortCriterion.ADDRESS -> compareBy { it.address }
            SortCriterion.RSSI -> compareBy { it.rssi }
            SortCriterion.LAST_SEEN -> compareBy { it.timestamp }
        }
        if (sortOrderState == SortOrder.ASCENDING) {
            deviceList.sortedWith(comparator)
        } else {
            deviceList.sortedWith(comparator.reversed())
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                deviceCount = deviceCount,
                connectionStatus = currentConnectionStatus,
                onDisconnectClick = {
                    if (hasBluetoothConnectPermission(context)) {
                        connectionViewModel.disconnect()
                    } else {
                        Toast.makeText(context, "Permission BLUETOOTH_CONNECT requise pour déconnecter.", Toast.LENGTH_LONG).show()
                    }
                },
                onClearListClick = {
                    if (previewDevices == null) {
                        BluetoothScanningService.clearDeviceList() // Assurez-vous que cette méthode existe
                        Toast.makeText(context, "Liste des appareils vidée.", Toast.LENGTH_SHORT).show()
                    }
                },
                isPreview = previewDevices != null
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            ConnectionStatusDisplay(
                status = currentConnectionStatus,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            DeviceListContent(
                modifier = Modifier.weight(1f),
                devices = sortedDevices,
                sortCriterion = sortCriterion,
                sortOrder = sortOrderState, // Passant votre enum local
                onSortChange = { criterion, order ->
                    sortCriterion = criterion
                    sortOrderState = order
                },
                onDeviceClick = { deviceToConnect ->
                    val currentTargetDeviceName = when (val status = currentConnectionStatus) {
                        is ConnectionStatus.Connected -> status.deviceName
                        is ConnectionStatus.Connecting -> status.deviceName
                        else -> null
                    }
                    val clickedDeviceIdentifier = deviceToConnect.name ?: deviceToConnect.address

                    if (currentTargetDeviceName == clickedDeviceIdentifier && currentConnectionStatus !is ConnectionStatus.Disconnected) {
                        Toast.makeText(context, "Déjà en interaction avec ${deviceToConnect.name ?: deviceToConnect.address}", Toast.LENGTH_SHORT).show()
                        return@DeviceListContent
                    }

                    if (hasBluetoothConnectPermission(context)) {
                        Log.d("BluetoothUiScreen", "Tentative de connexion à ${deviceToConnect.address}")
                        connectionViewModel.connectToDevice(deviceToConnect.address)
                    } else {
                        Toast.makeText(context, "Permission BLUETOOTH_CONNECT requise.", Toast.LENGTH_LONG).show()
                    }
                },
                currentConnectionStatus = currentConnectionStatus,
                connectedDeviceAddress = (currentConnectionStatus as? ConnectionStatus.Connected)?.deviceName?.let { name ->
                    devices.find { (it.name ?: it.address) == name }?.address
                } ?: (currentConnectionStatus as? ConnectionStatus.Connecting)?.deviceName?.let { name ->
                    devices.find { (it.name ?: it.address) == name }?.address
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBar(
    deviceCount: Int,
    connectionStatus: ConnectionStatus,
    onDisconnectClick: () -> Unit,
    onClearListClick: () -> Unit,
    isPreview: Boolean
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Appareils Détectés",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (deviceCount > 0) {
                    Text(
                        text = " ($deviceCount)",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (connectionStatus is ConnectionStatus.Connected) MaterialTheme.colorScheme.primary else Color.Red
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        actions = {
            if (connectionStatus is ConnectionStatus.Connected || connectionStatus is ConnectionStatus.Connecting) {
                IconButton(onClick = onDisconnectClick) {
                    Icon(Icons.Filled.BluetoothDisabled, contentDescription = "Déconnecter")
                }
            }
            if (!isPreview) {
                IconButton(onClick = onClearListClick) {
                    Icon(Icons.Filled.Clear, contentDescription = "Vider la liste")
                }
            }
        }
    )
}

@Composable
fun ConnectionStatusDisplay(status: ConnectionStatus, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        val text: String
        val icon: androidx.compose.ui.graphics.vector.ImageVector?
        val color: Color
        var showProgress = false

        when (status) {
            is ConnectionStatus.Connecting -> {
                text = "Connexion à ${status.deviceName}..."
                icon = Icons.Filled.BluetoothSearching
                color = MaterialTheme.colorScheme.primary
                showProgress = true
            }
            is ConnectionStatus.Connected -> {
                text = "Connecté à ${status.deviceName}" + if (status.servicesDiscovered) " (Services OK)" else ""
                icon = Icons.Filled.BluetoothConnected
                color = MaterialTheme.colorScheme.primary
            }
            is ConnectionStatus.Disconnected -> {
                text = "Aucun appareil connecté."
                icon = null
                color = MaterialTheme.colorScheme.onSurfaceVariant
            }
            is ConnectionStatus.Error -> {
                text = "Erreur: ${status.message}"
                icon = Icons.Filled.Error
                color = MaterialTheme.colorScheme.error
            }
        }

        if (showProgress) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = color)
            Spacer(modifier = Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(imageVector = icon, contentDescription = "Status de connexion", tint = color)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DeviceListContent(
    modifier: Modifier = Modifier,
    devices: List<ScannedBluetoothDevice>,
    sortCriterion: SortCriterion,
    sortOrder: SortOrder, // Utilisant votre enum local
    onSortChange: (SortCriterion, SortOrder) -> Unit,
    onDeviceClick: (ScannedBluetoothDevice) -> Unit,
    currentConnectionStatus: ConnectionStatus,
    connectedDeviceAddress: String?
) {
    Column(modifier = modifier) {
        if (devices.isEmpty() && currentConnectionStatus is ConnectionStatus.Disconnected) {
            EmptyState()
        } else {
            ColumnHeaders(
                sortCriterion = sortCriterion,
                sortOrder = sortOrder, // Passant votre enum local
                onSortChange = onSortChange
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            DeviceItemsList(
                devices = devices,
                onDeviceClick = onDeviceClick,
                connectedDeviceAddress = connectedDeviceAddress,
                currentConnectionStatus = currentConnectionStatus
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Aucun appareil détecté ou service non démarré.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ColumnHeaders(
    sortCriterion: SortCriterion,
    sortOrder: SortOrder, // Utilisant votre enum local
    onSortChange: (SortCriterion, SortOrder) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SortableHeader("Nom", 2.5f, SortCriterion.NAME, sortCriterion, sortOrder, onClick = { onSortChange(SortCriterion.NAME, toggleOrder(sortCriterion, SortCriterion.NAME, sortOrder)) })
        SortableHeader("Adresse", 2.0f, SortCriterion.ADDRESS, sortCriterion, sortOrder, onClick = { onSortChange(SortCriterion.ADDRESS, toggleOrder(sortCriterion, SortCriterion.ADDRESS, sortOrder)) })
        SortableHeader("RSSI", 1.2f, SortCriterion.RSSI, sortCriterion, sortOrder, Alignment.CenterHorizontally, onClick = { onSortChange(SortCriterion.RSSI, toggleOrder(sortCriterion, SortCriterion.RSSI, sortOrder)) })
        SortableHeader("Vu", 2.2f, SortCriterion.LAST_SEEN, sortCriterion, sortOrder, Alignment.CenterHorizontally, onClick = { onSortChange(SortCriterion.LAST_SEEN, toggleOrder(sortCriterion, SortCriterion.LAST_SEEN, sortOrder)) })
    }
}

private fun toggleOrder(
    currentCriterion: SortCriterion,
    clickedCriterion: SortCriterion,
    currentOrder: SortOrder // Utilisant votre enum local
): SortOrder {
    return if (currentCriterion == clickedCriterion) {
        if (currentOrder == SortOrder.ASCENDING) SortOrder.DESCENDING else SortOrder.ASCENDING
    } else {
        when (clickedCriterion) {
            SortCriterion.RSSI, SortCriterion.LAST_SEEN -> SortOrder.DESCENDING
            else -> SortOrder.ASCENDING
        }
    }
}

@Composable
private fun RowScope.SortableHeader(
    text: String,
    weight: Float,
    criterion: SortCriterion,
    currentCriterion: SortCriterion,
    currentOrder: SortOrder, // Utilisant votre enum local
    alignment: Alignment.Horizontal = Alignment.Start,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .weight(weight)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
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
            textAlign = when (alignment) {
                Alignment.CenterHorizontally -> TextAlign.Center
                Alignment.End -> TextAlign.End
                else -> TextAlign.Start
            }
        )
        if (currentCriterion == criterion) {
            Icon(
                imageVector = if (currentOrder == SortOrder.ASCENDING) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = "Sort order",
                modifier = Modifier.size(16.dp).padding(start = 2.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun DeviceItemsList(
    devices: List<ScannedBluetoothDevice>,
    onDeviceClick: (ScannedBluetoothDevice) -> Unit,
    connectedDeviceAddress: String?,
    currentConnectionStatus: ConnectionStatus
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = devices, key = { it.address }) { device ->
            val isConnected = device.address == connectedDeviceAddress && currentConnectionStatus is ConnectionStatus.Connected
            val isConnecting = device.address == connectedDeviceAddress && currentConnectionStatus is ConnectionStatus.Connecting

            DeviceRow(
                device = device,
                onClick = { onDeviceClick(device) },
                isConnected = isConnected,
                isConnecting = isConnecting
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun DeviceRow(
    device: ScannedBluetoothDevice,
    onClick: () -> Unit,
    isConnected: Boolean,
    isConnecting: Boolean
) {
    val backgroundColor = when {
        isConnected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        isConnecting -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DeviceCell(text = device.name ?: "N/A", weight = 2.5f)
        DeviceCell(text = device.address, weight = 2.0f, fontSize = 12.sp)
        DeviceCell(text = "${device.rssi}", weight = 1.2f, alignment = Alignment.CenterHorizontally)
        DeviceCell(
            text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(device.timestamp)),
            weight = 2.2f,
            fontSize = 12.sp,
            alignment = Alignment.CenterHorizontally
        )
        if (isConnected) {
            Icon(Icons.Filled.BluetoothConnected, "Connecté", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp).size(18.dp))
        } else if (isConnecting) {
            CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp).size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun RowScope.DeviceCell(
    text: String,
    weight: Float,
    alignment: Alignment.Horizontal = Alignment.Start,
    fontSize: TextUnit = TextUnit.Unspecified
) {
    Text(
        text = text,
        modifier = Modifier
            .weight(weight)
            .padding(horizontal = 2.dp),
        fontSize = if (fontSize != TextUnit.Unspecified) fontSize else MaterialTheme.typography.bodyMedium.fontSize,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = when (alignment) {
            Alignment.CenterHorizontally -> TextAlign.Center
            Alignment.End -> TextAlign.End
            else -> TextAlign.Start
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

// --- Previews ---
// (Previews nécessitent une instance factice de BluetoothConnectionViewModel)
// Vous pouvez créer une classe PreviewBluetoothConnectionViewModel pour cela.

// class PreviewBluetoothConnectionViewModel(context: Context) : BluetoothConnectionViewModel(context) { /* ... */ }

@Preview(showBackground = true, widthDp = 400, heightDp = 200)
@Composable
fun BluetoothUiScreenPreview_Connecting() {
    val context = LocalContext.current
    val previewConnectionVM = remember { /* PreviewBluetoothConnectionViewModel(context) */ TestBluetoothConnectionViewModel(context) }
    previewConnectionVM.connectionState = ConnectionStatus.Connecting("Appareil Alpha")

    MaterialTheme {
        BluetoothUiScreen(
            devices = listOf(
                ScannedBluetoothDevice("00:11:22:33:44:55", "Appareil Alpha", -55, System.currentTimeMillis(), rawDevice = null),
                ScannedBluetoothDevice("AA:BB:CC:DD:EE:FF", "Appareil Beta", -78, System.currentTimeMillis() - 10000, rawDevice = null)
            ),
            connectionViewModel = previewConnectionVM,
            previewDevices = null
        )
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 300)
@Composable
fun BluetoothUiScreenPreview_Connected() {
    val context = LocalContext.current
    val previewConnectionVM = remember { /* PreviewBluetoothConnectionViewModel(context) */ TestBluetoothConnectionViewModel(context) }
    previewConnectionVM.connectionState = ConnectionStatus.Connected("Appareil Beta", servicesDiscovered = true)
    // Simuler le nom connecté dans le ViewModel de preview
    // previewConnectionVM.connectedDeviceName = "Appareil Beta" // Assurez-vous que votre ViewModel a cette propriété pour le preview

    MaterialTheme {
        BluetoothUiScreen(
            devices = listOf(
                ScannedBluetoothDevice("00:11:22:33:44:55", "Appareil Alpha", -55, System.currentTimeMillis(), rawDevice = null),
                ScannedBluetoothDevice("AA:BB:CC:DD:EE:FF", "Appareil Beta", -78, System.currentTimeMillis() - 10000, rawDevice = null)
            ),
            connectionViewModel = previewConnectionVM,
            previewDevices = null
        )
    }
}

@Preview(showBackground = true, widthDp = 400)
@Composable
fun BluetoothUiScreenPreview_Empty() {
    val context = LocalContext.current
    val previewConnectionVM = remember { /* PreviewBluetoothConnectionViewModel(context) */ TestBluetoothConnectionViewModel(context) }
    previewConnectionVM.connectionState = ConnectionStatus.Disconnected

    MaterialTheme {
        BluetoothUiScreen(
            devices = emptyList(),
            connectionViewModel = previewConnectionVM,
            previewDevices = null
        )
    }
}

// Classe factice pour les previews si vous n'avez pas de ViewModel de preview séparé
// et si BluetoothConnectionViewModel ne peut pas être instancié directement
// ou si vous voulez un comportement contrôlé dans les previews.
private class TestBluetoothConnectionViewModel(context: Context) : BluetoothConnectionViewModel(context) {
    // Surchargez les méthodes si nécessaire pour le comportement de preview
    override fun connectToDevice(deviceAddress: String) {
        Log.d("PreviewVM", "Preview connect to $deviceAddress")
        // Simuler une connexion pour le preview
        val deviceName = if (deviceAddress == "00:11:22:33:44:55") "Appareil Alpha" else "Appareil Beta"
        connectionState = ConnectionStatus.Connecting(deviceName)
        // Vous pouvez ajouter un délai et passer à Connected pour tester
    }

    override fun disconnect() {
        Log.d("PreviewVM", "Preview disconnect")
        connectionState = ConnectionStatus.Disconnected
    }
}