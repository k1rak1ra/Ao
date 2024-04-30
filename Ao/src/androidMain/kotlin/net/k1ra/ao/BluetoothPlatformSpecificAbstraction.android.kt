package net.k1ra.ao

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.Clock
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
import net.k1ra.ao.model.connectionState.ConnectionFailure
import net.k1ra.ao.model.connectionState.ConnectionState
import net.k1ra.ao.model.connectionState.ConnectionSuccess
import net.k1ra.flight_data_recorder.feature.logging.Log
import net.k1ra.sharedprefkmm.SharedPrefKmmInitContentProvider
import java.util.LinkedList
import java.util.Locale
import java.util.Queue
import java.util.UUID
import kotlin.coroutines.resume


@SuppressLint("MissingPermission")
internal actual object BluetoothPlatformSpecificAbstraction {
    val turnOnRequestMap: MutableMap<Long, () -> Unit> = mutableMapOf()
    val permissionsRequestMap: MutableMap<Long, () -> Unit> = mutableMapOf()
    val scanResultMap: MutableMap<String, ScanResult> = mutableMapOf()
    val internalDeviceMap: MutableMap<String, Device> = mutableMapOf()
    private val bondedDevices: MutableMap<String, BluetoothGatt> = mutableMapOf()
    private val deviceDisconnectHandles: MutableMap<String, () -> Unit> = mutableMapOf()
    private val servicesDiscoveredHandles: MutableMap<String, () -> Unit> = mutableMapOf()
    private val bondedDeviceMtu: MutableMap<String, Int> = mutableMapOf()
    private val characteristicContinuations: MutableMap<String, CancellableContinuation<CharacteristicState>> = mutableMapOf()
    private val characteristicStateFlows: MutableMap<String, MutableStateFlow<ByteArray?>> = mutableMapOf()

    private val commandQueue: Queue<() -> Unit> = LinkedList()
    private var commandQueueBusy = false

    private val _scanResults = MutableStateFlow<List<Device>>(listOf())
    actual val scanResults = _scanResults.asStateFlowClass()

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val bluetoothManager = SharedPrefKmmInitContentProvider.appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, scanResult: ScanResult) {
            Log.v(Constants.TAG_SCANCALLBACK, "Found BLE device! Name: ${scanResult.device.name ?: "Unnamed"}, address: ${scanResult.device.address}")
            scanResultMap[scanResult.device.address] = scanResult

            if (internalDeviceMap[scanResult.device.address] == null) {
                internalDeviceMap[scanResult.device.address] = Device(
                    scanResult.device.address,
                    scanResult.device.name ?: "Unnamed",
                    false,
                    scanResult.rssi,
                    scanResult.scanRecord?.serviceUuids?.map { it.uuid.fixedToString() } ?: listOf()
                )
            } else {
                internalDeviceMap[scanResult.device.address]!!.name = scanResult.device.name ?: "Unnamed"
                internalDeviceMap[scanResult.device.address]!!.rssi = scanResult.rssi
                internalDeviceMap[scanResult.device.address]!!.serviceUUIDs = scanResult.scanRecord?.serviceUuids?.map { it.uuid.fixedToString() } ?: listOf()
            }

            _scanResults.value = internalDeviceMap.filter { scanResultMap.containsKey(it.key) }.map { it.value }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(Constants.TAG_SCANCALLBACK, "Failed to start scan due to error $errorCode")
        }
    }

    actual suspend fun isAdapterEnabled() : Boolean {
        Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "Checked adapter status. Is it on? ${bluetoothAdapter.isEnabled}")
        return bluetoothAdapter.isEnabled
    }

    actual suspend fun turnOnAdapter() : Boolean = suspendCancellableCoroutine { continuation ->
        if (!bluetoothAdapter.isEnabled) {
            val identifier = Clock.System.now().toEpochMilliseconds()

            turnOnRequestMap[identifier] = {
                turnOnRequestMap.remove(identifier)

                Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "Attempted to turn on adapter. Is it actually on? ${bluetoothAdapter.isEnabled}")
                continuation.resume(bluetoothAdapter.isEnabled)
            }

            val enableBtIntent = Intent(SharedPrefKmmInitContentProvider.appContext, BluetoothEnableContainerActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("identifier", identifier)
            SharedPrefKmmInitContentProvider.appContext.startActivity(enableBtIntent)
        } else {
            Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "Attempted to turn on adapter, but it is already on. Continuing")
            continuation.resume(true)
        }
    }

    actual suspend fun hasPermissions() : Boolean {
        Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "Checked permissions. Are they there? ${SharedPrefKmmInitContentProvider.appContext.hasRequiredRuntimePermissions()}")
        return SharedPrefKmmInitContentProvider.appContext.hasRequiredRuntimePermissions()
    }

    actual suspend fun requestPermissions() : Boolean = suspendCancellableCoroutine { continuation ->
        if (!SharedPrefKmmInitContentProvider.appContext.hasRequiredRuntimePermissions()) {
            val identifier = Clock.System.now().toEpochMilliseconds()

            permissionsRequestMap[identifier] = {
                permissionsRequestMap.remove(identifier)

                Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "Requested permissions. Are they there? ${SharedPrefKmmInitContentProvider.appContext.hasRequiredRuntimePermissions()}")
                continuation.resume(SharedPrefKmmInitContentProvider.appContext.hasRequiredRuntimePermissions())
            }

            val requestPermsIntent = Intent(SharedPrefKmmInitContentProvider.appContext, PermissionRequestContainerActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("identifier", identifier)
            SharedPrefKmmInitContentProvider.appContext.startActivity(requestPermsIntent)
        } else {
            Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "Attempted to request permissions, but they are already present. Continuing")
            continuation.resume(true)
        }
    }

    actual fun startScan() {
        if (SharedPrefKmmInitContentProvider.appContext.hasRequiredRuntimePermissions()) {
            Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "Starting scan!")
            bleScanner.stopScan(scanCallback)
            scanResultMap.clear()
            bleScanner.startScan(scanCallback)
        } else {
            Log.e(Constants.TAG_BLUETOOTHABSTRACTION, "Failed to start scan due to missing permissions")
        }
    }

    actual fun stopScan() {
        Log.v(Constants.TAG_BLUETOOTHABSTRACTION, "Stopping scan!")
        bleScanner.stopScan(scanCallback)
    }

    @OptIn(ExperimentalStdlibApi::class)
    actual suspend fun connectDevice(device: Device, callbacks: ConnectionStatusCallback?): ConnectionState = suspendCancellableCoroutine { continuation ->
        bleScanner.stopScan(scanCallback)
        val btDevice = scanResultMap[device.address]?.device
        Log.v(Constants.TAG_CONNECTION, "Trying to connect to device ${device.name} at address ${device.address}")

        if (bondedDevices[device.address] != null) {
            Log.e(Constants.TAG_CONNECTION, "ALREADY CONNECTED to device ${device.name} at address ${device.address}")
            continuation.resume(ConnectionFailure(ConnectionErrors.ALREADY_CONNECTED))
            return@suspendCancellableCoroutine
        }

        if (btDevice != null) {
            btDevice.connectGatt(SharedPrefKmmInitContentProvider.appContext, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS || !continuation.isActive) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTING -> {
                                Log.v(Constants.TAG_CONNECTION, "Connecting to device ${device.name} at address ${device.address}")
                                callbacks?.onConnecting(device)
                            }
                            BluetoothProfile.STATE_CONNECTED -> {
                                gatt!!.requestMtu(Constants.MAX_MTU)
                                Log.i(Constants.TAG_CONNECTION, "Connected to device ${device.name} at address ${device.address}")
                                callbacks?.onConnected(device)
                                bondedDevices[device.address] = gatt
                                device.isConnected = true
                                continuation.resume(ConnectionSuccess)
                            }
                            BluetoothProfile.STATE_DISCONNECTING -> {
                                Log.v(Constants.TAG_DISCONNECTION, "Disconnecting from device ${device.name} at address ${device.address}")
                                callbacks?.onDisconnecting(device)
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                Log.i(Constants.TAG_DISCONNECTION, "Disconnected from device ${device.name} at address ${device.address}")
                                device.isConnected = false
                                deviceDisconnectHandles[device.address]?.invoke()
                                callbacks?.onDisconnected(device)
                            }
                        }
                    } else {
                        Log.e(Constants.TAG_CONNECTION, "Failed to connect to the device ${device.name} at address ${device.address} with the code $newState")
                        continuation.resume(ConnectionFailure(when (newState) {
                            8 ->  ConnectionErrors.TIMEOUT
                            else -> ConnectionErrors.GENERIC_FAILURE
                        }))
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    Log.i(Constants.TAG_SERVICES, "Discovered ${gatt.services.size} services on device ${device.name} at address ${device.address}")
                    bondedDevices[device.address] = gatt
                    servicesDiscoveredHandles[device.address]?.invoke()
                }

                override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                    Log.v(Constants.TAG_CONNECTION, "MTU is $mtu for device ${device.name} at address ${device.address}")
                    bondedDeviceMtu[device.address] = mtu
                }

                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                    with(characteristic) {
                        val charId = "${device.address}/${service.uuid.fixedToString()}/${uuid.fixedToString()}"
                        val readContinuation = characteristicContinuations[charId]

                        when (status) {
                            BluetoothGatt.GATT_SUCCESS -> {
                                Log.i(Constants.TAG_CHARACTERISTICS, "Read characteristic $charId:\n${value.toHexString()}")
                                readContinuation?.resume(Success(value))
                            }
                            BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                                Log.e(Constants.TAG_CHARACTERISTICS, "Read not permitted for $charId!")
                                readContinuation?.resume(Failure(CharacteristicErrors.NOT_PERMITTED))
                            }
                            else -> {
                                Log.e(Constants.TAG_CHARACTERISTICS, "Characteristic read failed for $charId, error: $status")
                                readContinuation?.resume(Failure(CharacteristicErrors.GENERIC_ERROR))
                            }
                        }

                        characteristicContinuations.remove(charId)
                        commandQueueBusy = false
                        queueNextCommand()
                    }
                }

                override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    with(characteristic) {
                        val charId = "${device.address}/${service.uuid.fixedToString()}/${uuid.fixedToString()}"
                        val readContinuation = characteristicContinuations[charId]

                        when (status) {
                            BluetoothGatt.GATT_SUCCESS -> {
                                Log.i(Constants.TAG_CHARACTERISTICS, "Read characteristic $charId:\n${value.toHexString()}")
                                readContinuation?.resume(Success(value))
                            }
                            BluetoothGatt.GATT_READ_NOT_PERMITTED -> {
                                Log.e(Constants.TAG_CHARACTERISTICS, "Read not permitted for $charId!")
                                readContinuation?.resume(Failure(CharacteristicErrors.NOT_PERMITTED))
                            }
                            else -> {
                                Log.e(Constants.TAG_CHARACTERISTICS, "Characteristic read failed for $charId, error: $status")
                                readContinuation?.resume(Failure(CharacteristicErrors.GENERIC_ERROR))
                            }
                        }

                        characteristicContinuations.remove(charId)
                        commandQueueBusy = false
                        queueNextCommand()
                    }
                }

                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                    with(characteristic) {
                        val charId = "${device.address}/${service.uuid.fixedToString()}/${uuid.fixedToString()}"
                        val writeContinuation = characteristicContinuations[charId]

                        when (status) {
                            BluetoothGatt.GATT_SUCCESS -> {
                                Log.i(Constants.TAG_CHARACTERISTICS, "Wrote to characteristic $charId | value: ${value.toHexString()}")
                                writeContinuation?.resume(Success(value))
                            }
                            BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> {
                                Log.e(Constants.TAG_CHARACTERISTICS, "Write to $charId exceeded connection ATT MTU!")
                                writeContinuation?.resume(Failure(CharacteristicErrors.MTU_EXCEEDED))
                            }
                            BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                                Log.e(Constants.TAG_CHARACTERISTICS, "Write not permitted for $charId!")
                                writeContinuation?.resume(Failure(CharacteristicErrors.NOT_PERMITTED))
                            }
                            else -> {
                                Log.e(Constants.TAG_CHARACTERISTICS, "Characteristic write failed for $charId, error: $status")
                                writeContinuation?.resume(Failure(CharacteristicErrors.GENERIC_ERROR))
                            }
                        }

                        characteristicContinuations.remove(charId)
                        commandQueueBusy = false
                        queueNextCommand()
                    }
                }

                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                    commandQueueBusy = false
                    queueNextCommand()
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    with(characteristic) {
                        val charId = "${device.address}/${service.uuid.fixedToString()}/${uuid.fixedToString()}"
                        val data = value.copyOf()
                        Log.i(Constants.TAG_CHARACTERISTICS, "Characteristic $charId changed | value: ${data.toHexString()}")
                        characteristicStateFlows[charId]?.value = data
                    }
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                    with(characteristic) {
                        val charId = "${device.address}/${service.uuid.fixedToString()}/${uuid.fixedToString()}"
                        val data = value.copyOf()
                        Log.i(Constants.TAG_CHARACTERISTICS, "Characteristic $charId changed | value: ${data.toHexString()}")
                        characteristicStateFlows[charId]?.value = data
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
        } else {
            Log.e(Constants.TAG_CONNECTION, "Device ${device.name} at address ${device.address} was not found in the list of saved devices from the last scan")
            continuation.resume(ConnectionFailure(ConnectionErrors.INVALID_DEVICE))
        }
    }

    private fun queueNextCommand() = CoroutineScope(Dispatchers.IO).launch {
        if (commandQueueBusy)
            return@launch

        val nextCommand = commandQueue.poll()
        nextCommand ?: return@launch

        commandQueueBusy = true
        nextCommand.invoke()
    }

    actual suspend fun getServices(device: Device) : List<Service>? = suspendCancellableCoroutine { continuation ->
        val btDevice = bondedDevices[device.address]
        Log.v(Constants.TAG_SERVICES, "Discovering services on device ${device.name} at address ${device.address}")

        if (btDevice != null && device.isConnected) {
            servicesDiscoveredHandles[device.address] = {
                val btDeviceServices = bondedDevices[device.address]?.services
                continuation.resume(btDeviceServices?.filter { it.uuid.fixedToString() != "1800" && it.uuid.fixedToString() != "1801" }?.map { service ->
                    Service(
                        device,
                        bondedDeviceMtu[device.address] ?: Constants.MIN_MTU,
                        service.uuid.fixedToString(),
                        service.characteristics.map { characteristic ->
                            val charId = "${device.address}/${service.uuid.fixedToString()}/${characteristic.uuid.fixedToString()}"

                            Characteristic(
                                characteristic.uuid.fixedToString(),
                                charId,
                                characteristic.isReadable(),
                                characteristic.isObservable(),
                                characteristic.isWritable(),
                                characteristic.isWritableWithoutResponse(),
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

                                            if (!btDevice.readCharacteristic(characteristic)) {
                                                Log.e(
                                                    Constants.TAG_CHARACTERISTICS,
                                                    "Failed to read characteristic $charId. Could not start reading. Is another op in progress?"
                                                )
                                                characteristicContinuations.remove(charId)
                                                continuation.resume(Failure(CharacteristicErrors.FAILED_TO_INIT))
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
                                            Log.v(Constants.TAG_CHARACTERISTICS, "Subscribing to characteristic $charId")

                                            if (characteristicStateFlows[charId] == null)
                                                characteristicStateFlows[charId] = MutableStateFlow(null)
                                            val flow = characteristicStateFlows[charId]!!.asStateFlowClass()

                                            if (characteristic.isObservable()) {
                                                if (!device.isConnected) {
                                                    Log.e(Constants.TAG_CHARACTERISTICS, "Failed to subscribe to characteristic $charId. Device is not connected")
                                                    commandQueueBusy = false
                                                    queueNextCommand()
                                                } else {
                                                    val cccd = characteristic.getDescriptor(UUID.fromString(Constants.CCCD_UUID))
                                                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                                                    if (!btDevice.setCharacteristicNotification(characteristic, true)) {
                                                        Log.e(
                                                            Constants.TAG_CHARACTERISTICS,
                                                            "Failed to subscribe to characteristic $charId. Could not enable notifications."
                                                        )
                                                        commandQueueBusy = false
                                                        queueNextCommand()
                                                    }

                                                    if (!btDevice.writeDescriptor(cccd)) {
                                                        Log.e(Constants.TAG_CHARACTERISTICS, "Failed to subscribe to characteristic $charId. Failed to write CCCD.")
                                                        commandQueueBusy = false
                                                        queueNextCommand()
                                                    }
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

                                            if (characteristic.isObservable()) {
                                                if (!device.isConnected) {
                                                    Log.e(Constants.TAG_CHARACTERISTICS, "Failed to unsubscribe from characteristic $charId. Device is not connected")
                                                    commandQueueBusy = false
                                                    queueNextCommand()
                                                } else {
                                                    val cccd = characteristic.getDescriptor(UUID.fromString(Constants.CCCD_UUID))
                                                    cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE

                                                    if (!btDevice.setCharacteristicNotification(characteristic, false)) {
                                                        Log.e(
                                                            Constants.TAG_CHARACTERISTICS,
                                                            "Failed to unsubscribe from characteristic $charId. Could not disable notifications."
                                                        )
                                                        commandQueueBusy = false
                                                        queueNextCommand()
                                                    }

                                                    if (!btDevice.writeDescriptor(cccd)) {
                                                        Log.e(Constants.TAG_CHARACTERISTICS, "Failed to unsubscribe from characteristic $charId. Failed to write CCCD.")
                                                        commandQueueBusy = false
                                                        queueNextCommand()
                                                    }
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

                                            characteristicContinuations[charId] = continuation
                                            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                                            characteristic.value = it

                                            if (!btDevice.writeCharacteristic(characteristic)) {
                                                Log.e(
                                                    Constants.TAG_CHARACTERISTICS,
                                                    "Failed to write characteristic $charId. Could not start writing. Is another op in progress?"
                                                )
                                                characteristicContinuations.remove(charId)
                                                continuation.resume(Failure(CharacteristicErrors.FAILED_TO_INIT))
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

                                            characteristicContinuations[charId] = continuation
                                            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                            characteristic.value = it

                                            if (!btDevice.writeCharacteristic(characteristic)) {
                                                Log.e(
                                                    Constants.TAG_CHARACTERISTICS,
                                                    "Failed to write characteristic $charId without response. Could not start writing. Is another op in progress?"
                                                )
                                                characteristicContinuations.remove(charId)
                                                continuation.resume(Failure(CharacteristicErrors.FAILED_TO_INIT))
                                                commandQueueBusy = false
                                                queueNextCommand()
                                            }
                                        }
                                        queueNextCommand()
                                    }
                                })
                            )
                        }
                    )
                })
            }

            btDevice.discoverServices()
        } else {
            Log.e(Constants.TAG_SERVICES, "Device ${device.name} at address ${device.address} was not found in the list of connected devices")
            continuation.resume(null)
        }
    }

    actual suspend fun disconnectDevice(device: Device) : Unit = suspendCancellableCoroutine { continuation ->
        val btDevice = bondedDevices[device.address]

        if (btDevice != null) {
            Log.v(Constants.TAG_DISCONNECTION, "Disconnect called on device ${device.name} at address ${device.address}")

            deviceDisconnectHandles[device.address] = {
                bondedDevices.remove(device.address)
                deviceDisconnectHandles.remove(device.address)
                servicesDiscoveredHandles.remove(device.address)
                bondedDeviceMtu.remove(device.address)
                characteristicContinuations.remove(device.address)
                characteristicStateFlows.remove(device.address)
                btDevice.close()
                continuation.resume(Unit)
            }

            if (device.isConnected)
                btDevice.disconnect()
            else
                deviceDisconnectHandles[device.address]?.invoke()
        } else {
            Log.e(Constants.TAG_DISCONNECTION, "Device ${device.name} at address ${device.address} was not found in the list of connected devices")
            continuation.resume(Unit)
        }
    }

    private fun Context.hasPermission(permissionType: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permissionType) == PackageManager.PERMISSION_GRANTED
    }

    private fun Context.hasRequiredRuntimePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun BluetoothGattCharacteristic.isReadable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_READ)

    private fun BluetoothGattCharacteristic.isWritable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)

    private fun BluetoothGattCharacteristic.isWritableWithoutResponse(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)

    private fun BluetoothGattCharacteristic.isObservable(): Boolean =
        containsProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY)

    private fun BluetoothGattCharacteristic.containsProperty(property: Int): Boolean {
        return properties and property != 0
    }

    private fun UUID.fixedToString() : String {
        return if (toString().endsWith("-0000-1000-8000-00805f9b34fb"))
            toString().split("-").first().substring(4).uppercase(Locale.US)
        else
            toString().uppercase(Locale.US)
    }
}