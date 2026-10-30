package app.umitlauncher.tv.task

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.LinkedList

/** Ayarlar > Uygulama bilgisi ekranını açıp "Zorla durdur" + onay butonuna basar. */
class ForceStopService : AccessibilityService() {

    companion object {
        @Volatile var instance: ForceStopService? = null
        private val queue = LinkedList<String>()
        private val STOP = listOf("Zorla durdur", "Durmaya zorla", "Force stop", "Force Stop")
        private val OK = listOf("Tamam", "OK", "Zorla durdur", "Durmaya zorla", "Force stop")

        fun enqueue(pkgs: List<String>): Boolean {
            val s = instance ?: return false
            synchronized(queue) { pkgs.forEach { if (!queue.contains(it)) queue.add(it) } }
            s.h.post { s.next() }
            return true
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private var cur: String? = null
    private var step = 0
    private val timeout = Runnable { finishCurrent() }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}

    private fun next() {
        if (cur != null) return
        val p = synchronized(queue) { queue.poll() }
        if (p == null) { returnToApp(); return }
        cur = p; step = 0
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$p"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            h.postDelayed(timeout, 6000)
        } catch (e: Exception) {
            cur = null; h.post { next() }
        }
    }

    private fun finishCurrent() {
        h.removeCallbacks(timeout)
        cur = null; step = 0
        performGlobalAction(GLOBAL_ACTION_BACK)
        h.postDelayed({ next() }, 500)
    }

    private fun returnToApp() {
        startActivity(
            Intent(this, TaskManagerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (cur == null || step == 2) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString()?.contains("settings", true) != true) return

        if (step == 0) {
            val n = findText(root, STOP) ?: return
            val c = clickable(n) ?: return
            if (!c.isEnabled) { finishCurrent(); return }   // zaten kapalı
            if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) step = 1
        } else {
            val n = root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()
                ?: findText(root, listOf("Tamam", "OK")) ?: return
            val c = clickable(n) ?: return
            if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                step = 2
                h.postDelayed({ finishCurrent() }, 600)
            }
        }
    }

    private fun findText(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        for (t in texts) {
            val l = root.findAccessibilityNodeInfosByText(t)
            if (!l.isNullOrEmpty()) return l[0]
        }
        return null
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var x: AccessibilityNodeInfo? = n
        while (x != null) { if (x.isClickable) return x; x = x.parent }
        return n
    }
}
