package id.lingkardigital.niokasir

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Ambil data dari notifikasi
        val title = remoteMessage.data["title"] ?: remoteMessage.notification?.title ?: "NIO Kasir"
        val body  = remoteMessage.data["body"]  ?: remoteMessage.notification?.body  ?: "Ada notifikasi baru"
        val url   = remoteMessage.data["url"]   ?: ""

        sendNotification(title, body, url)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Token baru dari Firebase — nanti kita kirim ke server PHP
        // Untuk sementara, log saja
        android.util.Log.d("FCM_TOKEN", "New token: $token")
    }

    private fun sendNotification(title: String, body: String, url: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("notification_url", url)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "nio_kasir_channel"
        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "NIO Kasir Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Notifikasi member baru & pengumuman"
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }
}