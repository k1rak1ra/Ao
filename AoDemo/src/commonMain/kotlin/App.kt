import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Scaffold
import androidx.compose.material.SnackbarHost
import androidx.compose.material.SnackbarHostState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import net.k1ra.ao.BLEManager
import net.k1ra.ao.model.Characteristic
import net.k1ra.ao.model.ConnectionStatusCallback
import net.k1ra.ao.model.Device
import net.k1ra.ao.model.Service
import net.k1ra.ao.model.characteristicState.Failure
import net.k1ra.ao.model.characteristicState.Success
import net.k1ra.ao.model.connectionState.ConnectionFailure
import net.k1ra.ao.model.connectionState.ConnectionSuccess
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
@Preview
fun App() {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var adapterEnabled by mutableStateOf(false)
    var hasPermissions by mutableStateOf(false)
    var isScanning by mutableStateOf(BLEManager.isScanning())
    var devices by mutableStateOf(BLEManager.scanResults.value)
    var connectedDevices by mutableStateOf(mutableListOf<Device>())
    var servicesForDevices by mutableStateOf(mutableMapOf<String, List<Service>>())
    var observedValues by mutableStateOf(mutableMapOf<Characteristic, ByteArray>())

    BLEManager.scanResults.singletonSubscribe {
        devices = it
    }

    CoroutineScope(Dispatchers.Main).launch {
        adapterEnabled = BLEManager.isAdapterEnabled()
        hasPermissions = BLEManager.hasPermissions()
    }

    MaterialTheme {
        Scaffold(
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState)
            }
        ) { _ ->
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = {
                    CoroutineScope(Dispatchers.IO).launch {
                        adapterEnabled = BLEManager.isAdapterEnabled()
                    }
                }) {
                    Text("Check if adapter is enabled")
                }

                Button(onClick = {
                    CoroutineScope(Dispatchers.IO).launch {
                        adapterEnabled = BLEManager.turnOnAdapter()
                    }
                }) {
                    Text("Turn on adapter")
                }

                Text("Is enabled? $adapterEnabled")

                Box(Modifier.height(8.dp))

                Button(onClick = {
                    CoroutineScope(Dispatchers.IO).launch {
                        hasPermissions = BLEManager.hasPermissions()
                    }
                }) {
                    Text("Check for permissions")
                }

                Button(onClick = {
                    CoroutineScope(Dispatchers.IO).launch {
                        hasPermissions = BLEManager.requestPermissions()
                    }
                }) {
                    Text("Request permissions")
                }

                Text("Has permissions? $hasPermissions")

                Box(Modifier.height(8.dp))

                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    observedValues.map {
                        Card(
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
                                .fillMaxWidth(),
                            elevation = 4.dp
                        ) {
                            Column(
                                Modifier.padding(all = 16.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    "Observing\n${it.key.charId}",
                                    style = MaterialTheme.typography.subtitle1,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    it.value.decodeToString(),
                                    textAlign = TextAlign.Center
                                )
                                Button(onClick = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        it.key.cancelObserve()

                                        scope.launch {
                                            val tempList = mutableMapOf<Characteristic, ByteArray>()
                                            tempList.putAll(observedValues)
                                            tempList.remove(it.key)
                                            observedValues = tempList
                                        }
                                    }
                                }){
                                    Text("Cancel")
                                }
                            }
                        }
                    }

                    connectedDevices.map {
                        Card(
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
                                .fillMaxWidth(),
                            elevation = 4.dp
                        ) {
                            Column(
                                Modifier.padding(all = 16.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    "Connected to ${it.name}",
                                    style = MaterialTheme.typography.subtitle1,
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    it.address,
                                    style = MaterialTheme.typography.body1,
                                    textAlign = TextAlign.Center
                                )

                                Button(onClick = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val services = BLEManager.getServices(it)

                                        if (services != null) {
                                            val tempMap = mutableMapOf<String, List<Service>>()
                                            tempMap.putAll(servicesForDevices)
                                            tempMap[it.address] = services
                                            servicesForDevices = tempMap
                                        } else {
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Error getting services for ${it.name}")
                                            }
                                        }
                                    }
                                }) {
                                    Text("Get services")
                                }

                                servicesForDevices[it.address]?.map {
                                    Text(
                                        "MTU: ${it.mtu}\n${it.uuid}",
                                        style = MaterialTheme.typography.body1,
                                        textAlign = TextAlign.Center
                                    )

                                    it.characteristics.map {
                                        Text(
                                            it.uuid,
                                            style = MaterialTheme.typography.subtitle2,
                                            textAlign = TextAlign.Center
                                        )

                                        if (it.readable) {
                                            Button(onClick = {
                                                CoroutineScope(Dispatchers.IO).launch {
                                                    val result = it.read()

                                                    scope.launch {
                                                        when (result) {
                                                            is Failure -> snackbarHostState.showSnackbar("Error reading ${it.uuid}: ${result.reason.name}")

                                                            is Success -> snackbarHostState.showSnackbar("Read ${it.uuid}! Value is ${result.data!!.decodeToString()}")
                                                        }
                                                    }
                                                }
                                            }){
                                                Text("Read")
                                            }
                                        }

                                        if (it.observable) {
                                            Button(onClick = {
                                                CoroutineScope(Dispatchers.IO).launch {
                                                    it.observe { data ->
                                                        data ?: return@observe

                                                        scope.launch {
                                                            val tempList = mutableMapOf<Characteristic, ByteArray>()
                                                            tempList.putAll(observedValues)
                                                            tempList[it] = data
                                                            observedValues = tempList
                                                        }
                                                    }
                                                }
                                            }){
                                                Text("Observe")
                                            }
                                        }

                                        if (it.writable) {
                                            Button(onClick = {
                                                CoroutineScope(Dispatchers.IO).launch {
                                                    val result = it.write("test with resp".encodeToByteArray())

                                                    scope.launch {
                                                        when (result) {
                                                            is Failure -> snackbarHostState.showSnackbar("Error writing ${it.uuid}: ${result.reason.name}")
                                                            is Success -> snackbarHostState.showSnackbar("Wrote to ${it.uuid}!")
                                                        }
                                                    }
                                                }
                                            }){
                                                Text("Write")
                                            }
                                        }

                                        if (it.writableWithoutResponse) {
                                            Button(onClick = {
                                                CoroutineScope(Dispatchers.IO).launch {
                                                    val result = it.writeNoResp("test no resp".encodeToByteArray())

                                                    scope.launch {
                                                        when (result) {
                                                            is Failure -> snackbarHostState.showSnackbar("Error writing without response to ${it.uuid}: ${result.reason.name}")
                                                            is Success -> snackbarHostState.showSnackbar("Wrote without response to ${it.uuid}!")
                                                        }
                                                    }
                                                }
                                            }){
                                                Text("Write without response")
                                            }
                                        }
                                    }
                                }

                                Box(Modifier.height(32.dp))

                                Button(onClick = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        BLEManager.disconnectDevice(it)

                                        val tempList = arrayListOf<Device>()
                                        tempList.addAll(connectedDevices)
                                        tempList.remove(it)
                                        connectedDevices = tempList
                                    }
                                }) {
                                    Text("Disconnect")
                                }
                            }
                        }
                    }
                }

                Box(Modifier.height(8.dp))

                if (isScanning) {
                    Button(onClick = {
                        BLEManager.stopScan()
                        isScanning = BLEManager.isScanning()
                    }) {
                        Text("Stop scan")
                    }
                } else {
                    Button(onClick = {
                        BLEManager.startScan()
                        isScanning = BLEManager.isScanning()
                    }) {
                        Text("Start scan")
                    }
                }

                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    devices.map {
                        Card(
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
                                .fillMaxWidth(),
                            elevation = 4.dp
                        ) {
                            Column(
                                Modifier.padding(all = 16.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    it.name,
                                    style = MaterialTheme.typography.subtitle1,
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    it.address,
                                    style = MaterialTheme.typography.body1,
                                    textAlign = TextAlign.Center
                                )

                                Text(
                                    "Connected? ${it.isConnected}\n RSSI: ${it.rssi}",
                                    style = MaterialTheme.typography.subtitle2,
                                    textAlign = TextAlign.Center
                                )

                                Box(Modifier.height(8.dp))

                                Text(
                                    "Services (${it.serviceUUIDs.size})",
                                    style = MaterialTheme.typography.body1,
                                    textAlign = TextAlign.Center
                                )

                                it.serviceUUIDs.map {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.body2,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                Button(onClick = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        isScanning = false
                                        val result = BLEManager.connectDevice(
                                            it,
                                            object : ConnectionStatusCallback {
                                                override fun onConnecting(device: Device) {
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("Connecting to ${device.name}")
                                                    }
                                                }

                                                override fun onConnected(device: Device) {
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("Connected to ${device.name}")
                                                    }
                                                }

                                                override fun onDisconnecting(device: Device) {
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("Disconnecting from ${device.name}")
                                                    }
                                                }

                                                override fun onDisconnected(device: Device) {
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("Disconnected from ${device.name}")
                                                    }
                                                }
                                            })

                                        if (result is ConnectionSuccess) {
                                            val tempList = arrayListOf<Device>()
                                            tempList.addAll(connectedDevices)
                                            tempList.add(it)

                                            connectedDevices = tempList
                                        } else if (result is ConnectionFailure) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Failed to connect to ${it.name} with error ${result.reason.name}")
                                            }
                                        }
                                    }
                                }) {
                                    Text("Connect")
                                }
                            }
                        }
                    }
                }

            }
        }
    }
}