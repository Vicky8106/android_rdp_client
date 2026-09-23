package com.freerdp.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.freerdp.client.navigation.AppNavHost
import com.freerdp.client.ui.theme.RdpTheme

/**
 * Single-activity host. Edge-to-edge Material 3 UI; theme follows the user's
 * Settings (system/light/dark + dynamic color).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as App).container

        setContent {
            val settings by container.settings.settings.collectAsState()
            RdpTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor
            ) {
                AppNavHost(container)
            }
        }
    }
}
