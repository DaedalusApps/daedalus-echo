package com.daedalusapps.echo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AdbReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val action = intent?.action ?: return

        if (intent.getBooleanExtra("_forwarded", false)) return
        Log.i("DaedalusADB", "AdbReceiver forwarding: $action")
        context.sendBroadcast(Intent(action).setPackage(context.packageName).also { fwd ->
            intent.extras?.let { fwd.putExtras(it) }
            fwd.putExtra("_forwarded", true)
        })
    }
}
