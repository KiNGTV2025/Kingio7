package app.umitlauncher.tv.task

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.LinkedList

/** Ayarlar > Uygulama bilgisi ekranından "Zorla durdur" yapar + otomatik temizlik (ekran kapanınca / saatlik). */
class ForceStopService : AccessibilityService() {

    companion object {
        @Volatile var instance: ForceStopService? = null
        @Volatile private var backToApp = true
        private val queue = LinkedList<String>()
        private val STOP = listOf("Zorla durdur", "Durmaya zorla", "Force stop", "Force Stop")
        private const val INTERVAL = 2 * 3600 * 1000L

        fun enqueue(pkgs: List<String>, back: Boolean): Boolean {
            val s = instance ?: return false
            backToApp = back
            synchronized(queue) { pkgs.forEach { if (!queue.contains(it)) queue.add(it) } }
            s.h.post { s.next() }
            return true
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private var cur: String? = null
    private var step = 0
    private val timeout = Runnable { finishCurrent() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF && (Cleaner.auto(c) and 1) != 0) autoClean()
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if ((Cleaner.auto(this@ForceStopService) and 2) != 0) autoClean()
            h.postDelayed(this, INTERVAL)
        }
    }

    /** Otomatik temizlik sessiz çalışır (ekran açmaz): sadece arkaplan süreçlerini öldürür. */
    private fun autoClean() {
        try {
            if (!Cleaner.hasUsageAccess(this)) return
            val keep = Cleaner.keep(this)
            val l = Cleaner.candidates(this).map { it.pkg }.filter { it !in keep }
            if (l.isNotEmpty()) Cleaner.softKill(this, l)
        } catch (_: Exception) {}
    }

    override fun onServiceConnected() {
        instance = this
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        h.removeCallbacks(tick)
        h.postDelayed(tick, INTERVAL)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        h.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    private fun next() {
        if (cur != null) return
        val p = synchronized(queue) { queue.poll() }
        if (p == null) { finishAll(); return }
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

    private fun finishAll() {
        if (backToApp) {
            startActivity(
                Intent(this, TaskManagerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } else {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(this, "Temizlendi • " + Cleaner.mem(this), Toast.LENGTH_LONG).show()
        }
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
