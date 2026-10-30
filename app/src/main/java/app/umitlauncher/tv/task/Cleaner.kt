package app.umitlauncher.tv.task

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process

/** Ortak mantık: aday listesi, koruma listesi, ayarlar, RAM, kapatma. */
object Cleaner {
    data class App(val pkg: String, val label: String)

    private val SKIP = setOf("com.google.android.gms", "com.google.android.gsf")

    private fun cfg(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun killedPrefs(c: Context) = c.getSharedPreferences("killed", Context.MODE_PRIVATE)

    // ---- Koruma listesi ----
    fun keep(c: Context): Set<String> = HashSet(cfg(c).getStringSet("keep", emptySet()) ?: emptySet())
    fun toggleKeep(c: Context, pkg: String): Boolean {
        val s = HashSet(keep(c))
        val on = if (s.contains(pkg)) { s.remove(pkg); false } else { s.add(pkg); true }
        cfg(c).edit().putStringSet("keep", s).apply()
        return on
    }

    // ---- Ayarlar ----
    fun hours(c: Context) = cfg(c).getInt("hours", 24)
    fun setHours(c: Context, h: Int) = cfg(c).edit().putInt("hours", h).apply()
    /** 0 kapalı, 1 ekran kapanınca, 2 2 saatte bir, 3 ikisi */
    fun auto(c: Context) = cfg(c).getInt("auto", 0)
    fun setAuto(c: Context, a: Int) = cfg(c).edit().putInt("auto", a).apply()

    // ---- RAM ----
    fun mem(c: Context): String {
        val mi = ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return "RAM: ${mi.availMem shr 20} MB boş / ${mi.totalMem shr 20} MB"
    }

    /** (boş MB, toplam MB) */
    fun memMb(c: Context): Pair<Long, Long> {
        val mi = ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return Pair(mi.availMem shr 20, mi.totalMem shr 20)
    }

    @Suppress("DEPRECATION")
    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }

    // ---- Aday listesi ----
    @Suppress("DEPRECATION")
    fun candidates(c: Context): List<App> {
        val pm = c.packageManager
        val home = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val ev = usm.queryEvents(end - hours(c) * 3600_000L, end)
        val e = UsageEvents.Event()
        val last = HashMap<String, Long>()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last[e.packageName] = e.timeStamp
        }
        val killed = killedPrefs(c)
        val found = LinkedHashSet<String>()
        last.entries.sortedByDescending { it.value }.forEach {
            if (killed.getLong(it.key, 0L) < it.value) found.add(it.key)
        }
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.forEach { p -> p.pkgList?.forEach { found.add(it) } }

        val out = ArrayList<App>()
        for (pkg in found) {
            if (pkg == c.packageName || pkg in home || pkg in SKIP) continue
            val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (sys) continue
            if ((ai.flags and ApplicationInfo.FLAG_STOPPED) != 0) continue
            out.add(App(pkg, pm.getApplicationLabel(ai).toString()))
        }
        return out
    }

    // ---- Kapatma ----
    /** Sadece arkaplan sürecini öldürür (ekransız, sessiz). */
    fun softKill(c: Context, pkgs: List<String>, mark: Boolean = true) {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val ed = killedPrefs(c).edit()
        val now = System.currentTimeMillis()
        pkgs.forEach { if (mark) ed.putLong(it, now); am.killBackgroundProcesses(it) }
        ed.apply()
    }

    /** softKill + servis açıksa Ayarlar'dan zorla durdur. Servis varsa true döner. */
    fun stop(c: Context, pkgs: List<String>, backToApp: Boolean): Boolean {
        val svc = ForceStopService.instance != null
        softKill(c, pkgs, svc)   // servis yoksa "kapandı" diye gizleme
        return svc && ForceStopService.enqueue(pkgs, backToApp)
    }
}
