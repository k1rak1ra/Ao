package net.k1ra.ao

import kotlin.time.Duration.Companion.seconds

object Constants {
    const val TAG_BLUETOOTHABSTRACTION = "Ao/BluetoothAbstraction"
    const val TAG_BLUETOOTHENABLE = "Ao/BluetoothEnableActivity"
    const val TAG_SCANCALLBACK = "Ao/ScanCallbackInternal"
    const val TAG_CONNECTION = "Ao/Connection"
    const val TAG_DISCONNECTION = "Ao/Disconnection"
    const val TAG_SERVICES = "Ao/Services"
    const val TAG_CHARACTERISTICS = "Ao/Characteristics"

    val IOS_CONNNECTION_TIMEOUT = 30.seconds
    const val MIN_MTU = 23
    const val MAX_MTU = 517
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
}