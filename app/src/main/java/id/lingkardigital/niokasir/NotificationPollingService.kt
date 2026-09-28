package id.lingkardigital.niokasir

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class NotificationPollingService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null
    private var lastNotifId = 0L

    companion object {
        private const val TAG = "NIO_POLL"
        private const val CHANNEL_SERVICE = "nio_service_channel"
        private const val CHANNEL_NOTIF = "nio_kasir_channel"
        private const val SERVICE_NOTIF_ID = 1000
        private const val POLL_INTERVAL_MS = 15000L
        private const val BASE_URL = "https://snaplink.site/niom/"
        private const val API_URL = BASE_URL + "api/v1/check-notifications.php"
        private const val COOKIE_DOMAIN = "https://snaplink.site/niom/"
        private const val RESTART_DELAY_MS = 3000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createChannels()
        startForeground(SERVICE_NOTIF_ID, buildServiceNotification())
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        // START_STICKY: restart otomatis kalau dibunuh
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Dipanggil saat user swipe app dari recent
        Log.d(TAG, "onTaskRemoved — schedule restart")

        // Schedule restart via AlarmManager
        try {
            val restartIntent = Intent(applicationContext, ServiceRestartReceiver::class.java).apply {
                action = "id.lingkardigital.niokasir.RESTART_SERVICE"
            }

            val pi = PendingIntent.getBroadcast(
                applicationContext,
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
                pi
            )
        } catch (e: Exception) {
            Log.e(TAG, "Schedule restart failed", e)
        }

        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed — schedule restart")
        pollJob?.cancel()
        scope.cancel()

        // Coba restart via Alarm
        try {
            val restartIntent = Intent(applicationContext, ServiceRestartReceiver::class.java).apply {
                action = "id.lingkardigital.niokasir.RESTART_SERVICE"
            }
            val pi = PendingIntent.getBroadcast(
                applicationContext,
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
                pi
            )
        } catch (e: Exception) {
            Log.e(TAG, "Restart on destroy failed", e)
        }

        super.onDestroy()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "NIO Kasir Service",
                NotificationManager.IMPORTANCE_MIN
            )
            serviceChannel.setShowBadge(false)
            serviceChannel.description = "Background service untuk notifikasi"
            nm.createNotificationChannel(serviceChannel)

            val notifChannel = NotificationChannel(
                CHANNEL_NOTIF,
                "NIO Kasir Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
            notifChannel.description = "Notifikasi member baru & pengumuman"
            notifChannel.enableVibration(true)
            notifChannel.enableLights(true)
            nm.createNotificationChannel(notifChannel)
        }
    }

    private fun buildServiceNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setContentTitle("NIO Kasir Aktif")
            .setContentText("Menerima notifikasi real-time")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun pollOnce() {
        val url = URL("$API_URL?since_id=$lastNotifId")
        val conn = url.openConnection() as HttpURLConnection

        try {
            val cookie = CookieManager.getInstance().getCookie(COOKIE_DOMAIN)
            if (!cookie.isNullOrEmpty()) {
                conn.setRequestProperty("Cookie", cookie)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cookie error", e)
        }

        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "application/json")

        try {
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "HTTP $code")
                return
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)

            if (!json.optBoolean("success")) return

            val data = json.optJSONObject("data") ?: return
            val items = data.optJSONArray("items") ?: return
            val newLastId = data.optLong("last_id", lastNotifId)

            Log.d(TAG, "Got ${items.length()} items, lastId=$newLastId")

            if (lastNotifId == 0L) {
                lastNotifId = newLastId
                return
            }

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val id = item.optLong("id", 0)
                if (id <= lastNotifId) continue

                val title = item.optString("title", "NIO Kasir")
                val body = item.optString("body", "")
                val link = item.optString("link", "")

                Log.d(TAG, "Show notif: $title")
                showNotification(title, body, link, id)
            }

            lastNotifId = newLastId
        } finally {
            conn.disconnect()
        }
    }

    private fun showNotification(title: String, body: String, link: String, id: Long) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("notification_url", link)
        }

        val pi = PendingIntent.getActivity(
            this,
            id.toInt(),
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notif = NotificationCompat.Builder(this, CHANNEL_NOTIF)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(id.toInt(), notif)
    }
}