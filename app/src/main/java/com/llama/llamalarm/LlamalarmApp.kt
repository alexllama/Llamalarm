package com.llama.llamalarm

import android.app.Application
import com.llama.llamalarm.alarm.AlarmScheduler
import com.llama.llamalarm.data.AlarmList
import com.llama.llamalarm.data.AlarmRepository
import com.llama.llamalarm.data.InstanceList
import com.llama.llamalarm.data.InstanceRepository
import com.llama.llamalarm.data.JsonStore
import com.llama.llamalarm.data.SettingsRepository
import com.llama.llamalarm.domain.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class LlamalarmApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val files: File = app.filesDir

    val alarmRepository = AlarmRepository(
        JsonStore(File(files, "alarms.json"), AlarmList.serializer()) { AlarmList() },
        scope,
    )
    val settingsRepository = SettingsRepository(
        JsonStore(File(files, "settings.json"), AppSettings.serializer()) { AppSettings() },
        scope,
    )
    val instanceRepository = InstanceRepository(
        JsonStore(File(files, "active_instances.json"), InstanceList.serializer()) { InstanceList() },
        scope,
    )
    val scheduler = AlarmScheduler(app)

    fun start() {
        scope.launch {
            // Self-heal scheduling on app start (app update, force-stop, time changes).
            runCatching { scheduler.rescheduleAll() }
        }
    }
}
