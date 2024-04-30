package net.k1ra.ao.model

interface ConnectionStatusCallback {
    fun onConnecting(device: Device)

    fun onConnected(device: Device)

    fun onDisconnecting(device: Device)

    fun onDisconnected(device: Device)
}