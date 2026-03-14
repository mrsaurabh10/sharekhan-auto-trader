package com.sharekhan.admin

import androidx.compose.runtime.staticCompositionLocalOf

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}

