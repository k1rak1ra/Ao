package net.k1ra.ao

import net.k1ra.ao.helpers.StateFlowClass
import net.k1ra.ao.model.ConnectionStatusCallback
import net.k1ra.ao.model.Device
import net.k1ra.ao.model.Service
import net.k1ra.ao.model.connectionState.ConnectionState

internal actual object BluetoothPlatformSpecificAbstraction {
    actual val scanResults: StateFlowClass<List<Device>>
        get() = throw UnsupportedOperationException("Feature not supported on desktop yet")


    actual suspend fun isAdapterEnabled() : Boolean {
        throw UnsupportedOperationException("Feature not supported on desktop yet")
    }

    actual suspend fun turnOnAdapter() : Boolean {
        throw UnsupportedOperationException("Feature not supported on desktop yet")
    }

    actual suspend fun hasPermissions() : Boolean {
        throw UnsupportedOperationException("Feature not supported on desktop yet")
    }

    actual suspend fun requestPermissions() : Boolean {
        throw UnsupportedOperationException("Feature not supported on desktop yet")
    }

    actual fun startScan() {
        throw UnsupportedOperationException("Feature not supported on desktop yet")
    }

    actual fun stopScan() {
        throw UnsupportedOperationException("Feature not supported on desktop yet")
    }

    actual suspend fun connectDevice(device: Device, callbacks: ConnectionStatusCallback?): ConnectionState {
        throw UnsupportedOperationException("Feature not supported on desktop yet")
    }

    actual suspend fun getServices(device: Device) : List<Service>? {
        throw UnsupportedOperationException("Feature not supported on desktop yet")
    }

    actual suspend fun disconnectDevice(device: Device) {
        throw UnsupportedOperationException("Feature not supported on desktop yet")
    }
}