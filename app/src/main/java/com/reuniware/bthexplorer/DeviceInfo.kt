package com.reuniware.bthexplorer // Ou le package approprié où vous stockez vos modèles

data class DeviceInfo(
    val address: String,
    val name: String?,
    val rssi: Int,
    val estimatedDistance: Double?, // Vous l'aviez dans BluetoothUiScreen, assurez-vous qu'il est cohérent
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val serialNumber: String? = null,
    val modelName: String? = null,
    val serviceUuids: List<String> = emptyList()
)