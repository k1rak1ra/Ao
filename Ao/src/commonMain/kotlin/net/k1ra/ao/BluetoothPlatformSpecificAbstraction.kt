package net.k1ra.ao

import net.k1ra.ao.helpers.StateFlowClass
import net.k1ra.ao.model.ConnectionStatusCallback
import net.k1ra.ao.model.Device
import net.k1ra.ao.model.Service
import net.k1ra.ao.model.connectionState.ConnectionState

internal expect object BluetoothPlatformSpecificAbstraction {
    val scanResults: StateFlowClass<List<Device>>

    suspend fun isAdapterEnabled() : Boolean

    suspend fun turnOnAdapter() : Boolean

    suspend fun hasPermissions() : Boolean

    suspend fun requestPermissions() : Boolean

    fun startScan()

    fun stopScan()

    suspend fun connectDevice(device: Device, callbacks: ConnectionStatusCallback?) : ConnectionState

    suspend fun getServices(device: Device) : List<Service>?

    suspend fun disconnectDevice(device: Device)
}