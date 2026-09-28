package id.lingkardigital.niokasir

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
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
        private const val CHANNEL_SERVICE = "nio_service_channel_v3"
        private const val CHANNEL_NOTIF = "nio_kasir_notif_v3"
        private const val SERVICE_NOTIF_ID = 1000
        private const val POLL_INTERVAL_MS = 15000L
        private const val BASE_URL = "https://snaplink.site/niom/"
        private const val API_URL = BASE_URL + "api/v1/check-notifications.php"
        private const val COOKIE_DOMAIN = "https://snaplink.site/niom/"
        private const val RESTART_DELAY_MS = 3000L

        // List channel lama yang perlu dihapus
        private val OLD_CHANNELS = listOf(
            "nio_kasir_channel",
            "nio_kasir_notif",
            "nio_kasir_notif_v2",
            "nio_service_channel",
            "nio_service_channel_v2"
        )
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        cleanupOldChannels()
        createChannels()
        startForeground(SERVICE_NOTIF_ID, buildServiceNotification())
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved")
        scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        pollJob?.cancel()
        scope.cancel()
        scheduleRestart()
        super.onDestroy()
    }

    private fun cleanupOldChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(NotificationManager::class.java)
                for (ch in OLD_CHANNELS) {
                    nm.deleteNotificationChannel(ch)
                    Log.d(TAG, "Deleted old channel: $ch")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup channels failed", e)
            }
        }
    }

    private fun scheduleRestart() {
        try {
            val intent = Intent(applicationContext, ServiceRestartReceiver::class.java).apply {
                action = "id.lingkardigital.niokasir.RESTART_SERVICE"
            }
            val pi = PendingIntent.getBroadcast(
                applicationContext, 1, intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + RESTART_DELAY_MS, pi
            )
        } catch (e: Exception) {
            Log.e(TAG, "Schedule restart failed", e)
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Service channel (silent)
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "NIO Kasir Service",
                NotificationManager.IMPORTANCE_MIN
            )
            serviceChannel.setShowBadge(false)
            nm.createNotificationChannel(serviceChannel)

            // Notification channel
            val notifChannel = NotificationChannel(
                CHANNEL_NOTIF,
                "NIO Kasir Notifications",
                NotificationManager.IMPORTANCE_HIGH  // WAJIB HIGH untuk heads-up popup
            )
            notifChannel.description = "Notifikasi member baru & pengumuman"
            notifChannel.enableVibration(true)
            notifChannel.vibrationPattern = longArrayOf(0, 500, 200, 500)
            notifChannel.enableLights(true)
            notifChannel.lightColor = 0xFF0B5FFF.toInt()
            notifChannel.setShowBadge(true)

            // Lock screen visibility
            notifChannel.lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC

            // Sound eksplisit
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttrs = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            notifChannel.setSound(soundUri, audioAttrs)

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
                try { pollOnce() } catch (e: Exception) { Log.e(TAG, "Poll error", e) }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun pollOnce() {
        val url = URL("$API_URL?since_id=$lastNotifId")
        val conn = url.openConnection() as HttpURLConnection

        try {
            val cookie = CookieManager.getInstance().getCookie(COOKIE_DOMAIN)
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
        } catch (e: Exception) {}

        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "application/json")

        try {
            if (conn.responseCode != 200) return

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            if (!json.optBoolean("success")) return

            val data = json.optJSONObject("data") ?: return
            val items = data.optJSONArray("items") ?: return
            val newLastId = data.optLong("last_id", lastNotifId)

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
            this, id.toInt(), intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        // Full-screen intent untuk popup di lock screen
        val fullScreenPi = PendingIntent.getActivity(
            this, (id + 10000).toInt(), intent,
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
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // lock screen
            .setContentIntent(pi)
            .setFullScreenIntent(fullScreenPi, true)  // popup di lock screen
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(id.toInt(), notif)
    }
}