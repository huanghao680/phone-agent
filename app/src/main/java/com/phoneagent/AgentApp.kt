package com.phoneagent

import android.app.Application
import com.topjohnwu.superuser.Shell

class AgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(15)
        )
    }
}
