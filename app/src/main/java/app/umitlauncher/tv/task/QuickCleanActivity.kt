package app.umitlauncher.tv.task

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Arayüzsüz tek tuşla temizlik. Launcher kısayoluna / kumanda tuşuna bağlanabilir.
 *  Intent action: app.umitlauncher.tv.task.CLEAN */
class QuickCleanActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (!Cleaner.hasUsageAccess(this)) {
            Toast.makeText(this, "Önce Arkaplan uygulamasından kullanım erişimi ver", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, TaskManagerActivity::class.java))
            finish(); return
        }
        val keep = Cleaner.keep(this)
        val targets = Cleaner.candidates(this).map { it.pkg }.filter { it !in keep }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Kapatılacak uygulama yok • " + Cleaner.mem(this), Toast.LENGTH_SHORT).show()
        } else {
            val full = Cleaner.stop(this, targets, false)
            Toast.makeText(
                this,
                if (full) "${targets.size} uygulama kapatılıyor…" else "${targets.size} uygulama için hafif kapatma denendi; tam kapanması için Servis (Erişilebilirlik) açık olmalı • " + Cleaner.mem(this),
                Toast.LENGTH_LONG
            ).show()
        }
        finish()
    }
}
