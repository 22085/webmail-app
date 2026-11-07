package com.sshdog.webmail

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class WebmailApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createMailNotificationChannel(this)
        // 上次 token 注册失败留下的待办，下次启动再试一次
        TokenRegistrar.retryPending(this)
    }
}

/** 新邮件通知渠道：id "mail"、名“新邮件”、高重要性。 */
fun createMailNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            MailFirebaseMessagingService.CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
