package net.k1ra.ao

import net.k1ra.ao.model.ConnectionStatusCallback
import net.k1ra.ao.model.Device
import net.k1ra.ao.model.Service
import net.k1ra.ao.model.connectionState.ConnectionState

object BLEManager {
    val scanResults = BluetoothPlatformSpecificAbstraction.scanResults

    private var isScanning = false

    suspend fun hasPermissions() : Boolean {
        return BluetoothPlatformSpecificAbstraction.hasPermissions()
    }

    suspend fun requestPermissions() : Boolean {
        return BluetoothPlatformSpecificAbstraction.requestPermissions()
    }

    suspend fun isAdapterEnabled() : Boolean {
        return BluetoothPlatformSpecificAbstraction.isAdapterEnabled()
    }

    suspend fun turnOnAdapter() : Boolean {
        return BluetoothPlatformSpecificAbstraction.turnOnAdapter()
    }

    fun isScanning() : Boolean {
        return isScanning
    }

    fun startScan() {
        isScanning = true
        BluetoothPlatformSpecificAbstraction.startScan()
    }

    fun stopScan() {
        isScanning = false
        BluetoothPlatformSpecificAbstraction.stopScan()
    }

    suspend fun connectDevice(device: Device, callbacks: ConnectionStatusCallback? = null) : ConnectionState {
        return BluetoothPlatformSpecificAbstraction.connectDevice(device, callbacks)
    }

    suspend fun disconnectDevice(device: Device) {
        BluetoothPlatformSpecificAbstraction.disconnectDevice(device)
    }

    suspend fun getServices(device: Device) : List<Service>? {
        return BluetoothPlatformSpecificAbstraction.getServices(device)
    }
}