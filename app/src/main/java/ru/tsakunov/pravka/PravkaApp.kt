package ru.tsakunov.pravka

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.tsakunov.pravka.data.AppDatabase
import ru.tsakunov.pravka.data.Repository
import ru.tsakunov.pravka.data.Settings

class PravkaApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: Settings by lazy { Settings(this) }
    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val repository: Repository by lazy { Repository(database, settings) }

    override fun onCreate() {
        super.onCreate()
        appScope.launch { repository.seedIfNeeded() }
    }
}
