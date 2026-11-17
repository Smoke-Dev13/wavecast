package dev.smoke.wavecast

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings as SysSettings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Обновления: сайт сервиса раздаёт /app/latest.json и сам APK.
 * Подлинность: SHA-256 из latest.json + Android ставит обновление только с той же подписью,
 * так что подменить приложение чужой сборкой нельзя.
 */
object Updater {
    const val LATEST_URL = "https://${Settings.DEFAULT_SERVICE}/app/latest.json"
    const val REPO_URL = "https://github.com/Smoke-Dev13/wavecast"

    data class Release(val versionCode: Long, val versionName: String, val url: String,
                       val sha256: String, val size: Long, val notes: String)

    private val main = Handler(Looper.getMainLooper())
    /** Последняя найденная новая версия (живёт, пока жив процесс). */
    @Volatile var available: Release? = null; private set
    @Volatile private var lastCheck = 0L

    fun currentCode(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    fun currentName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Проверяет обновление в фоне. force=false — не чаще раза в 30 минут. done(новая версия или null, ошибка). */
    fun check(ctx: Context, force: Boolean = false, done: (Release?, String?) -> Unit) {
        if (!force && System.currentTimeMillis() - lastCheck < 30 * 60_000) { done(available, null); return }
        val app = ctx.applicationContext
        thread(name = "wc-update-check", isDaemon = true) {
            val r = runCatching {
                val o = JSONObject(fetch(LATEST_URL).toString(Charsets.UTF_8))
                Release(o.getLong("versionCode"), o.getString("versionName"),
                    URL(URL(LATEST_URL), o.getString("url")).toString(),
                    o.getString("sha256").lowercase(), o.optLong("size"), o.optString("notes"))
            }
            lastCheck = System.currentTimeMillis()
            val rel = r.getOrNull()?.takeIf { it.versionCode > currentCode(app) }
            available = rel
            main.post { done(rel, r.exceptionOrNull()?.let { "нет связи с сервером обновлений" }) }
        }
    }

    private fun fetch(url: String, progress: ((Int) -> Unit)? = null, out: File? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { inp ->
                if (out == null) return inp.readBytes()
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var got = 0L; var last = -1
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        o.write(buf, 0, n); got += n
                        if (total > 0) { val pct = (got * 100 / total).toInt(); if (pct != last) { last = pct; progress?.invoke(pct) } }
                    }
                }
                return ByteArray(0)
            }
        } finally { c.disconnect() }
    }

    /** Можно ли сейчас ставить APK. Если нет — открывает системную настройку «Установка неизвестных приложений». */
    fun ensureInstallAllowed(ctx: Context): Boolean {
        if (ctx.packageManager.canRequestPackageInstalls()) return true
        ctx.startActivity(Intent(SysSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }

    /** Скачивает, проверяет SHA-256 и запускает системную установку. state — текст для кнопки. */
    fun downloadAndInstall(ctx: Context, rel: Release, state: (String?, Boolean) -> Unit) {
        val app = ctx.applicationContext
        state("Скачиваю…", false)
        thread(name = "wc-update", isDaemon = true) {
            val file = File(app.cacheDir, "update.apk")
            val err = runCatching {
                fetch(rel.url, { pct -> main.post { state("Скачиваю… $pct%", false) } }, file)
                val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                check(sha == rel.sha256) { "файл повреждён при загрузке, попробуй ещё раз" }
                install(app, file)
            }.exceptionOrNull()
            main.post {
                if (err != null) { file.delete(); state("Не получилось: ${err.message ?: "ошибка сети"}", true) }
                else state("Подтверди установку", true)
            }
        }
    }

    private fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("wavecast.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val cb = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(cb.intentSender)
        }
    }
}

/** Ответ системы на установку: показывает окно подтверждения или сообщает об ошибке. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit   // приложение перезапустится уже новой версией
            else -> Toast.makeText(ctx, "Обновление не установлено: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "ошибка"), Toast.LENGTH_LONG).show()
        }
    }
}
