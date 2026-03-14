package com.sharekhan.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.sharekhan.admin.ui.AdminDashboardApp
import com.sharekhan.admin.ui.theme.AdminDashboardTheme

class MainActivity : ComponentActivity() {

    private val appContainer: AppContainer by lazy {
        (application as AdminDashboardApplication).appContainer
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalAppContainer provides appContainer
            ) {
                AdminDashboardTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AdminDashboardApp()
                    }
                }
            }
        }
    }
}

