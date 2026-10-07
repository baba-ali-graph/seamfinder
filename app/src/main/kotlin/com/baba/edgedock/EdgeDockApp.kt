package com.baba.edgedock

import android.app.Application
import com.baba.edgedock.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EdgeDockApp : Application() {
    lateinit var appRepository: AppRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appRepository = AppRepository(this).also { it.registerPackageMonitor() }
        // Warm the catalog in the background so the first overlay open is instant.
        appScope.launch { appRepository.warm() }
    }
}
