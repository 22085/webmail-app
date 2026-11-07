package com.sshdog.webmail

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把 FCM token 注册到服务器：POST https://webmail.sshdog.com/fcm/register
 * Body: {"token": "...", "account": "xxx@sshdog.com"}
 *
 * 服务器按账号分开存 token，新邮件只推给该账号自己的设备，别人收不到你的邮件通知。
 * 注册失败时静默保存，等待下次 token 刷新或下次启动时重试，不打扰用户。
 */
object TokenRegistrar {

    private const val TAG = "TokenRegistrar"
    private const val REGISTER_URL = "https://webmail.sshdog.com/fcm/register"
    private const val PREFS = "fcm_token"
    private const val KEY_PENDING_TOKEN = "pending_token"
    private const val KEY_PENDING_ACCOUNT = "pending_account"
    private const val KEY_REGISTERED_TOKEN = "registered_token"
    private const val KEY_REGISTERED_ACCOUNT = "registered_account"

    /** 上次注册成功的账号（后台刷新 token 时用，没有 WebView 可读账号）。 */
    fun lastAccount(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REGISTERED_ACCOUNT, null)
            ?.takeIf { it.isNotBlank() }
    }

    /** 注册一次；返回是否成功。失败时把 (token, account) 存为待办。 */
    fun register(context: Context, token: String, account: String?): Boolean {
        val acc = account?.trim().orEmpty()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 没账号注册不上去（服务器会 400）：先存待办，等前台读到账号再试
        if (acc.isEmpty()) {
            prefs.edit().putString(KEY_PENDING_TOKEN, token).apply()
            return false
        }
        // 同一个 token + 账号已经注册过：不用重复发
        if (prefs.getString(KEY_REGISTERED_TOKEN, null) == token &&
            prefs.getString(KEY_REGISTERED_ACCOUNT, null) == acc
        ) {
            prefs.edit().remove(KEY_PENDING_TOKEN).remove(KEY_PENDING_ACCOUNT).apply()
            return true
        }
        val ok = postToken(token, acc)
        if (ok) {
            prefs.edit()
                .putString(KEY_REGISTERED_TOKEN, token)
                .putString(KEY_REGISTERED_ACCOUNT, acc)
                .remove(KEY_PENDING_TOKEN)
                .remove(KEY_PENDING_ACCOUNT)
                .apply()
        } else {
            prefs.edit()
                .putString(KEY_PENDING_TOKEN, token)
                .putString(KEY_PENDING_ACCOUNT, acc)
                .apply()
        }
        return ok
    }

    /** 兼容旧调用（后台 onNewToken）：用上次注册过的账号。 */
    fun register(context: Context, token: String): Boolean {
        return register(context, token, lastAccount(context))
    }

    /** 应用启动时调用：有待办 (token, account) 就再试一次。 */
    fun retryPending(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_PENDING_TOKEN, null) ?: return
        val account = prefs.getString(KEY_PENDING_ACCOUNT, null)
        Thread {
            register(context, token, account)
        }.start()
    }

    private fun postToken(token: String, account: String): Boolean {
        return try {
            val conn = (URL(REGISTER_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
            }
            conn.outputStream.use { out ->
                out.write(
                    JSONObject().put("token", token).put("account", account).toString()
                        .toByteArray(Charsets.UTF_8)
                )
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "token 注册失败，等待下次重试", e)
            false
        }
    }
}
