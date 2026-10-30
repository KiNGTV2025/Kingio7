package app.umitlauncher.tv.task

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** OK: kapat | OK basılı tut / YEŞİL: koru-korumayı kaldır | MENU / KIRMIZI: hepsini kapat | BACK: çık */
class TaskManagerActivity : Activity() {

    private val rows = ArrayList<Cleaner.App>()
    private var keep: Set<String> = emptySet()
    private lateinit var list: ListView
    private lateinit var info: TextView
    private lateinit var memTv: TextView
    private lateinit var hoursBtn: Button
    private lateinit var autoBtn: Button
    private lateinit var adapter: BaseAdapter
    private val ui = Handler(Looper.getMainLooper())
    private val autoLabels = arrayOf("Oto: Kapalı", "Oto: Ekran kapanınca", "Oto: 2 saatte bir", "Oto: İkisi de")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101014"))
            setPadding(px(48), px(28), px(48), px(20))
        }
        val title = TextView(this).apply { text = "Arkaplan Uygulamaları"; textSize = 28f; setTextColor(Color.WHITE) }
        memTv = TextView(this).apply { textSize = 18f; setTextColor(Color.parseColor("#FFC72C")) }
        info = TextView(this).apply { textSize = 15f; setTextColor(Color.parseColor("#9a9aa5")) }

        fun btn(t: String, a: () -> Unit) = Button(this).apply {
            text = t; textSize = 16f; isFocusable = true; setOnClickListener { a() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { rightMargin = px(12) }
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(10), 0, px(10)) }
        bar.addView(btn("Tümünü Kapat") { killAll() })
        bar.addView(btn("Yenile") { refresh() })
        hoursBtn = btn("") {
            val n = when (Cleaner.hours(this)) { 1 -> 6; 6 -> 24; else -> 1 }
            Cleaner.setHours(this, n); refresh()
        }
        bar.addView(hoursBtn)
        autoBtn = btn("") {
            val n = (Cleaner.auto(this) + 1) % 4
            Cleaner.setAuto(this, n); autoBtn.text = autoLabels[n]
            if (n != 0 && ForceStopService.instance == null)
                Toast.makeText(this, "Otomatik temizlik için Erişilebilirlik servisi açık olmalı", Toast.LENGTH_LONG).show()
        }
        bar.addView(autoBtn)
        bar.addView(btn("Erişilebilirlik") { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) }

        list = ListView(this).apply {
            divider = ColorDrawable(Color.parseColor("#22ffffff")); dividerHeight = 1
            selector = ColorDrawable(Color.parseColor("#44ffc72c"))
            isFocusable = true; isFocusableInTouchMode = true
            setOnItemClickListener { _, _, pos, _ -> killOne(pos) }
            setOnItemLongClickListener { _, _, pos, _ -> toggleKeep(pos); true }
        }
        adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val r = rows[p]
                val kept = r.pkg in keep
                val h = LinearLayout(this@TaskManagerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(16), px(10), px(16), px(10))
                }
                h.addView(ImageView(this@TaskManagerActivity).apply {
                    runCatching { setImageDrawable(packageManager.getApplicationIcon(r.pkg)) }
                    layoutParams = LinearLayout.LayoutParams(px(48), px(48))
                })
                val t = LinearLayout(this@TaskManagerActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(px(16), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                t.addView(TextView(this@TaskManagerActivity).apply { text = r.label; textSize = 20f; setTextColor(Color.WHITE) })
                t.addView(TextView(this@TaskManagerActivity).apply { text = r.pkg; textSize = 12f; setTextColor(Color.parseColor("#9a9aa5")) })
                h.addView(t)
                if (kept) h.addView(TextView(this@TaskManagerActivity).apply {
                    text = "KORUMALI"; textSize = 14f; setTextColor(Color.parseColor("#FFC72C"))
                })
                return h
            }
        }
        list.adapter = adapter

        root.addView(title); root.addView(memTv); root.addView(info); root.addView(scroll)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        hoursBtn.text = "Süre: ${Cleaner.hours(this)} saat"
        autoBtn.text = autoLabels[Cleaner.auto(this)]
    }

    override fun onResume() {
        super.onResume()
        if (!Cleaner.hasUsageAccess(this)) {
            Toast.makeText(this, "Kullanım erişimi izni ver, sonra geri dön", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            return
        }
        refresh()
    }

    private fun status(extra: String? = null) {
        memTv.text = Cleaner.mem(this)
        info.text = (extra ?: "${rows.size} uygulama") + "  •  OK: kapat  •  OK basılı/YEŞİL: koru  •  MENU/KIRMIZI: hepsini kapat"
    }

    private fun refresh() {
        hoursBtn.text = "Süre: ${Cleaner.hours(this)} saat"
        keep = Cleaner.keep(this)
        rows.clear(); rows.addAll(Cleaner.candidates(this))
        adapter.notifyDataSetChanged()
        status()
        if (rows.isNotEmpty()) list.requestFocus()
    }

    private fun afterStop(msg: String, full: Boolean) {
        adapter.notifyDataSetChanged()
        status(msg)
        if (!full) {
            Toast.makeText(this, "Tam kapatma için 'Erişilebilirlik' butonundan servisi aç", Toast.LENGTH_LONG).show()
            ui.postDelayed({ status(msg) }, 1500)   // RAM'in toparlanması için kısa bekleme
        }
    }

    private fun killOne(pos: Int) {
        if (pos !in rows.indices) return
        val r = rows[pos]
        if (r.pkg in keep) { Toast.makeText(this, "${r.label} korumalı (basılı tutup kaldır)", Toast.LENGTH_SHORT).show(); return }
        rows.removeAt(pos)
        afterStop("${r.label} kapatıldı", Cleaner.stop(this, listOf(r.pkg), true))
    }

    private fun killAll() {
        val targets = rows.filter { it.pkg !in keep }
        if (targets.isEmpty()) { Toast.makeText(this, "Kapatılacak uygulama yok", Toast.LENGTH_SHORT).show(); return }
        rows.removeAll(targets.toSet())
        afterStop("${targets.size} uygulama kapatıldı", Cleaner.stop(this, targets.map { it.pkg }, true))
    }

    private fun toggleKeep(pos: Int) {
        if (pos !in rows.indices) return
        val on = Cleaner.toggleKeep(this, rows[pos].pkg)
        keep = Cleaner.keep(this)
        adapter.notifyDataSetChanged()
        Toast.makeText(this, if (on) "${rows[pos].label} korumaya alındı" else "${rows[pos].label} korumadan çıkarıldı", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(k: Int, ev: KeyEvent): Boolean = when (k) {
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_RED -> { killAll(); true }
        KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_INFO -> { toggleKeep(list.selectedItemPosition); true }
        else -> super.onKeyDown(k, ev)
    }
}
