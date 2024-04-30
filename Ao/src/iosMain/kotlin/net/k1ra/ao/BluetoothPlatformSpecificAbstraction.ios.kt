package net.k1ra.ao

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.k1ra.ao.helpers.asStateFlowClass
import net.k1ra.ao.model.Characteristic
import net.k1ra.ao.model.ConnectionStatusCallback
import net.k1ra.ao.model.Device
import net.k1ra.ao.model.Service
import net.k1ra.ao.model.characteristicState.CharacteristicErrors
import net.k1ra.ao.model.characteristicState.CharacteristicState
import net.k1ra.ao.model.characteristicState.Failure
import net.k1ra.ao.model.characteristicState.Success
import net.k1ra.ao.model.connectionState.ConnectionErrors
import net.k1ra.ao.model.connectionState.ConnectionState
import net.k1ra.ao.model.connectionState.ConnectionFailure
import net.k1ra.ao.model.connectionState.ConnectionSuccess
import net.k1ra.flight_data_recorder.feature.logging.Log
import net.k1ra.sharedprefkmm.extensions.toByteArray
import net.k1ra.sharedprefkmm.extensions.toNsData
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerStatePoweredOff
import platform.CoreBluetooth.CBCentralManagerStatePoweredOn
import platform.CoreBluetooth.CBCentralManagerStateResetting
import platform.CoreBluetooth.CBCentralManagerStateUnauthorized
import platform.CoreBluetooth.CBCentralManagerStateUnknown
import platform.CoreBluetooth.CBCentralManagerStateUnsupported
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBDescriptor
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBPeripheralStateDisconnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import kotlin.coroutines.resume

internal actual object BluetoothPlatformSpecificAbstraction {
    private val managerDelegate = ManagerDelegate()
    val peripheralDelegate = PeripheralDelegate()
    private val writeDelegate = WriteDelegate()
    private val updateNotificationStateDelegate = UpdateNotificationStateDelegate()
    private val centralManager = CBCentralManager(managerDelegate, null)
    private var initDone = false
    val scanResultMap: MutableMap<String, CBPeripheral> = mutableMapOf()
    val internalDeviceMap: MutableMap<String, Device> = mutableMapOf()
    val connectionStatusCallbacksMap: MutableMap<String, ConnectionStatusCallback> = mutableMapOf()
    val bondedDevices: MutableMap<String, CBPeripheral> = mutableMapOf()
    val devicesForCallbackMap: MutableMap<String, Device> = mutableMapOf()
    val connectionErrorHandles: MutableMap<String, (NSError?) -> Unit> = mutableMapOf()
    val deviceDisconnectHandles: MutableMap<String, () -> Unit> = mutableMapOf()
    val servicesDiscoveredHandles: MutableMap<String, () -> Unit> = mutableMapOf()
    val servicesWithDiscoveredCharacteristicsCount: MutableMap<String, Int> = mutableMapOf()
    val dataWrittenToCharacteristic: MutableMap<String, ByteArray> = mutableMapOf()
    val characteristicContinuations: MutableMap<String, CancellableContinuation<CharacteristicState>> = mutableMapOf()
    val characteristicStateFlows: MutableMap<String, MutableStateFlow<ByteArray?>> = mutableMapOf()

    private val commandQueue: MutableList<() -> Unit> = mutableListOf()
    private var commandQueueBusy = false

    private val _scanResults = MutableStateFlow<List<Device>>(listOf())
    actual val scanResults = _scanResults.asStateFlowClass()

    private suspend fun waitUntilReady() {
        while (!initDone)
            delay(100)
    }

    actual suspend fun isAdapterEnabled() : Boolean {
        waitUntilReady()
        Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "Checked adapter status. Is it on? ${centralManager.state == CBCentralManagerStatePoweredOn}")
        return centralManager.state == CBCentralManagerStatePoweredOn
    }

    actual suspend fun turnOnAdapter() : Boolean {
        waitUntilReady()
        Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "We can't turn on the adapter on iOS. Is it on? ${centralManager.state == CBCentralManagerStatePoweredOn}")
        return centralManager.state == CBCentralManagerStatePoweredOn
    }

    actual suspend fun hasPermissions() : Boolean {
        waitUntilReady()
        val hasAccess = centralManager.state == CBCentralManagerStatePoweredOn
                || centralManager.state == CBCentralManagerStatePoweredOff
                || centralManager.state == CBCentralManagerStateResetting

        Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "Checked permissions. Are they there? $hasAccess")
        return hasAccess
    }

    actual suspend fun requestPermissions() : Boolean {
        waitUntilReady()
        val hasAccess = centralManager.state == CBCentralManagerStatePoweredOn
                || centralManager.state == CBCentralManagerStatePoweredOff
                || centralManager.state == CBCentralManagerStateResetting

        Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "Cannot explicitly request permissions on iOS. Are they there? $hasAccess")
        return hasAccess
    }

    actual fun startScan() {
        if (centralManager.state == CBCentralManagerStatePoweredOn) {
            Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "Starting scan!")
            centralManager.stopScan()
            scanResultMap.clear()
            centralManager.scanForPeripheralsWithServices(null, mapOf("CBCentralManagerScanOptionAllowDuplicatesKey" to true))
        }
    }

    actual fun stopScan() {
        Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "Stopping scan!")
        centralManager.stopScan()
    }

    actual suspend fun connectDevice(device: Device, callbacks: ConnectionStatusCallback?): ConnectionState = suspendCancellableCoroutine { continuation ->
        centralManager.stopScan()
        Log.v(Constants.TAG_CONNECTION, "Trying to connect to device ${device.name} at address ${device.address}")

        val btDevice = scanResultMap[device.address]
        if (btDevice != null) {
            if (btDevice.state == CBPeripheralStateConnected) {
                Log.e(Constants.TAG_CONNECTION, "ALREADY CONNECTED to device ${device.name} at address ${device.address}")
                continuation.resume(ConnectionFailure(ConnectionErrors.ALREADY_CONNECTED))
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    delay(Constants.IOS_CONNNECTION_TIMEOUT)
                    if (continuation.isCancelled || continuation.isCompleted)
                        return@launch

                    centralManager.cancelPeripheralConnection(btDevice)
                    continuation.resume(ConnectionFailure(ConnectionErrors.TIMEOUT))
                }

                devicesForCallbackMap[device.address] = device
                connectionErrorHandles[device.address] = {
                    Log.e(Constants.TAG_CONNECTION, "Failed to connect to device ${device.name} at address ${device.address} with the error $it")
                    continuation.resume(ConnectionFailure(ConnectionErrors.GENERIC_FAILURE))
                }
                connectionStatusCallbacksMap[device.address] = object : ConnectionStatusCallback {
                    override fun onConnecting(device: Device) {
                        Log.v(Constants.TAG_CONNECTION, "Connecting to device ${device.name} at address ${device.address}")
                        callbacks?.onConnecting(device)
                    }

                    override fun onConnected(device: Device) {
                        Log.i(Constants.TAG_CONNECTION, "Connected to device ${device.name} at address ${device.address}")
                        callbacks?.onConnected(device)
                        bondedDevices[device.address] = btDevice
                        device.isConnected = true
                        continuation.resume(ConnectionSuccess)
                    }

                    override fun onDisconnecting(device: Device) {
                        Log.v(Constants.TAG_DISCONNECTION, "Disconnecting from device ${device.name} at address ${device.address}")
                        callbacks?.onDisconnecting(device)
                    }

                    override fun onDisconnected(device: Device) {
                        Log.i(Constants.TAG_DISCONNECTION, "Disconnected from device ${device.name} at address ${device.address}")
                        device.isConnected = false
                        deviceDisconnectHandles[device.address]?.invoke()
                        callbacks?.onDisconnected(device)
                    }
                }
                connectionStatusCallbacksMap[device.address]?.onConnecting(device)
                centralManager.connectPeripheral(btDevice, null)
            }
        } else {
            Log.e(Constants.TAG_CONNECTION, "Device ${device.name} at address ${device.address} was not found in the list of saved devices from the last scan")
            continuation.resume(ConnectionFailure(ConnectionErrors.INVALID_DEVICE))
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    actual suspend fun getServices(device: Device) : List<Service>? = suspendCancellableCoroutine { continuation ->
        val btDevice = bondedDevices[device.address]
        Log.v(Constants.TAG_SERVICES, "Discovering services on device ${device.name} at address ${device.address}")
        servicesDiscoveredHandles[device.address] = {
            val btDeviceServices = bondedDevices[device.address]?.services
            continuation.resume(btDeviceServices?.map { service ->
                service as CBService
                Service(
                    device,
                    btDevice!!.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse).toInt(),
                    service.UUID.UUIDString,
                    service.characteristics?.map { characteristic ->
                        characteristic as CBCharacteristic

                        val charId = "${device.address}/${service.UUID.UUIDString}/${characteristic.UUID.UUIDString}"

                        Characteristic(
                            characteristic.UUID.UUIDString,
                            charId,
                            (characteristic.properties and CBCharacteristicPropertyRead).toInt() != 0,
                            (characteristic.properties and CBCharacteristicPropertyNotify).toInt() != 0,
                            (characteristic.properties and CBCharacteristicPropertyWrite).toInt() != 0,
                            (characteristic.properties and CBCharacteristicPropertyWriteWithoutResponse).toInt() != 0,
                            null,
                            ({
                                suspendCancellableCoroutine { continuation ->
                                    commandQueue.add {
                                        Log.v(Constants.TAG_CHARACTERISTICS, "Reading characteristic $charId")

                                        if (!device.isConnected) {
                                            Log.e(Constants.TAG_CHARACTERISTICS, "Failed to read characteristic $charId. Device is not connected")
                                            continuation.resume(Failure(CharacteristicErrors.INVALID_DEVICE))
                                            commandQueueBusy = false
                                            queueNextCommand()
                                            return@add
                                        }

                                        characteristicContinuations[charId] = continuation
                                        btDevice.readValueForCharacteristic(characteristic)
                                    }
                                    queueNextCommand()
                                }
                            }),
                            ({
                                suspendCancellableCoroutine { continuation ->
                                    commandQueue.add {
                                        Log.v(Constants.TAG_CHARACTERISTICS, "Subscribing to characteristic $charId")

                                        if (characteristicStateFlows[charId] == null)
                                            characteristicStateFlows[charId] = MutableStateFlow(null)
                                        val flow = characteristicStateFlows[charId]!!.asStateFlowClass()

                                        if ((characteristic.properties and CBCharacteristicPropertyNotify).toInt() != 0) {

                                            if (!device.isConnected) {
                                                Log.e(Constants.TAG_CHARACTERISTICS, "Failed to subscribe to characteristic $charId. Device is not connected")
                                                commandQueueBusy = false
                                                queueNextCommand()
                                            } else {
                                                btDevice.delegate = updateNotificationStateDelegate
                                                btDevice.setNotifyValue(true, characteristic)
                                            }
                                        } else {
                                            Log.e(Constants.TAG_CHARACTERISTICS, "Failed to subscribe to characteristic $charId. It is not observable")
                                            commandQueueBusy = false
                                            queueNextCommand()
                                        }

                                        continuation.resume(flow)
                                    }
                                    queueNextCommand()
                                }
                            }),
                            ({
                                suspendCancellableCoroutine { continuation ->
                                    commandQueue.add {
                                        Log.v(Constants.TAG_CHARACTERISTICS, "Unsubscribing characteristic $charId")
                                        characteristicStateFlows.remove(charId)

                                        if ((characteristic.properties and CBCharacteristicPropertyNotify).toInt() != 0) {

                                            if (!device.isConnected) {
                                                Log.e(Constants.TAG_CHARACTERISTICS, "Failed to unsubscribe from characteristic $charId. Device is not connected")
                                                commandQueueBusy = false
                                                queueNextCommand()
                                            } else {
                                                btDevice.delegate = updateNotificationStateDelegate
                                                btDevice.setNotifyValue(false, characteristic)
                                            }
                                        } else {
                                            Log.e(Constants.TAG_CHARACTERISTICS, "Failed to unsubscribe from characteristic $charId. It is not observable")
                                            commandQueueBusy = false
                                            queueNextCommand()
                                        }

                                        continuation.resume(Unit)
                                    }
                                    queueNextCommand()
                                }
                            }),
                            ({
                                suspendCancellableCoroutine { continuation ->
                                    commandQueue.add {
                                        Log.v(Constants.TAG_CHARACTERISTICS, "Writing characteristic $charId")

                                        if (!device.isConnected) {
                                            Log.e(Constants.TAG_CHARACTERISTICS, "Failed to write characteristic $charId. Device is not connected")
                                            continuation.resume(Failure(CharacteristicErrors.INVALID_DEVICE))
                                            commandQueueBusy = false
                                            queueNextCommand()
                                            return@add
                                        }

                                        if (it.size <= btDevice.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse).toInt()) {
                                            characteristicContinuations[charId] = continuation
                                            dataWrittenToCharacteristic[charId] = it
                                            btDevice.delegate = writeDelegate
                                            btDevice.writeValue(it.toNsData(), characteristic, CBCharacteristicWriteWithResponse)
                                        } else {
                                            Log.e(Constants.TAG_CHARACTERISTICS, "Failed to write characteristic $charId. Data too long")
                                            continuation.resume(Failure(CharacteristicErrors.MTU_EXCEEDED))
                                            commandQueueBusy = false
                                            queueNextCommand()
                                        }
                                    }
                                    queueNextCommand()
                                }
                            }),
                            ({
                                suspendCancellableCoroutine { continuation ->
                                    commandQueue.add {
                                        Log.v(Constants.TAG_CHARACTERISTICS, "Writing characteristic $charId without response")

                                        if (!device.isConnected) {
                                            Log.e(Constants.TAG_CHARACTERISTICS, "Failed to write characteristic $charId without response. Device is not connected")
                                            continuation.resume(Failure(CharacteristicErrors.INVALID_DEVICE))
                                            commandQueueBusy = false
                                            queueNextCommand()
                                            return@add
                                        }

                                        if (it.size <= btDevice.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse).toInt()) {
                                            btDevice.writeValue(it.toNsData(), characteristic, CBCharacteristicWriteWithoutResponse)

                                            CoroutineScope(Dispatchers.IO).launch {
                                                while (!btDevice.canSendWriteWithoutResponse)
                                                    delay(10)

                                                Log.i(Constants.TAG_CHARACTERISTICS, "Wrote to characteristic $charId | value: ${it.toHexString()}")
                                                continuation.resume(Success(it))
                                                commandQueueBusy = false
                                                queueNextCommand()
                                            }
                                        } else {
                                            Log.e(Constants.TAG_CHARACTERISTICS, "Failed to write characteristic $charId without response. Data too long")
                                            continuation.resume(Failure(CharacteristicErrors.MTU_EXCEEDED))
                                            commandQueueBusy = false
                                            queueNextCommand()
                                        }
                                    }
                                    queueNextCommand()
                                }
                            })
                        )
                    } ?: listOf()
                ) })
        }

        if (btDevice != null) {
            btDevice.discoverServices(null)
        } else {
            Log.e(Constants.TAG_SERVICES, "Device ${device.name} at address ${device.address} was not found in the list of connected devices")
            continuation.resume(null)
        }
    }

    private fun queueNextCommand() = CoroutineScope(Dispatchers.IO).launch {
        if (commandQueueBusy)
            return@launch

        val nextCommand = commandQueue.firstOrNull()
        nextCommand ?: return@launch
        commandQueue.removeAt(0)

        commandQueueBusy = true
        nextCommand.invoke()
    }

    actual suspend fun disconnectDevice(device: Device) : Unit = suspendCancellableCoroutine { continuation ->
        val btDevice = bondedDevices[device.address]

        if (btDevice != null) {
            Log.v(Constants.TAG_DISCONNECTION, "Disconnect called on device ${device.name} at address ${device.address}")

            deviceDisconnectHandles[device.address] = {
                bondedDevices.remove(device.address)
                deviceDisconnectHandles.remove(device.address)
                devicesForCallbackMap.remove(device.address)
                connectionErrorHandles.remove(device.address)
                connectionStatusCallbacksMap.remove(device.address)
                servicesDiscoveredHandles.remove(device.address)
                servicesWithDiscoveredCharacteristicsCount.remove(device.address)
                characteristicContinuations.remove(device.address)
                characteristicStateFlows.remove(device.address)
                continuation.resume(Unit)
            }

            connectionStatusCallbacksMap[device.address]?.onDisconnecting(device)
            centralManager.cancelPeripheralConnection(btDevice)

            CoroutineScope(Dispatchers.IO).launch {
                while(btDevice.state != CBPeripheralStateDisconnected)
                    delay(100)

                connectionStatusCallbacksMap[device.address]?.onDisconnected(device)
            }
        } else {
            Log.e(Constants.TAG_DISCONNECTION, "Device ${device.name} at address ${device.address} was not found in the list of connected devices")
            continuation.resume(Unit)
        }
    }

    class ManagerDelegate : CBCentralManagerDelegateProtocol, NSObject() {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            initDone = true
            when (central.state) {
                CBCentralManagerStateUnknown -> Log.e(Constants.TAG_BLUETOOTHABSTRACTION, "CBCentralManager state UNKNOWN")
                CBCentralManagerStateResetting -> Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "CBCentralManager state RESETTING")
                CBCentralManagerStateUnsupported -> Log.e(Constants.TAG_BLUETOOTHABSTRACTION, "CBCentralManager state UNSUPPORTED")
                CBCentralManagerStateUnauthorized -> Log.e(Constants.TAG_BLUETOOTHABSTRACTION, "CBCentralManager state UNAUTHORIZED")
                CBCentralManagerStatePoweredOff -> Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "CBCentralManager state POWERED OFF")
                CBCentralManagerStatePoweredOn -> Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "CBCentralManager state POWERED ON")
            }
        }

        override fun centralManager(central: CBCentralManager, didDiscoverPeripheral: CBPeripheral, advertisementData: Map<Any?, *>, RSSI: NSNumber) {
            Log.v(Constants.TAG_SCANCALLBACK, "Found BLE device! Name: ${didDiscoverPeripheral.name ?: "Unnamed"}, address: ${didDiscoverPeripheral.identifier.UUIDString}")
            scanResultMap[didDiscoverPeripheral.identifier.UUIDString] = didDiscoverPeripheral

            if (internalDeviceMap[didDiscoverPeripheral.identifier.UUIDString] == null) {
                internalDeviceMap[didDiscoverPeripheral.identifier.UUIDString] = Device(
                    didDiscoverPeripheral.identifier.UUIDString,
                    didDiscoverPeripheral.name?: "Unnamed",
                    didDiscoverPeripheral.state == CBPeripheralStateConnected,
                    RSSI.intValue,
                    (advertisementData["kCBAdvDataServiceUUIDs"] as List<CBUUID>?)?.map { it.UUIDString } ?: listOf())
            } else {
                internalDeviceMap[didDiscoverPeripheral.identifier.UUIDString]!!.name = didDiscoverPeripheral.name?: "Unnamed"
                internalDeviceMap[didDiscoverPeripheral.identifier.UUIDString]!!.isConnected = didDiscoverPeripheral.state == CBPeripheralStateConnected
                internalDeviceMap[didDiscoverPeripheral.identifier.UUIDString]!!.rssi = RSSI.intValue
                internalDeviceMap[didDiscoverPeripheral.identifier.UUIDString]!!.serviceUUIDs =
                    (advertisementData["kCBAdvDataServiceUUIDs"] as List<CBUUID>?)?.map { it.UUIDString } ?: listOf()
            }

            _scanResults.value = internalDeviceMap.filter { scanResultMap.containsKey(it.key) }.map { it.value }
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            didConnectPeripheral.delegate = peripheralDelegate
            connectionStatusCallbacksMap[didConnectPeripheral.identifier.UUIDString]?.onConnected(devicesForCallbackMap[didConnectPeripheral.identifier.UUIDString]!!)
        }

        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            connectionErrorHandles[didFailToConnectPeripheral.identifier.UUIDString]?.invoke(error)
        }
    }

    class PeripheralDelegate : CBPeripheralDelegateProtocol, NSObject() {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            servicesWithDiscoveredCharacteristicsCount[peripheral.identifier.UUIDString] = 0
            val serviceCount = peripheral.services?.size ?: 0

            val device = devicesForCallbackMap[peripheral.identifier.UUIDString]!!
            Log.i(Constants.TAG_SERVICES, "Discovered $serviceCount services on device ${device.name} at address ${device.address}")

            if (serviceCount > 0)
                peripheral.services?.forEach { peripheral.discoverCharacteristics(null, it as CBService) }
            else
                servicesDiscoveredHandles[peripheral.identifier.UUIDString]?.invoke()
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
            servicesWithDiscoveredCharacteristicsCount[peripheral.identifier.UUIDString] = servicesWithDiscoveredCharacteristicsCount[peripheral.identifier.UUIDString]!! + 1

            val device = devicesForCallbackMap[peripheral.identifier.UUIDString]!!
            Log.v(Constants.TAG_CHARACTERISTICS, "Found ${didDiscoverCharacteristicsForService.characteristics?.size} characteristics " +
                    "for service ${didDiscoverCharacteristicsForService.UUID.UUIDString} on device ${device.name} at address ${device.address}")

            val serviceCount = peripheral.services?.size ?: 0
            if (servicesWithDiscoveredCharacteristicsCount[peripheral.identifier.UUIDString]!! >= serviceCount)
                servicesDiscoveredHandles[peripheral.identifier.UUIDString]?.invoke()
        }

        @OptIn(ExperimentalStdlibApi::class)
        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
            val charId = "${peripheral.identifier.UUIDString}/${didUpdateValueForCharacteristic.service?.UUID?.UUIDString}/${didUpdateValueForCharacteristic.UUID.UUIDString}"
            val readContinuation = characteristicContinuations[charId]
            val flow = characteristicStateFlows[charId]

            if (error == null) {
                val data = didUpdateValueForCharacteristic.value!!.toByteArray()

                if (readContinuation != null) {
                    Log.i(Constants.TAG_CHARACTERISTICS, "Read characteristic $charId:\n${data.toHexString()}")
                    readContinuation.resume(Success(data))
                }

                if (flow != null) {
                    Log.i(Constants.TAG_CHARACTERISTICS, "Characteristic $charId changed | value: ${data.toHexString()}")
                    flow.value = data
                }

            } else {
                if (readContinuation != null) {
                    Log.e(Constants.TAG_CHARACTERISTICS, "Characteristic read failed for $charId, error: $error")
                    readContinuation.resume(Failure(CharacteristicErrors.GENERIC_ERROR))
                }
            }

            if (readContinuation != null) {
                characteristicContinuations.remove(charId)
                commandQueueBusy = false
                queueNextCommand()
            }
        }
    }

    class WriteDelegate : CBPeripheralDelegateProtocol, NSObject() {
        @OptIn(ExperimentalStdlibApi::class)
        override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) {
            val charId = "${peripheral.identifier.UUIDString}/${didWriteValueForCharacteristic.service?.UUID?.UUIDString}/${didWriteValueForCharacteristic.UUID.UUIDString}"
            val writeContinuation = characteristicContinuations[charId]

            if (error == null) {
                val data = dataWrittenToCharacteristic[charId]!!
                Log.i(Constants.TAG_CHARACTERISTICS, "Wrote to characteristic $charId | value: ${data.toHexString()}")
                writeContinuation?.resume(Success(data))
            } else {
                Log.e(Constants.TAG_CHARACTERISTICS, "Characteristic write failed for $charId, error: $error")
                writeContinuation?.resume(Failure(CharacteristicErrors.GENERIC_ERROR))
            }

            characteristicContinuations.remove(charId)
            dataWrittenToCharacteristic.remove(charId)
            peripheral.delegate = peripheralDelegate
            commandQueueBusy = false
            queueNextCommand()
        }
    }

    class UpdateNotificationStateDelegate : CBPeripheralDelegateProtocol, NSObject() {
        override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) {
            peripheral.delegate = peripheralDelegate
            commandQueueBusy = false
            queueNextCommand()
        }
    }
}