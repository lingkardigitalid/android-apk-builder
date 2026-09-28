package id.lingkardigital.niokasir

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.Context

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val CAMERA_PERMISSION_CODE = 100
    private val NOTIF_PERMISSION_CODE = 200
    private var cachedFcmToken: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestCameraPermission()
        requestNotificationPermission()

        // Init Firebase dengan try-catch — biar kalau gagal, app tidak crash
        try {
            com.google.firebase.FirebaseApp.initializeApp(this)
            prefetchFcmToken()
        } catch (e: Exception) {
            Log.e("FIREBASE_INIT", "Firebase init failed", e)
        }

        webView = findViewById(R.id.webView)
        webView.addJavascriptInterface(WebAppInterface(this), "Android")

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
    }

    private fun prefetchFcmToken() {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        cachedFcmToken = task.result ?: ""
                        Log.d("FCM_TOKEN", "Cached: ${cachedFcmToken.take(30)}...")
                    } else {
                        Log.w("FCM_TOKEN", "Failed", task.exception)
                    }
                }
        } catch (e: Exception) {
            Log.e("FCM_TOKEN", "Prefetch error", e)
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

    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun getFCMToken(): String = cachedFcmToken

        @JavascriptInterface
        fun requestNotificationPermission() {
            (context as? android.app.Activity)?.let {
                it.runOnUiThread { requestNotificationPermission() }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
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