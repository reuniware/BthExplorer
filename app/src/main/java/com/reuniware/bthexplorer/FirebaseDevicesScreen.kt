package com.reuniware.bthexplorer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirebaseDevicesScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val firestore = FirebaseFirestore.getInstance()
    var devices by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isDeleting by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    
    var excludeUnknown by remember { mutableStateOf(false) }
    var onlyWithServices by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    
    // État pour l'appareil sélectionné à afficher en détail
    var selectedDeviceDetail by remember { mutableStateOf<Map<String, Any>?>(null) }

    DisposableEffect(Unit) {
        val listener = firestore.collection("detected_devices")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    error.printStackTrace()
                    if (error.message?.contains("QUOTA_EXCEEDED", true) == true || 
                        error.message?.contains("RESOURCE_EXHAUSTED", true) == true) {
                        errorMessage = "Quota Firebase dépassé (limite gratuite atteinte)."
                    } else {
                        errorMessage = "Erreur de connexion à Firebase."
                    }
                    isLoading = false
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    // On récupère les données et on injecte l'ID du document si l'adresse est manquante
                    devices = snapshot.documents.map { doc ->
                        val data = doc.data?.toMutableMap() ?: mutableMapOf()
                        if (!data.containsKey("address")) {
                            data["address"] = doc.id
                        }
                        data
                    }.sortedByDescending { 
                        when (val ts = it["timestamp"]) {
                            is Long -> ts
                            is Timestamp -> ts.seconds * 1000
                            is Double -> ts.toLong()
                            else -> 0L
                        }
                    }
                }
                isLoading = false
            }
        
        onDispose {
            listener.remove()
        }
    }

    val filteredDevices = remember(devices, searchQuery, excludeUnknown, onlyWithServices) {
        devices.filter { device ->
            val name = (device["name"] as? String) ?: ""
            val address = (device["address"] as? String) ?: ""
            val services = (device["services"] as? List<*>) ?: emptyList<Any>()
            
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                name.lowercase().contains(searchQuery.lowercase()) || 
                address.lowercase().contains(searchQuery.lowercase())
            }
            
            val isKnown = name.isNotBlank() && 
                           !name.equals("Unknown Device", ignoreCase = true) && 
                           !name.equals("Inconnu", ignoreCase = true)
            
            val hasServices = services.isNotEmpty()
            
            matchesSearch && (!excludeUnknown || isKnown) && (!onlyWithServices || hasServices)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appareils sur Firebase") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (devices.isNotEmpty()) {
                        IconButton(onClick = { showDeleteConfirmation = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Supprimer tout", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Barre de recherche
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    placeholder = { Text("Rechercher par nom ou adresse...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = excludeUnknown,
                        onClick = { excludeUnknown = !excludeUnknown },
                        label = { Text("Masquer inconnus") },
                        leadingIcon = if (excludeUnknown) {
                            { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                    
                    FilterChip(
                        selected = onlyWithServices,
                        onClick = { onlyWithServices = !onlyWithServices },
                        label = { Text("Avec services") },
                        leadingIcon = if (onlyWithServices) {
                            { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }

                if (isLoading) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (errorMessage != null) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                        items(filteredDevices) { device ->
                            DeviceFirebaseItem(
                                device = device,
                                onClick = { 
                                    selectedDeviceDetail = device 
                                }
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        }
                        if (filteredDevices.isEmpty() && !isLoading) {
                            item {
                                Text(
                                    "Aucun appareil trouvé.",
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            if (isDeleting) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Suppression en cours...")
                        }
                    }
                }
            }

            if (showDeleteConfirmation) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirmation = false },
                    title = { Text("Confirmer la suppression") },
                    text = { Text("Voulez-vous vraiment supprimer TOUS les appareils de la base de données distante ? Cette action est irréversible.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteConfirmation = false
                                isDeleting = true
                                firestore.collection("detected_devices").get()
                                    .addOnSuccessListener { snapshot ->
                                        val batch = firestore.batch()
                                        snapshot.documents.forEach { batch.delete(it.reference) }
                                        batch.commit().addOnCompleteListener {
                                            // 1. Vider Firebase (fait via batch.commit)
                                            // 2. Vider la base locale SQL
                                            BluetoothScanningService.requestDatabaseClear(context)
                                            // 3. Vider la liste UI en mémoire
                                            BluetoothScanningService.clearDeviceList()

                                            isDeleting = false
                                        }
                                    }
                                    .addOnFailureListener {
                                        isDeleting = false
                                    }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Supprimer tout")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirmation = false }) {
                            Text("Annuler")
                        }
                    }
                )
            }

            // Affichage des détails de l'appareil dans un Dialog si un appareil est sélectionné
            selectedDeviceDetail?.let { device ->
                DeviceDetailDialog(
                    device = device,
                    onDismiss = { selectedDeviceDetail = null }
                )
            }
        }
    }
}

@Composable
fun DeviceDetailDialog(device: Map<String, Any>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            DeviceDetailContent(device = device, onDismiss = onDismiss)
        }
    }
}

@Composable
fun DeviceDetailContent(device: Map<String, Any>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lat = when (val l = device["latitude"]) {
        is Double -> l
        is Float -> l.toDouble()
        is Long -> l.toDouble()
        is Int -> l.toDouble()
        else -> null
    }
    val lon = when (val l = device["longitude"]) {
        is Double -> l
        is Float -> l.toDouble()
        is Long -> l.toDouble()
        is Int -> l.toDouble()
        else -> null
    }
    val name = device["name"]?.toString() ?: "Inconnu"
    val address = device["address"]?.toString() ?: "Inconnue"
    val services = (device["services"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()

    Column {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Fermer")
            }
        }

        Column(modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())) {
            Text(text = name, style = MaterialTheme.typography.headlineSmall)
            Text(text = "Adresse: $address", style = MaterialTheme.typography.bodyMedium)
            
            if (lat != null && lon != null) {
                OutlinedButton(
                    onClick = {
                        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon($name)")
                        val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                        context.startActivity(mapIntent)
                    },
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Voir sur la carte", style = MaterialTheme.typography.labelLarge)
                }
                Text(text = "Coordonnées: $lat, $lon", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            Text(text = "Services découverts:", style = MaterialTheme.typography.titleMedium)
            if (services.isEmpty()) {
                Text(
                    text = "Aucun service enregistré. Connectez-vous à l'appareil pour les découvrir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else {
                services.forEach { uuid ->
                    val serviceName = getBluetoothServiceName(uuid)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = serviceName,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = uuid,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Traduit un UUID de service Bluetooth en nom lisible
 */
fun getBluetoothServiceName(uuid: String): String {
    val shortUuid = if (uuid.length >= 8) uuid.substring(4, 8).lowercase() else uuid
    return when (shortUuid) {
        "1800" -> "Accès Générique"
        "1801" -> "Attribut Générique"
        "180a" -> "Information Appareil"
        "180d" -> "Fréquence Cardiaque"
        "180f" -> "Niveau Batterie"
        "1812" -> "Interface Humaine (HID)"
        "1805" -> "Heure Actuelle"
        "1803" -> "Alerte de Proximité"
        "1802" -> "Alerte Immédiate"
        "1804" -> "Niveau de Puissance"
        "1809" -> "Thermomètre"
        "1806" -> "Données de Référence"
        "1810" -> "Pression Artérielle"
        "1811" -> "Alerte Notification"
        "1819" -> "Localisation et Navigation"
        "181a" -> "Données Environnementales"
        "181b" -> "Composition Corporelle"
        "181c" -> "Utilisateur"
        "181d" -> "Poids"
        "181e" -> "Pression d'Oxygène"
        "181f" -> "Activité Physique"
        "1808" -> "Glucose"
        "feaf" -> "Service Jabra (Propriétaire)"
        "fe9f" -> "Google Fast Pair"
        "fd5a" -> "Identification Produit"
        else -> "Service Personnalisé"
    }
}

@Composable
fun DeviceFirebaseItem(device: Map<String, Any>, onClick: () -> Unit) {
    val name = device["name"]?.toString() ?: "Inconnu"
    val address = device["address"]?.toString() ?: "Inconnue"
    
    val rssi = when (val r = device["rssi"]) {
        is Long -> r
        is Double -> r.toLong()
        is Int -> r.toLong()
        else -> 0L
    }
    
    val timestamp = when (val ts = device["timestamp"]) {
        is Long -> ts
        is Timestamp -> ts.seconds * 1000
        is Double -> ts.toLong()
        else -> 0L
    }
    
    val detectionCount = when (val c = device["detectionCount"]) {
        is Long -> c
        is Int -> c.toLong()
        is Double -> c.toLong()
        else -> 1L
    }

    val lat = when (val l = device["latitude"]) {
        is Double -> l
        is Float -> l.toDouble()
        is Long -> l.toDouble()
        else -> null
    }
    val lon = when (val l = device["longitude"]) {
        is Double -> l
        is Float -> l.toDouble()
        is Long -> l.toDouble()
        else -> null
    }

    val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    Column(modifier = Modifier
        .fillMaxWidth()
        .clickable { onClick() }
        .padding(vertical = 4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "Nom: $name", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Text(text = "Détéctions: $detectionCount", modifier = Modifier.padding(4.dp))
            }
        }
        Text(text = "Adresse: $address", style = MaterialTheme.typography.bodyMedium)
        Text(text = "RSSI: $rssi dBm", style = MaterialTheme.typography.bodySmall)
        Text(text = "Date: $date", style = MaterialTheme.typography.bodySmall)
        if (lat != null && lon != null) {
            Text(text = "Position: $lat, $lon", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(text = "(Cliquez pour voir les détails)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        } else {
            Text(text = "Position: Inconnue", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Text(text = "(Cliquez pour voir les détails)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
