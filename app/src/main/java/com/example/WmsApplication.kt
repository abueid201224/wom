package com.example

import android.app.Application
import com.example.data.cloud.CloudSyncRepository
import com.example.data.local.AppDatabase
import com.example.data.local.PreferencesManager
import com.example.data.local.WmsRepository
import com.example.services.AudioFeedbackManager
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WmsApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val preferencesManager: PreferencesManager by lazy { PreferencesManager(this) }
    val wmsRepository: WmsRepository by lazy {
        WmsRepository(this, database.wmsDao(), preferencesManager)
    }
    val audioFeedbackManager: AudioFeedbackManager by lazy { AudioFeedbackManager(this) }
    val cloudSyncRepository: CloudSyncRepository by lazy { CloudSyncRepository(this) }

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        applicationScope.launch {
            database.ensureSeeded()
        }
    }

    override fun onTerminate() {
        audioFeedbackManager.release()
        super.onTerminate()
    }
}
