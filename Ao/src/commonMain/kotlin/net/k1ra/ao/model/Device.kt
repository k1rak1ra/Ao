package net.k1ra.ao.model

data class Device(
    val address: String,
    var name: String,
    var isConnected: Boolean,
    var rssi: Int,
    var serviceUUIDs: List<String>
)