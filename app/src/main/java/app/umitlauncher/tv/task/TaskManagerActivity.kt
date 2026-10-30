package app.umitlauncher.tv.task

import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Arkaplan uygulama yöneticisi – kumanda (D-pad) uyumlu.
 *  OK/ENTER: seçili uygulamayı kapat | MENU / KIRMIZI: hepsini kapat | BACK: çık */
class TaskManagerActivity : Activity() {

    private class Row(val pkg: String, val label: String)

    private val rows = ArrayList<Row>()
    private lateinit var list: ListView
    private lateinit var info: TextView
    private lateinit var adapter: BaseAdapter
    private val prefs by lazy { getSharedPreferences("killed", Context.MODE_PRIVATE) }
    private val skip = setOf("com.google.android.gms", "com.google.android.gsf")

    /** Önce arkaplan süreçlerini öldür, servis açıksa Ayarlar'dan "Zorla durdur" yap. */
    private fun stop(pkgs: List<String>) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        pkgs.forEach { markKilled(it); am.killBackgroundProcesses(it) }
        if (!ForceStopService.enqueue(pkgs))
            Toast.makeText(this, "Tam kapatma için 'Erişilebilirlik' butonundan servisi aç", Toast.LENGTH_LONG).show()
    }

    private fun markKilled(pkg: String) = prefs.edit().putLong(pkg, System.currentTimeMillis()).apply()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101014"))
            setPadding(px(48), px(32), px(48), px(24))
        }
        val title = TextView(this).apply {
            text = "Arkaplan Uygulamaları"; textSize = 26f; setTextColor(Color.WHITE)
        }
        info = TextView(this).apply {
            textSize = 15f; setTextColor(Color.parseColor("#9a9aa5"))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(12), 0, px(12))
        }
        fun btn(t: String, a: () -> Unit) = Button(this).apply {
            text = t; isFocusable = true; setOnClickListener { a() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = px(12) }
        }
        bar.addView(btn("Tümünü Kapat") { killAll() })
        bar.addView(btn("Yenile") { refresh() })
        bar.addView(btn("Erişilebilirlik") { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })

        list = ListView(this).apply {
            divider = ColorDrawable(Color.parseColor("#22ffffff")); dividerHeight = 1
            selector = ColorDrawable(Color.parseColor("#44ffc72c"))
            isFocusable = true; isFocusableInTouchMode = true
            setOnItemClickListener { _, _, pos, _ -> killOne(pos) }
        }
        adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val r = rows[p]
                val h = LinearLayout(this@TaskManagerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(16), px(10), px(16), px(10))
                }
                h.addView(ImageView(this@TaskManagerActivity).apply {
                    runCatching { setImageDrawable(packageManager.getApplicationIcon(r.pkg)) }
                    layoutParams = LinearLayout.LayoutParams(px(44), px(44))
                })
                val t = LinearLayout(this@TaskManagerActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(px(16), 0, 0, 0)
                }
                t.addView(TextView(this@TaskManagerActivity).apply { text = r.label; textSize = 18f; setTextColor(Color.WHITE) })
                t.addView(TextView(this@TaskManagerActivity).apply { text = r.pkg; textSize = 12f; setTextColor(Color.parseColor("#9a9aa5")) })
                h.addView(t)
                return h
            }
        }
        list.adapter = adapter

        root.addView(title); root.addView(info); root.addView(bar)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!hasUsageAccess()) {
            Toast.makeText(this, "Kullanım erişimi izni ver, sonra geri dön", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            return
        }
        refresh()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun refresh() {
        val pm = packageManager
        val home = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        val found = LinkedHashSet<String>()

        // 1) Son 24 saatte öne gelen uygulamalar (stopped olmayanlar = büyük ihtimalle hâlâ bellekte)
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val ev = usm.queryEvents(end - 24L * 3600 * 1000, end)
        val e = UsageEvents.Event()
        val last = HashMap<String, Long>()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last[e.packageName] = e.timeStamp
        }
        // Kapatılanları, kapatıldıktan sonra tekrar açılana kadar gizle
        last.entries.sortedByDescending { it.value }.forEach {
            if (prefs.getLong(it.key, 0L) < it.value) found.add(it.key)
        }

        // 2) Bazı TV kutularında çalışan süreçleri doğrudan döndürür
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.forEach { p -> p.pkgList?.forEach { found.add(it) } }

        rows.clear()
        for (pkg in found) {
            if (pkg == packageName || pkg in home || pkg in skip) continue
            val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            val sys = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            if (sys) continue
            if (ai.flags and ApplicationInfo.FLAG_STOPPED != 0) continue
            rows.add(Row(pkg, pm.getApplicationLabel(ai).toString()))
        }
        adapter.notifyDataSetChanged()
        info.text = "${rows.size} uygulama  •  OK: kapat  •  MENU/KIRMIZI: hepsini kapat"
        if (rows.isNotEmpty()) list.requestFocus()
    }

    private fun killOne(pos: Int) {
        if (pos !in rows.indices) return
        val r = rows.removeAt(pos)
        stop(listOf(r.pkg))
        adapter.notifyDataSetChanged()
        info.text = "${rows.size} uygulama  •  ${r.label} kapatıldı"
    }

    private fun killAll() {
        val n = rows.size
        stop(rows.map { it.pkg })
        rows.clear(); adapter.notifyDataSetChanged()
        info.text = "$n uygulama kapatıldı"
    }

    override fun onKeyDown(k: Int, ev: KeyEvent): Boolean = when (k) {
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_RED -> { killAll(); true }
        else -> super.onKeyDown(k, ev)
    }
}
