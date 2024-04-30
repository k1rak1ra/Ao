package net.k1ra.ao

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import net.k1ra.flight_data_recorder.feature.logging.Log

class BluetoothEnableContainerActivity : AppCompatActivity() {
    private val enableBluetoothActivtyForResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val identifier = intent.getLongExtra("identifier", 0)

        BluetoothPlatformSpecificAbstraction.turnOnRequestMap[identifier]?.invoke()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            enableBluetoothActivtyForResult.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: SecurityException) {
            Log.e(Constants.TAG_BLUETOOTHENABLE, e.message ?: "Missing permissions for BluetoothEnable on Android S and above, request permissions first!")

            val identifier = intent.getLongExtra("identifier", 0)

            BluetoothPlatformSpecificAbstraction.turnOnRequestMap[identifier]?.invoke()
            finish()
        }
    }
}