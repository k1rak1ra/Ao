package net.k1ra.ao

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts

class PermissionRequestContainerActivity : AppCompatActivity() {
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val identifier = intent.getLongExtra("identifier", 0)

        BluetoothPlatformSpecificAbstraction.permissionsRequestMap[identifier]?.invoke()
        finish()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> {
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                requestPermissionLauncher.launch(arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                ))
            }
        }
    }


}