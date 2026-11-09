package br.com.intelientrega.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.graphics.toArgb
import br.com.intelientrega.domain.CostSettings
import br.com.intelientrega.domain.OfferCalculator
import br.com.intelientrega.domain.OfferDraft
import br.com.intelientrega.domain.Semaphore
import br.com.intelientrega.domain.Signal
import br.com.intelientrega.ui.Brand
import br.com.intelientrega.ui.color
import br.com.intelientrega.parsing.Platform
import br.com.intelientrega.ui.OverlayPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs

sealed interface PanelUi {
    data object Hidden : PanelUi
    data object Analyzing : PanelUi
    data class Message(val title: String, val body: String) : PanelUi
    data class Offer(val draft: OfferDraft) : PanelUi
}

/**
 * Bolinha (View simples, arrastável) + painel (Compose) em janelas TYPE_APPLICATION_OVERLAY.
 * Só existe enquanto há sessão de captura ativa.
 */
class OverlayController(private val context: Context, private val actions: Actions) {

    interface Actions {
        fun onBubbleTap()
        fun onReanalyze()
        fun onStopSession()
        fun onPlatformChange(p: Platform)
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayLifecycleOwner().apply { create() }
    private val density = context.resources.displayMetrics.density

    private var panelState by mutableStateOf<PanelUi>(PanelUi.Hidden)
    private var settingsState by mutableStateOf(CostSettings())
    private var platformState by mutableStateOf(Platform.GENERICO)

    var settings: CostSettings
        get() = settingsState
        set(v) { settingsState = v }

    /** Último rascunho exibido (inclui correções do usuário, como a carga da bateria). */
    val lastDraft: OfferDraft? get() = (panelState as? PanelUi.Offer)?.draft ?: keptDraft
    private var keptDraft: OfferDraft? = null

    var panel: PanelUi
        get() = panelState
        set(v) {
            (panelState as? PanelUi.Offer)?.let { keptDraft = it.draft }
            panelState = v
            if (v is PanelUi.Offer) { platformState = v.draft.platform; tintBubble(v.draft) }
            if (v == PanelUi.Hidden) removePanel() else ensurePanel()
        }

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelView: ComposeView? = null

    // ---------- Bolinha ----------

    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() {
        if (bubble != null) return
        val size = (56 * density).toInt()
        val view = TextView(context).apply {
            text = "R$?"
            setTextColor(Brand.OnYellow.toArgb())
            textSize = 15f
            typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(context, br.com.intelientrega.R.font.sora_extrabold) }.getOrNull()
            gravity = Gravity.CENTER
            contentDescription = "Analisar oferta na tela"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Brand.Yellow.toArgb())
                setStroke((3 * density).toInt(), Brand.Bg.toArgb())
            }
            elevation = 8 * density
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (8 * density).toInt()
            y = (220 * density).toInt()
        }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) v.performClick()
            }
            true
        }
        view.setOnClickListener { actions.onBubbleTap() }
        wm.addView(view, params)
        bubble = view
        bubbleParams = params
    }

    /** Cor da bolinha = veredito da última oferta (amarelo quando não há veredito claro). */
    private fun tintBubble(d: OfferDraft) {
        val view = bubble as? TextView ?: return
        val signal = runCatching { Semaphore.of(OfferCalculator.calculate(d.toInput(settingsState), settingsState).verdict) }
            .getOrDefault(Signal.NEUTRO)
        val bg = if (signal == Signal.NEUTRO) Brand.Yellow else signal.color()
        (view.background as? GradientDrawable)?.setColor(bg.toArgb())
        view.setTextColor(if (signal == Signal.NEUTRO) Brand.OnYellow.toArgb() else Color.BLACK)
    }

    // ---------- Painel ----------

    private fun ensurePanel() {
        if (panelView != null) return
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                OverlayPanel(
                    ui = panelState,
                    settings = settingsState,
                    platform = platformState,
                    onDraftChange = { d -> panelState = PanelUi.Offer(d); keptDraft = d; tintBubble(d) },
                    onPlatformChange = { p -> platformState = p; actions.onPlatformChange(p) },
                    onReanalyze = actions::onReanalyze,
                    onClose = { panel = PanelUi.Hidden },
                    onStopSession = actions::onStopSession,
                )
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focável para permitir digitar correções; toques fora do painel seguem para o app de baixo.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(view, params)
        owner.resume()
        panelView = view
    }

    private fun removePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
    }

    // ---------- Captura limpa ----------

    /**
     * Esconde a bolinha e o painel e espera dois quadros para a mudança chegar à tela.
     * Retorna o instante (uptime) em que a ocultação foi pedida.
     */
    suspend fun hideForCapture(): Long {
        val t = SystemClock.uptimeMillis()
        bubble?.visibility = View.INVISIBLE
        panelView?.visibility = View.INVISIBLE
        awaitFrame(); awaitFrame()
        delay(50)
        return t
    }

    fun restoreAfterCapture() {
        bubble?.visibility = View.VISIBLE
        panelView?.visibility = View.VISIBLE
    }

    private suspend fun awaitFrame() = suspendCancellableCoroutine<Unit> { cont ->
        Choreographer.getInstance().postFrameCallback { if (cont.isActive) cont.resume(Unit) }
    }

    fun destroy() {
        removePanel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        owner.destroy()
    }
}
