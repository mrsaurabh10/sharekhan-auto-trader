package com.sharekhan.admin

import android.app.Application
import com.sharekhan.admin.data.preferences.AdminPreferences
import com.sharekhan.admin.data.repository.AdminRepository

class AdminDashboardApplication : Application() {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
    }
}

class AppContainer(application: Application) {
    private val preferences = AdminPreferences(application)
    val repository = AdminRepository(preferences)
}

