package app.umitlauncher.tv.task

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.*

/** Dokun/OK: kapat | Basılı tut / YEŞİL: koru | MENU / KIRMIZI: hepsini kapat | BACK: çık */
class TaskManagerActivity : Activity() {

    private val BG = Color.parseColor("#0E0F13")
    private val CARD = Color.parseColor("#1A1C23")
    private val CARD_HI = Color.parseColor("#2B2F3B")
    private val ACCENT = Color.parseColor("#FFC72C")
    private val DANGER = Color.parseColor("#FF5A4D")
    private val MUTED = Color.parseColor("#8E92A0")

    private val rows = ArrayList<Cleaner.App>()
    private var keep: Set<String> = emptySet()
    private var phone = false
    private lateinit var list: ListView
    private lateinit var info: TextView
    private lateinit var memTv: TextView
    private lateinit var memSub: TextView
    private lateinit var barFill: View
    private lateinit var barFree: View
    private lateinit var hoursBtn: Button
    private lateinit var autoBtn: Button
    private lateinit var serviceBtn: Button
    private lateinit var adapter: BaseAdapter
    private val ui = Handler(Looper.getMainLooper())
    private val autoLabels = arrayOf("Oto: Kapalı", "Oto: Ekran", "Oto: 2 saat", "Oto: Ekran+2s")

    private fun px(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = px(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(px(strokeDp), strokeColor)
    }

    /** Normal / odaklı (kumanda) / basılı (dokunma) hâlleri olan arka plan */
    private fun states(normal: Int, hi: Int, radiusDp: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(hi, radiusDp, ACCENT, 2))
        addState(intArrayOf(android.R.attr.state_selected), shape(hi, radiusDp, ACCENT, 2))
        addState(intArrayOf(android.R.attr.state_pressed), shape(hi, radiusDp))
        addState(intArrayOf(), shape(normal, radiusDp))
    }

    private fun pill(t: String, primary: Boolean, small: Boolean = false, a: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; maxLines = 1; typeface = Typeface.DEFAULT_BOLD
        textSize = if (small) 13f else if (phone) 15f else 17f
        setTextColor(Color.WHITE)
        stateListAnimator = null
        minHeight = 0; minimumHeight = 0
        setPadding(px(10), px(if (small) 10 else 13), px(10), px(if (small) 10 else 13))
        background = if (primary) states(DANGER, Color.parseColor("#FF7A6E"), 28) else states(CARD, CARD_HI, 28)
        isFocusable = true
        setOnClickListener { a() }
    }

    private fun lp(w: Float, left: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply { leftMargin = px(left) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        phone = resources.configuration.screenWidthDp < 600
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            if (phone) setPadding(px(16), px(14), px(16), px(10)) else setPadding(px(48), px(28), px(48), px(18))
        }

        // Başlık
        val title = TextView(this).apply {
            text = "Arkaplan"; textSize = if (phone) 28f else 34f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        }
        info = TextView(this).apply { textSize = 14f; setTextColor(MUTED) }

        // RAM kartı
        val ramLabel = TextView(this).apply { text = "RAM"; textSize = 12f; setTextColor(MUTED); typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f }
        memTv = TextView(this).apply { textSize = if (phone) 24f else 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        memSub = TextView(this).apply { textSize = 13f; setTextColor(MUTED) }
        barFill = View(this)
        barFree = View(this)
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = shape(Color.parseColor("#2B2F3B"), 6)
            addView(barFill, LinearLayout.LayoutParams(0, px(10), 1f))
            addView(barFree, LinearLayout.LayoutParams(0, px(10), 1f))
        }
        val ramCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(CARD, 20); setPadding(px(18), px(14), px(18), px(14))
            addView(ramLabel); addView(memTv)
            addView(track, LinearLayout.LayoutParams(-1, px(10)).apply { topMargin = px(8); bottomMargin = px(8) })
            addView(memSub)
        }

        // Butonlar
        hoursBtn = pill("", false, true) {
            val n = when (Cleaner.hours(this)) { 1 -> 6; 6 -> 24; else -> 1 }
            Cleaner.setHours(this, n); refresh()
        }
        autoBtn = pill("", false, true) {
            val n = (Cleaner.auto(this) + 1) % 4
            Cleaner.setAuto(this, n); autoBtn.text = autoLabels[n]
            if (n != 0 && ForceStopService.instance == null)
                Toast.makeText(this, "Otomatik temizlik için Servis (Erişilebilirlik) açık olmalı", Toast.LENGTH_LONG).show()
        }
        serviceBtn = pill("Servis", false, true) { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("Tümünü Kapat", true) { killAll() }, lp(2f))
            addView(pill("Yenile", false) { refresh() }, lp(1f, 10))
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hoursBtn, lp(1f)); addView(autoBtn, lp(1f, 8)); addView(serviceBtn, lp(1f, 8))
        }

        // Liste
        list = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT); dividerHeight = px(8)
            selector = ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false; setPadding(0, px(4), 0, px(8))
            isVerticalScrollBarEnabled = false
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
                    background = states(CARD, CARD_HI, 18)
                    minimumHeight = px(if (phone) 64 else 76)
                    setPadding(px(12), px(10), px(14), px(10))
                }
                val ic = if (phone) 44 else 52
                h.addView(ImageView(this@TaskManagerActivity).apply {
                    runCatching { setImageDrawable(packageManager.getApplicationIcon(r.pkg)) }
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, o: Outline) { o.setRoundRect(0, 0, view.width, view.height, px(11).toFloat()) }
                    }
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(px(ic), px(ic))
                })
                val t = LinearLayout(this@TaskManagerActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(px(14), 0, px(8), 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                t.addView(TextView(this@TaskManagerActivity).apply {
                    text = r.label; textSize = if (phone) 17f else 20f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                t.addView(TextView(this@TaskManagerActivity).apply {
                    text = r.pkg; textSize = 12f; setTextColor(MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                h.addView(t)
                if (kept) h.addView(TextView(this@TaskManagerActivity).apply {
                    text = "KORUMALI"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(ACCENT)
                    background = shape(Color.parseColor("#33FFC72C"), 10)
                    setPadding(px(10), px(4), px(10), px(4))
                })
                return h
            }
        }
        list.adapter = adapter

        val empty = TextView(this).apply {
            text = "Liste boş\nKapattıkların tekrar açılana kadar gizlenir.\n'Süre'yi artırmayı dene."
            textSize = 15f; setTextColor(MUTED); gravity = Gravity.CENTER; setPadding(px(16), px(48), px(16), 0)
        }
        val hint = TextView(this).apply {
            text = if (phone) "Dokun: kapat  •  Basılı tut: koru" else "OK: kapat  •  OK basılı / YEŞİL: koru  •  MENU / KIRMIZI: hepsini kapat"
            textSize = 12f; setTextColor(MUTED); gravity = Gravity.CENTER; setPadding(0, px(6), 0, 0)
        }

        fun gap(dp: Int) = View(this).also { it.layoutParams = LinearLayout.LayoutParams(1, px(dp)) }
        root.addView(title); root.addView(info); root.addView(gap(12))
        root.addView(ramCard); root.addView(gap(12))
        root.addView(row1); root.addView(gap(8)); root.addView(row2); root.addView(gap(8))
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(empty, LinearLayout.LayoutParams(-1, -2))
        root.addView(hint)
        list.emptyView = empty
        setContentView(root)
        hoursBtn.text = "Süre: ${Cleaner.hours(this)} sa"
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
        val (avail, total) = Cleaner.memMb(this)
        val used = (total - avail).coerceAtLeast(0)
        val pct = if (total > 0) (used * 100 / total).toInt() else 0
        val color = when { pct < 65 -> Color.parseColor("#3DDC84"); pct < 82 -> ACCENT; else -> DANGER }
        memTv.text = "$avail MB boş"
        memSub.text = "Toplam $total MB  •  %$pct kullanımda"
        (barFill.layoutParams as LinearLayout.LayoutParams).weight = used.toFloat().coerceAtLeast(1f)
        (barFree.layoutParams as LinearLayout.LayoutParams).weight = avail.toFloat().coerceAtLeast(1f)
        barFill.background = shape(color, 5)
        barFill.requestLayout(); barFree.requestLayout()
        info.text = extra ?: "${rows.size} uygulama arkaplanda"
    }

    private fun serviceOn() = ForceStopService.instance != null

    private fun updateService() {
        serviceBtn.text = if (serviceOn()) "Servis: Açık" else "Servis: Kapalı"
        serviceBtn.setTextColor(if (serviceOn()) Color.parseColor("#3DDC84") else DANGER)
    }

    private fun needService() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Servis kapalı")
            .setMessage("Uygulamaları gerçekten kapatmak için erişilebilirlik servisi açık olmalı.\n\n" +
                "1) Android 13 ve üstünde önce: Ayarlar > Uygulamalar > Arkaplan > ⋮ menüsü > 'Kısıtlanmış ayarlara izin ver'.\n" +
                "2) Sonra Erişilebilirlik > Yüklü uygulamalar > Arkaplan Yöneticisi'ni aç.")
            .setPositiveButton("Ayarları aç") { _, _ -> runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun refresh() {
        updateService()
        hoursBtn.text = "Süre: ${Cleaner.hours(this)} sa"
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
            Toast.makeText(this, "Tam kapatma için 'Servis' butonundan erişilebilirlik servisini aç", Toast.LENGTH_LONG).show()
            ui.postDelayed({ status(msg) }, 1500)   // RAM'in toparlanması için kısa bekleme
        }
    }

    private fun killOne(pos: Int) {
        if (pos !in rows.indices) return
        val r = rows[pos]
        if (r.pkg in keep) { Toast.makeText(this, "${r.label} korumalı (basılı tutup kaldır)", Toast.LENGTH_SHORT).show(); return }
        if (!serviceOn()) { Cleaner.softKill(this, listOf(r.pkg), false); needService(); return }
        rows.removeAt(pos)
        afterStop("${r.label} kapatıldı", Cleaner.stop(this, listOf(r.pkg), true))
    }

    private fun killAll() {
        val targets = rows.filter { it.pkg !in keep }
        if (targets.isEmpty()) { Toast.makeText(this, "Kapatılacak uygulama yok", Toast.LENGTH_SHORT).show(); return }
        if (!serviceOn()) { Cleaner.softKill(this, targets.map { it.pkg }, false); needService(); return }
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
