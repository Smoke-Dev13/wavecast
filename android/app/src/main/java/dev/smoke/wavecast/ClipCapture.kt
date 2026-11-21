package dev.smoke.wavecast

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.io.ByteArrayOutputStream

/**
 * Клип в эфир: когда на экране видеоплеер (YouTube и т.п.), снимаем экран тем же разрешением,
 * что и звук, и отдаём кадры JPEG ~12 к/с. Как только ушёл из плеера — кадры не идут,
 * у друзей снова обложка. Что открыто на экране, узнаём через «Доступ к истории использования».
 */
class ClipCapture(
    private val ctx: Context,
    private val mp: MediaProjection,
    private val onFrame: (ByteArray) -> Unit,
    private val onState: (Boolean) -> Unit,
    private val onPalette: (IntArray) -> Unit,
) {
    private var frames = 0
    private val thread = HandlerThread("wc-clip", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val h = Handler(thread.looper)
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var w = 0; private var hgt = 0
    private var bmp: Bitmap? = null
    private val jpeg = ByteArrayOutputStream(64 * 1024)
    @Volatile private var running = true
    private var on = false
    private var fgPkg: String? = null
    private var lastQuery = 0L
    private var lastCheck = 0L

    fun start() = h.post {
        runCatching { setup() }.onFailure { running = false }
        h.post(tick)
    }

    fun stop() {
        running = false
        h.post {
            if (on) { on = false; onState(false) }
            runCatching { display?.release() }; display = null
            runCatching { reader?.close() }; reader = null
            bmp?.recycle(); bmp = null
            thread.quitSafely()
        }
    }

    /** Размер снимка: длинная сторона 640 px, с учётом поворота экрана. */
    private fun target(): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        val k = 640f / maxOf(m.widthPixels, m.heightPixels)
        return ((m.widthPixels * k).toInt() and 0xFFFE).coerceAtLeast(2) to ((m.heightPixels * k).toInt() and 0xFFFE).coerceAtLeast(2)
    }

    private fun setup() {
        val (tw, th) = target()
        w = tw; hgt = th
        reader = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2)
        // Android 14: одно разрешение — один виртуальный экран; создаём его один раз и дальше только меняем размер
        display = mp.createVirtualDisplay("wavecast-clip", w, hgt, ctx.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, h)
    }

    private fun resizeIfRotated() {
        val (tw, th) = target()
        if (tw == w && th == hgt) return
        w = tw; hgt = th
        val old = reader
        reader = ImageReader.newInstance(w, hgt, PixelFormat.RGBA_8888, 2)
        display?.resize(w, hgt, ctx.resources.displayMetrics.densityDpi)
        display?.surface = reader!!.surface
        runCatching { old?.close() }
        bmp?.recycle(); bmp = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            if (now - lastCheck > 1000) {                         // раз в секунду: что на экране и не повернули ли телефон
                lastCheck = now
                runCatching { resizeIfRotated() }
                val want = display != null && isVideoApp(foreground())
                if (want != on) { on = want; onState(want) }
            }
            if (on) runCatching { grab() }
            h.postDelayed(this, 83)                               // ~12 кадров в секунду
        }
    }

    private fun grab() {
        val img = reader?.acquireLatestImage() ?: return
        try {
            val plane = img.planes[0]
            val rowPx = plane.rowStride / plane.pixelStride
            val b = bmp?.takeIf { it.width == rowPx && it.height == hgt }
                ?: Bitmap.createBitmap(rowPx, hgt, Bitmap.Config.ARGB_8888).also { bmp?.recycle(); bmp = it }
            b.copyPixelsFromBuffer(plane.buffer)
            val frame = if (rowPx == w) b else Bitmap.createBitmap(b, 0, 0, w, hgt)
            if (frames++ % 5 == 0) onPalette(ambilight(frame))          // подсветка по кадру ~2 раза в секунду
            jpeg.reset()
            frame.compress(Bitmap.CompressFormat.JPEG, 60, jpeg)
            if (frame !== b) frame.recycle()
            onFrame(jpeg.toByteArray())
        } finally { img.close() }
    }

    /** Какое приложение сейчас на экране — по событиям «вышло на передний план». */
    private fun foreground(): String? {
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return null
        val end = System.currentTimeMillis()
        val begin = if (lastQuery == 0L) end - 60 * 60_000 else lastQuery - 2000
        lastQuery = end
        val ev = runCatching { usm.queryEvents(begin, end) }.getOrNull() ?: return fgPkg
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) fgPkg = e.packageName
        }
        return fgPkg
    }

    /** Подсветка «как в кадре»: цвет каждой четверти экрана, ярче и сочнее — под неон. */
    private fun ambilight(f: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(f, 8, 8, true)
        val out = IntArray(4)
        val hsv = FloatArray(3)
        for (q in 0 until 4) {
            var r = 0L; var g = 0L; var bl = 0L; var n = 0
            val x0 = (q % 2) * 4; val y0 = (q / 2) * 4
            for (x in x0 until x0 + 4) for (y in y0 until y0 + 4) {
                val c = s.getPixel(x, y); r += (c shr 16) and 255; g += (c shr 8) and 255; bl += c and 255; n++
            }
            android.graphics.Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (bl / n).toInt(), hsv)
            hsv[1] = (hsv[1] * 1.6f + 0.25f).coerceAtMost(1f)                     // сочнее
            hsv[2] = hsv[2].coerceIn(0.55f, 1f)                                   // светится даже на тёмной сцене
            out[q] = android.graphics.Color.HSVToColor(hsv)
        }
        if (s !== f) s.recycle()
        return out
    }

    companion object {
        /** Видеоплееры: только с них идёт картинка. Браузеры не берём — в них может быть что угодно личное. */
        val VIDEO_APPS = setOf(
            "com.google.android.youtube", "app.revanced.android.youtube", "app.rvx.android.youtube",
            "com.vanced.android.youtube", "org.schabi.newpipe", "com.github.libretube",
            "com.google.android.apps.youtube.music", "app.revanced.android.apps.youtube.music",
            "ru.rutube.app", "com.vk.vkvideo", "com.vkontakte.android", "ru.vk.video",
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "tv.twitch.android.app",
            "org.videolan.vlc", "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro",
            "is.xyz.mpv", "com.netflix.mediaclient", "ru.kinopoisk", "ru.ivi.client",
        )

        fun isVideoApp(pkg: String?) = pkg != null && pkg in VIDEO_APPS

        /** Разрешён ли «Доступ к истории использования» — без него клип не показываем. */
        fun allowed(ctx: Context): Boolean {
            val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
