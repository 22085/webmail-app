package com.sshdog.webmail

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {

    companion object {
        private const val WEBMAIL_URL = "https://webmail.sshdog.com"
        private const val STATE_WEBVIEW = "webview_state"
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** 附件上传：系统文件选择器返回结果 */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        if (callback == null) return@registerForActivityResult
        if (result.resultCode != RESULT_OK) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }
        val data = result.data
        val uris = ArrayList<Uri>()
        data?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                uris.add(clip.getItemAt(i).uri)
            }
        }
        data?.data?.let { uris.add(it) }
        callback.onReceiveValue(uris.toTypedArray())
    }

    /** Android 13+ 通知权限：用户拒绝也不影响看邮件，只是收不到通知 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNotificationPermissionIfNeeded()

        webView = findViewById(R.id.webview)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                // 全部跳转留在 WebView 里，不跳外部浏览器
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 每次页面加载完都读一次登录账号（覆盖登录、退出、换账号）
                detectAccountAndRegister()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val acceptType = params?.acceptTypes
                    ?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "*/*"
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = acceptType
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                fileChooserLauncher.launch(
                    Intent.createChooser(intent, getString(R.string.choose_file))
                )
                return true
            }
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
        }

        // Cookie 默认持久化：接受 Cookie，页面切后台时立即落盘
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState.getBundle(STATE_WEBVIEW) ?: Bundle())
        } else {
            webView.loadUrl(WEBMAIL_URL)
        }

        // 返回键：先回退网页历史，退到底才退出应用
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val bundle = Bundle()
        webView.saveState(bundle)
        outState.putBundle(STATE_WEBVIEW, bundle)
    }

    override fun onPause() {
        super.onPause()
        // 立即把 Cookie 写盘，登录态不丢
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        super.onDestroy()
    }

    /**
     * 读 Bulwark 登录账号，再把 FCM token 按账号注册到服务器。
     * Bulwark 把会话存在 localStorage，键 "auth-storage"，state.username 就是登录邮箱。
     * 未登录时返回空，不注册；TokenRegistrar 内部会跳过已注册过的 (token, 账号)。
     */
    private fun detectAccountAndRegister() {
        val js = "(function(){try{var s=JSON.parse(localStorage.getItem(\"auth-storage\")||\"null\");return (s&&s.state&&s.state.username)||\"\";}catch(e){return \"\";}})()"
        webView.evaluateJavascript(js) { result ->
            val account = result?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
                ?: return@evaluateJavascript
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) return@addOnCompleteListener
                val token = task.result ?: return@addOnCompleteListener
                Thread { TokenRegistrar.register(this, token, account) }.start()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
