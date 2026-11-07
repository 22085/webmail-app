package com.sshdog.webmail

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM 接收服务。
 *
 * 去重规则（系统 + 本地只弹一次）：
 * - notification payload：应用在后台时系统会自动展示，onMessageReceived 不再弹；
 *   只有在前台（系统不自动展示）时才自己弹一条本地通知。
 * - data payload：系统永远不会自动展示，onMessageReceived 收到就弹一条。
 */
class MailFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "mail"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // 后台线程发，不阻塞；失败静默存待办，下次再试
        Thread { TokenRegistrar.register(applicationContext, token) }.start()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val notification = message.notification
        val hasNotificationPayload = notification != null

        // 后台 + notification payload：系统已自动展示，不重复弹
        if (hasNotificationPayload && !isAppInForeground()) {
            return
        }

        val title = notification?.title
            ?: message.data["title"]
            ?: getString(R.string.notification_default_title)
        val body = notification?.body
            ?: message.data["body"]
            ?: message.data["snippet"]
            ?: message.data["subject"]
            ?: ""

        showMailNotification(title, body, message.messageId)
    }

    private fun isAppInForeground(): Boolean {
        return try {
            ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(Lifecycle.State.STARTED)
        } catch (e: Exception) {
            // 判断失败时按前台处理：宁可多弹一条，也不漏通知
            true
        }
    }

    private fun showMailNotification(title: String, body: String, messageId: String?) {
        // 兜底：渠道在 Application 里已建好，这里再确保一次
        createMailNotificationChannel(this)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mail_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // 同一条 FCM 消息用同一个 id，避免重复堆叠
        val notificationId = messageId?.hashCode() ?: System.currentTimeMillis().toInt()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }
}
