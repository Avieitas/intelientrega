package br.com.intelientrega.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Cria UM VirtualDisplay por MediaProjection (regra do Android 14+) e guarda só o quadro mais recente.
 * Não roda OCR nenhum aqui: o quadro só vira Bitmap quando o usuário toca na bolinha.
 */
class FrameGrabber(private val context: Context, private val projection: MediaProjection) {
    private val thread = HandlerThread("vale-captura").apply { start() }
    private val handler = Handler(thread.looper)
    private val dispatcher = handler.asCoroutineDispatcher()
    private val lock = Any()

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var latest: Image? = null
    private var latestAtMs = 0L
    private var dpi = 0
    @Volatile private var released = false

    /** Deve ser chamado depois de MediaProjection.registerCallback(). */
    fun start() {
        val (w, h) = screenSize()
        dpi = context.resources.configuration.densityDpi
        val r = newReader(w, h)
        reader = r
        display = projection.createVirtualDisplay(
            "InteliEntrega", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler,
        )
    }

    /** Chamado por onCapturedContentResize (rotação, ou app único escolhido no Android 14+). */
    fun resize(width: Int, height: Int) = handler.post {
        if (released || width <= 0 || height <= 0) return@post
        val old = reader
        synchronized(lock) { latest?.close(); latest = null }
        val nr = newReader(width, height)
        display?.resize(width, height, dpi)
        display?.surface = nr.surface
        reader = nr
        old?.close()
    }

    private fun newReader(w: Int, h: Int) = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
        setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return@setOnImageAvailableListener
            synchronized(lock) {
                latest?.close()
                latest = img
                latestAtMs = SystemClock.uptimeMillis()
            }
        }, handler)
    }

    /**
     * Devolve um quadro que chegou DEPOIS de [hiddenAtMs] (quando a sobreposição foi escondida),
     * para a bolinha e o painel não aparecerem na imagem.
     * Prefere um quadro com pelo menos [settleMs] de folga; se a tela estiver parada e nenhum quadro
     * novo chegar, aceita qualquer quadro posterior ao momento em que escondemos a sobreposição.
     * Retorna null se não houver quadro seguro.
     */
    suspend fun captureAfter(hiddenAtMs: Long, settleMs: Long = 60, timeoutMs: Long = 900): Bitmap? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            grab { at -> at >= hiddenAtMs + settleMs }?.let { return it }
            delay(40)
        }
        return grab { at -> at > hiddenAtMs }
    }

    private suspend fun grab(accept: (Long) -> Boolean): Bitmap? = withContext(dispatcher) {
        synchronized(lock) {
            val img = latest
            if (img != null && accept(latestAtMs)) runCatching { img.toBitmap() }.getOrNull() else null
        }
    }

    fun release() {
        if (released) return
        released = true
        handler.post {
            synchronized(lock) { latest?.close(); latest = null }
            display?.release(); display = null
            reader?.close(); reader = null
            thread.quitSafely()
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }
}

private fun Image.toBitmap(): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer.duplicate().apply { rewind() }
    val paddedWidth = plane.rowStride / plane.pixelStride
    val tmp = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
    tmp.copyPixelsFromBuffer(buffer)
    if (paddedWidth == width) return tmp
    return Bitmap.createBitmap(tmp, 0, 0, width, height).also { tmp.recycle() }
}
