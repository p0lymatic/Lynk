package com.p0lymatic.lynk

import android.app.Application
import com.p0lymatic.lynk.data.Store
import com.p0lymatic.lynk.service.LynkService
import com.p0lymatic.lynk.ssh.ConnectionManager

class LynkApp : Application() {

    lateinit var store: Store
        private set

    lateinit var connections: ConnectionManager
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        connections = ConnectionManager(this, store)
        connections.onActivityChanged = {
            LynkService.sync(this, connections.activeCount())
        }
    }
}