package id.lingkardigital.niokasir

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class ServiceRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("NIO_RECEIVER", "Received: ${intent.action}")

        val serviceIntent = Intent(context, NotificationPollingService::class.java)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d("NIO_RECEIVER", "Service restarted")
        } catch (e: Exception) {
            Log.e("NIO_RECEIVER", "Failed to restart", e)
        }
    }
}