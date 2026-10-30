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
    private val autoLabels = arrayOf("Oto: Kapalı", "Oto: Ekran", "Oto: 2 saat", "Oto: Ekran+2s")

    private var phone = false
    private lateinit var empty: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        phone = resources.configuration.screenWidthDp < 600   // telefon (dikey) / dar ekran
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101014"))
            if (phone) setPadding(px(16), px(12), px(16), px(8)) else setPadding(px(48), px(28), px(48), px(20))
        }
        val title = TextView(this).apply {
            text = "Arkaplan Uygulamaları"; textSize = if (phone) 22f else 28f; setTextColor(Color.WHITE)
        }
        memTv = TextView(this).apply { textSize = if (phone) 15f else 18f; setTextColor(Color.parseColor("#FFC72C")) }
        info = TextView(this).apply { textSize = if (phone) 13f else 15f; setTextColor(Color.parseColor("#9a9aa5")) }

        fun btn(t: String, a: () -> Unit) = Button(this).apply {
            text = t; textSize = if (phone) 14f else 16f; isAllCaps = false; maxLines = 1
            isFocusable = true; setOnClickListener { a() }
        }
        hoursBtn = btn("") {
            val n = when (Cleaner.hours(this)) { 1 -> 6; 6 -> 24; else -> 1 }
            Cleaner.setHours(this, n); refresh()
        }
        autoBtn = btn("") {
            val n = (Cleaner.auto(this) + 1) % 4
            Cleaner.setAuto(this, n); autoBtn.text = autoLabels[n]
            if (n != 0 && ForceStopService.instance == null)
                Toast.makeText(this, "Otomatik temizlik için Erişilebilirlik servisi açık olmalı", Toast.LENGTH_LONG).show()
        }
        val btns = listOf(
            btn("Tümünü Kapat") { killAll() },
            btn("Yenile") { refresh() },
            hoursBtn, autoBtn,
            btn("Erişilebilirlik") { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        )
        // Telefonda 2'şerli satırlar, TV'de tek satır
        val bars = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(8), 0, px(8)) }
        btns.chunked(if (phone) 2 else btns.size).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEachIndexed { i, bt ->
                row.addView(bt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i < chunk.size - 1) rightMargin = px(8) })
            }
            bars.addView(row)
        }

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
                    minimumHeight = px(if (phone) 60 else 72)
                    setPadding(px(if (phone) 8 else 16), px(8), px(if (phone) 8 else 16), px(8))
                }
                val ic = if (phone) 40 else 48
                h.addView(ImageView(this@TaskManagerActivity).apply {
                    runCatching { setImageDrawable(packageManager.getApplicationIcon(r.pkg)) }
                    layoutParams = LinearLayout.LayoutParams(px(ic), px(ic))
                })
                val t = LinearLayout(this@TaskManagerActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(px(if (phone) 12 else 16), 0, px(8), 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                t.addView(TextView(this@TaskManagerActivity).apply {
                    text = r.label; textSize = if (phone) 16f else 20f; setTextColor(Color.WHITE); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                t.addView(TextView(this@TaskManagerActivity).apply {
                    text = r.pkg; textSize = if (phone) 11f else 12f; setTextColor(Color.parseColor("#9a9aa5")); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                h.addView(t)
                if (kept) h.addView(TextView(this@TaskManagerActivity).apply {
                    text = if (phone) "KORUMALI" else "KORUMALI"; textSize = if (phone) 11f else 14f
                    setTextColor(Color.parseColor("#FFC72C"))
                })
                return h
            }
        }
        list.adapter = adapter
        empty = TextView(this).apply {
            text = "Liste boş.\nDaha önce kapattıkların, tekrar açılana kadar gizlenir.\n'Süre'yi artırmayı dene."
            textSize = 15f; setTextColor(Color.parseColor("#9a9aa5")); gravity = Gravity.CENTER
            setPadding(px(16), px(40), px(16), 0)
        }

        root.addView(title); root.addView(memTv); root.addView(info); root.addView(bars)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(empty, LinearLayout.LayoutParams(-1, -2))
        list.emptyView = empty
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
        val hint = if (phone) "Dokun: kapat  •  Basılı tut: koru"
                   else "OK: kapat  •  OK basılı/YEŞİL: koru  •  MENU/KIRMIZI: hepsini kapat"
        info.text = (extra ?: "${rows.size} uygulama") + "  •  " + hint
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
