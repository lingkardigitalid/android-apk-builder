package id.lingkardigital.niokasir

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val CAMERA_PERMISSION_CODE = 100
    private val NOTIF_PERMISSION_CODE = 200

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestCameraPermission()
        requestNotificationPermission()

        webView = findViewById(R.id.webView)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setGeolocationEnabled(true)

        webView.webViewClient = WebViewClient()

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread { request?.grant(request.resources) }
            }
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean = false
        }

        val notifUrl = intent?.getStringExtra("notification_url")
        val targetUrl = if (!notifUrl.isNullOrEmpty()) {
            val baseUrl = getString(R.string.webview_base)
            if (notifUrl.startsWith("http")) notifUrl else "$baseUrl$notifUrl"
        } else {
            getString(R.string.webview_url)
        }
        webView.loadUrl(targetUrl)

        startNotificationService()

        // Minta pengecualian battery optimization (sekali saja)
        requestBatteryOptimizationExemption()
    }

    private fun startNotificationService() {
        try {
            val intent = Intent(this, NotificationPollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("NIO_SERVICE", "Failed to start service", e)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val pkg = packageName

            if (pm.isIgnoringBatteryOptimizations(pkg)) {
                // Sudah exempted
                return
            }

            // Cek apakah pernah ditanya (biar tidak spam)
            val prefs = getSharedPreferences("nio_prefs", MODE_PRIVATE)
            val asked = prefs.getBoolean("battery_asked", false)
            if (asked) return

            AlertDialog.Builder(this)
                .setTitle("Aktifkan Notifikasi Real-time")
                .setMessage(
                    "Supaya notifikasi member baru tetap masuk walau aplikasi ditutup, " +
                    "NIO Kasir butuh pengecualian dari optimasi baterai.\n\n" +
                    "Klik OK, lalu pilih 'Izinkan' / 'Allow'."
                )
                .setPositiveButton("OK") { _, _ ->
                    prefs.edit().putBoolean("battery_asked", true).apply()
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$pkg")
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        // Fallback: buka settings battery optimization umum
                        try {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (e2: Exception) {}
                    }
                }
                .setNegativeButton("Nanti") { _, _ ->
                    prefs.edit().putBoolean("battery_asked", true).apply()
                }
                .show()

        } catch (e: Exception) {
            android.util.Log.e("NIO_BATTERY", "Request exemption failed", e)
        }
    }

    private fun requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            if (camera != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                    CAMERA_PERMISSION_CODE
                )
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIF_PERMISSION_CODE
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra("notification_url")
        if (!url.isNullOrEmpty() && ::webView.isInitialized) {
            val baseUrl = getString(R.string.webview_base)
            val targetUrl = if (url.startsWith("http")) url else "$baseUrl$url"
            webView.loadUrl(targetUrl)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}