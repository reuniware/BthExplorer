package com.reuniware.bthexplorer

import android.util.Log
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.*

// Ajout des enum manquants
enum class SortCriterion {
    NAME, ADDRESS, RSSI, DISTANCE, LAST_SEEN
}

enum class SortOrder {
    ASCENDING, DESCENDING
}

// Définition de la data class DeviceInfo si elle n'existe pas
data class DeviceInfo(
    val address: String,
    val name: String?,
    val rssi: Int,
    val estimatedDistance: Double?,
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double? = null, // NOUVEAU CHAMP
    val longitude: Double? = null // NOUVEAU CHAMP
)


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothUiScreen(
    previewDevices: List<DeviceInfo>? = null
) {
    val context = LocalContext.current
    val devicesState: State<List<DeviceInfo>> = if (previewDevices == null) {
        BluetoothScanningService.discoveredDevicesList.collectAsStateWithLifecycle()
    } else {
        remember { mutableStateOf(previewDevices) }
    }
    val deviceList = devicesState.value
    val deviceCount = deviceList.size

    var sortCriterion by remember { mutableStateOf(SortCriterion.LAST_SEEN) }
    var sortOrder by remember { mutableStateOf(SortOrder.DESCENDING) }

    if (previewDevices == null && deviceCount > 0) {
        LaunchedEffect(deviceCount) {
            Toast.makeText(context, "Appareils détectés: $deviceCount", Toast.LENGTH_SHORT).show()
        }
    }

    val sortedDevices = remember(deviceList, sortCriterion, sortOrder) {
        val comparator: Comparator<DeviceInfo> = when (sortCriterion) {
            SortCriterion.NAME -> compareBy { it.name?.lowercase() }
            SortCriterion.ADDRESS -> compareBy { it.address }
            SortCriterion.RSSI -> compareBy { it.rssi }
            SortCriterion.DISTANCE -> compareBy(nullsLast()) { it.estimatedDistance }
            SortCriterion.LAST_SEEN -> compareBy { it.timestamp }
        }
        if (sortOrder == SortOrder.ASCENDING) {
            deviceList.sortedWith(comparator)
        } else {
            deviceList.sortedWith(comparator.reversed())
        }
    }

    Scaffold(
        topBar = {
            AppBar(deviceCount, previewDevices)
        }
    ) { padding ->
        DeviceListContent(
            modifier = Modifier.padding(padding),
            devices = sortedDevices,
            sortCriterion = sortCriterion,
            sortOrder = sortOrder,
            onSortChange = { criterion, order ->
                sortCriterion = criterion
                sortOrder = order
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppBar(deviceCount: Int, previewDevices: List<DeviceInfo>?) {
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
                        color = Color.Red
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ),
        actions = {
            if (previewDevices == null) {
                Button(onClick = { BluetoothScanningService.clearDeviceList() }) {
                    Text("Vider")
                }
            }
        }
    )
}

@Composable
private fun DeviceListContent(
    modifier: Modifier = Modifier,
    devices: List<DeviceInfo>,
    sortCriterion: SortCriterion,
    sortOrder: SortOrder,
    onSortChange: (SortCriterion, SortOrder) -> Unit
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (devices.isEmpty()) {
            EmptyState()
        } else {
            ColumnHeaders(
                sortCriterion = sortCriterion,
                sortOrder = sortOrder,
                onSortChange = onSortChange
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            DeviceItemsList(devices = devices)
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
            text = "Aucun appareil détecté",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ColumnHeaders(
    sortCriterion: SortCriterion,
    sortOrder: SortOrder,
    onSortChange: (SortCriterion, SortOrder) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        SortableHeader(
            text = "Nom",
            weight = 2.5f,
            criterion = SortCriterion.NAME,
            currentCriterion = sortCriterion,
            currentOrder = sortOrder,
            onClick = { onSortChange(SortCriterion.NAME, toggleOrder(sortCriterion, SortCriterion.NAME, sortOrder)) }
        )
        SortableHeader(
            text = "Adresse",
            weight = 2.0f,
            criterion = SortCriterion.ADDRESS,
            currentCriterion = sortCriterion,
            currentOrder = sortOrder,
            onClick = { onSortChange(SortCriterion.ADDRESS, toggleOrder(sortCriterion, SortCriterion.ADDRESS, sortOrder)) }
        )
        SortableHeader(
            text = "RSSI",
            weight = 1.2f,
            alignment = Alignment.CenterHorizontally,
            criterion = SortCriterion.RSSI,
            currentCriterion = sortCriterion,
            currentOrder = sortOrder,
            onClick = { onSortChange(SortCriterion.RSSI, toggleOrder(sortCriterion, SortCriterion.RSSI, sortOrder)) }
        )
        SortableHeader(
            text = "Dist.",
            weight = 1.3f,
            alignment = Alignment.CenterHorizontally,
            criterion = SortCriterion.DISTANCE,
            currentCriterion = sortCriterion,
            currentOrder = sortOrder,
            onClick = { onSortChange(SortCriterion.DISTANCE, toggleOrder(sortCriterion, SortCriterion.DISTANCE, sortOrder)) }
        )
        SortableHeader(
            text = "Vu",
            weight = 2.2f,
            alignment = Alignment.CenterHorizontally,
            criterion = SortCriterion.LAST_SEEN,
            currentCriterion = sortCriterion,
            currentOrder = sortOrder,
            onClick = { onSortChange(SortCriterion.LAST_SEEN, toggleOrder(sortCriterion, SortCriterion.LAST_SEEN, sortOrder)) }
        )
    }
}

private fun toggleOrder(
    currentCriterion: SortCriterion,
    clickedCriterion: SortCriterion,
    currentOrder: SortOrder
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
    currentOrder: SortOrder,
    alignment: Alignment.Horizontal = Alignment.Start,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .weight(weight)
            .clickable(onClick = onClick)
            .padding(4.dp),
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
                Alignment.CenterHorizontally -> androidx.compose.ui.text.style.TextAlign.Center
                Alignment.End -> androidx.compose.ui.text.style.TextAlign.End
                else -> androidx.compose.ui.text.style.TextAlign.Start
            }
        )
        if (currentCriterion == criterion) {
            Icon(
                imageVector = if (currentOrder == SortOrder.ASCENDING) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = "Sort order",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun DeviceItemsList(devices: List<DeviceInfo>) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = devices, key = { it.address }) { device ->
            DeviceRow(device = device)
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DeviceCell(text = device.name ?: "N/A", weight = 2.5f)
        DeviceCell(text = device.address, weight = 2.0f, fontSize = 12.sp)
        DeviceCell(text = "${device.rssi}", weight = 1.2f, alignment = Alignment.CenterHorizontally)
        DeviceCell(
            text = device.estimatedDistance?.let { "%.1fm".format(Locale.US, it) } ?: "-",
            weight = 1.3f,
            alignment = Alignment.CenterHorizontally
        )
        DeviceCell(
            text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(device.timestamp)),
            weight = 2.2f,
            fontSize = 12.sp,
            alignment = Alignment.CenterHorizontally
        )
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
            .padding(horizontal = 4.dp),
        fontSize = if (fontSize != TextUnit.Unspecified) fontSize else MaterialTheme.typography.bodyMedium.fontSize,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = when (alignment) {
            Alignment.CenterHorizontally -> androidx.compose.ui.text.style.TextAlign.Center
            Alignment.End -> androidx.compose.ui.text.style.TextAlign.End
            else -> androidx.compose.ui.text.style.TextAlign.Start
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Preview(showBackground = true, widthDp = 420)
@Composable
fun BluetoothUiScreenPreviewWithDevices() {
    MaterialTheme {
        BluetoothUiScreen(
            previewDevices = listOf(
                DeviceInfo("00:11:22:33:44:55", "Preview Alpha", -55, 2.5),
                DeviceInfo("AA:BB:CC:DD:EE:FF", "Preview Beta", -78, 12.1),
                DeviceInfo("A1:B2:C3:D4:E5:F6", null, -60, 4.0)
            )
        )
    }
}

@Preview(showBackground = true, widthDp = 420)
@Composable
fun BluetoothUiScreenPreviewEmpty() {
    MaterialTheme {
        BluetoothUiScreen(previewDevices = emptyList())
    }
}