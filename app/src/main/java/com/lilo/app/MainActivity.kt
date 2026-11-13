package com.lilo.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.lilo.app.ui.LiloRoot
import com.lilo.app.ui.LiloTheme

class MainActivity : ComponentActivity() {

    // After the user answers, restart the service so it picks up the location foreground type.
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            GuardService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askPermissions()
        setContent { LiloTheme { LiloRoot(onRequestPermissions = ::askPermissions) } }
    }

    // Every time the app comes to the front, make sure the guard is running (unless the user stopped it).
    override fun onStart() {
        super.onStart()
        if (!GuardHub.userStopped) GuardService.start(this)
    }

    private fun askPermissions() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }
}
