package com.reuniware.bthexplorer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirebaseDevicesScreen(onNavigateBack: () -> Unit) {
    val firestore = FirebaseFirestore.getInstance()
    var devices by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val result = firestore.collection("detected_devices")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(50)
                .get()
                .await()
            devices = result.documents.map { it.data ?: emptyMap() }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appareils sur Firebase") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        // Icone de retour (vous pouvez utiliser l'icône de retour standard)
                        Text("<") 
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                items(devices) { device ->
                    DeviceFirebaseItem(device)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
fun DeviceFirebaseItem(device: Map<String, Any>) {
    val name = device["name"] as? String ?: "Inconnu"
    val address = device["address"] as? String ?: "Inconnue"
    val rssi = device["rssi"] as? Long ?: 0L
    val timestamp = device["timestamp"] as? Long ?: 0L
    val lat = device["latitude"] as? Double
    val lon = device["longitude"] as? Double

    val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    Column {
        Text(text = "Nom: $name", style = MaterialTheme.typography.titleMedium)
        Text(text = "Adresse: $address", style = MaterialTheme.typography.bodyMedium)
        Text(text = "RSSI: $rssi dBm", style = MaterialTheme.typography.bodySmall)
        Text(text = "Date: $date", style = MaterialTheme.typography.bodySmall)
        if (lat != null && lon != null) {
            Text(text = "Position: $lat, $lon", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        } else {
            Text(text = "Position: Inconnue", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
