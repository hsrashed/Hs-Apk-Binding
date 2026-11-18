package Com.devhs.apkbinder.payload

import android.content.Context
import java.io.File

object HsLog {
    private var file: File? = null

    @Synchronized
    fun init(c: Context) {
        if (file == null) file = File(c.filesDir, "hs_debug.log")
    }

    fun w(m: String) = write("W", m)
    fun e(m: String) = write("E", m)

    @Synchronized
    private fun write(tag: String, m: String) {
        val f = file ?: return
        try {
            if (f.length() > 200_000) f.delete()
            f.appendText("${System.currentTimeMillis()} $tag $m\n")
        } catch (_: Throwable) {
        }
    }
}
