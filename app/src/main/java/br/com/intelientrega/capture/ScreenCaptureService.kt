package br.com.intelientrega.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import br.com.intelientrega.MainActivity
import br.com.intelientrega.R
import br.com.intelientrega.account.AccountRepository
import br.com.intelientrega.data.SettingsRepository
import br.com.intelientrega.domain.CostSettings
import br.com.intelientrega.domain.OfferDraft
import br.com.intelientrega.ocr.MlKitOcrEngine
import br.com.intelientrega.ocr.OcrEngine
import br.com.intelientrega.overlay.OverlayController
import br.com.intelientrega.overlay.PanelUi
import br.com.intelientrega.parsing.OcrText
import br.com.intelientrega.parsing.OfferParsers
import br.com.intelientrega.parsing.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Uma sessão = um consentimento = um MediaProjection = um VirtualDisplay.
 * Quando a sessão termina (usuário, chip da barra de status, bloqueio de tela, outra projeção,
 * revogação), tudo é liberado e o token NÃO é reaproveitado: nova sessão exige novo consentimento.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ValeCaptura"
        private const val ACTION_START = "br.com.intelientrega.START"
        private const val ACTION_STOP = "br.com.intelientrega.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL = "sessao_captura"
        private const val NOTIF_ID = 42

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _lastEndReason = MutableStateFlow<String?>(null)
        val lastEndReason: StateFlow<String?> = _lastEndReason.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, i)
        }

        fun stopIntent(context: Context) = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var settingsRepo: SettingsRepository

    private var projection: MediaProjection? = null
    private var grabber: FrameGrabber? = null
    private var overlay: OverlayController? = null
    private var ocr: OcrEngine? = null
    private var receiverRegistered = false
    private var stopping = false
    private var analyzing = false
    /** false quando o usuário compartilhou só um app e ele não está visível (Android 14+). */
    private var capturedContentVisible = true

    /** Último texto OCR, para reinterpretar ao trocar de plataforma. A imagem em si nunca é guardada. */
    private var lastOcr: OcrText? = null
    private var platform = Platform.GENERICO
    @Volatile private var settings = CostSettings()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Chip da barra de status, bloqueio de tela, outra sessão, revogação pelo sistema...
            mainHandler.post { endSession("A captura foi encerrada pelo sistema ou pelo usuário.") }
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            grabber?.resize(width, height)
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            capturedContentVisible = isVisible
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) endSession("Tela bloqueada: a sessão de captura foi encerrada.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsRepo = SettingsRepository(this)
        scope.launch { settingsRepo.settings.collect { settings = it; overlay?.settings = it } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> endSession("Sessão encerrada por você.")
            ACTION_START -> startSession(intent)
            // Reinício pelo sistema sem token: não há como (nem devemos) retomar a captura.
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return }

        // Uma sessão ativa já existe? Encerra-a antes: cada token é usado uma vez só.
        if (projection != null) releaseResources()
        stopping = false

        // 1) Primeiro plano com tipo mediaProjection ANTES de obter o MediaProjection (exigência do Android 14+).
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar primeiro plano", e)
            fail("Não foi possível iniciar o serviço de captura.")
            return
        }

        // 2) Token → callback → VirtualDisplay (nessa ordem).
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data) ?: run { fail("Permissão de captura inválida."); return }
            p.registerCallback(projectionCallback, mainHandler)
            projection = p
            grabber = FrameGrabber(this, p).also { it.start() }
        } catch (e: SecurityException) {
            Log.e(TAG, "Token de captura recusado", e)
            fail("O Android recusou a captura. Inicie uma nova sessão.")
            return
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Projeção já encerrada", e)
            fail("A sessão de captura já tinha sido encerrada. Inicie outra.")
            return
        }

        ocr = MlKitOcrEngine()
        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true

        if (!AccountRepository.get(this).hasAccess()) {
            fail("Assinatura inativa. Abra o app > Conta para assinar ou regularizar.")
            return
        }
        if (Settings.canDrawOverlays(this)) {
            overlay = OverlayController(this, actions).also { it.settings = settings; it.showBubble() }
        } else {
            fail("Permita a sobreposição a outros apps e inicie de novo.")
            return
        }
        _lastEndReason.value = null
        _running.value = true
    }

    private val actions = object : OverlayController.Actions {
        override fun onBubbleTap() = analyze()
        override fun onReanalyze() = analyze()
        override fun onStopSession() = endSession("Sessão encerrada por você.")
        override fun onPlatformChange(p: Platform) {
            platform = p
            lastOcr?.let { overlay?.panel = PanelUi.Offer(buildDraft(it)) }
        }
    }

    /** Captura sob demanda: um quadro, um OCR, nada contínuo. */
    private fun analyze() {
        if (analyzing) return
        val g = grabber ?: return
        val ov = overlay ?: return
        val engine = ocr ?: return
        if (!capturedContentVisible) {
            ov.panel = PanelUi.Message(
                "App compartilhado não está visível",
                "Você escolheu compartilhar só um app. Abra esse app (a oferta) e toque na bolinha de novo.",
            )
            return
        }
        // A assinatura pode vencer durante a sessão: confere a cada análise.
        if (!AccountRepository.get(this).hasAccess()) {
            ov.panel = PanelUi.Message(
                "Assinatura inativa",
                "Abra o InteliEntrega > Conta para ver o status e regularizar ou renovar.",
            )
            return
        }
        analyzing = true
        scope.launch {
            try {
                // Esconde bolinha e painel e espera alguns quadros, para não contaminar a imagem.
                val hiddenAt = ov.hideForCapture()
                val bitmap = g.captureAfter(hiddenAt)
                ov.restoreAfterCapture()
                if (bitmap == null) {
                    ov.panel = PanelUi.Message("Não recebi imagem da tela", "Tente de novo. Se persistir, encerre e inicie outra sessão.")
                    return@launch
                }
                ov.panel = PanelUi.Analyzing
                val text = try { engine.recognize(bitmap) } finally { bitmap.recycle() }
                lastOcr = text
                ov.panel = PanelUi.Offer(buildDraft(text))
            } catch (e: Exception) {
                Log.e(TAG, "Falha na análise", e)
                ov.restoreAfterCapture()
                ov.panel = PanelUi.Message("Falha ao ler a tela", e.message ?: "Erro desconhecido")
            } finally {
                analyzing = false
            }
        }
    }

    /** A carga da bateria (elétricos) é herdada da análise anterior, já que muda devagar. */
    private fun buildDraft(text: OcrText): OfferDraft =
        OfferDraft.from(OfferParsers.forPlatform(platform).parse(text), settings, previous = overlay?.lastDraft)

    private fun fail(reason: String) {
        _lastEndReason.value = reason
        endSession(reason)
    }

    /** Idempotente. Libera tudo e encerra o serviço. */
    private fun endSession(reason: String) {
        if (stopping) return
        stopping = true
        if (_lastEndReason.value == null) _lastEndReason.value = reason
        releaseResources()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources() {
        overlay?.destroy(); overlay = null
        grabber?.release(); grabber = null
        projection?.let { p ->
            p.unregisterCallback(projectionCallback)
            runCatching { p.stop() }
        }
        projection = null
        ocr?.close(); ocr = null
        lastOcr = null
        if (receiverRegistered) { runCatching { unregisterReceiver(screenOffReceiver) }; receiverRegistered = false }
        _running.value = false
    }

    override fun onDestroy() {
        releaseResources()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Sessão de captura", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Aparece enquanto a captura de tela está ativa"
                },
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(this, 1, stopIntent(this), flags)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Captura de tela ativa")
            .setContentText("Toque na bolinha para analisar uma oferta. Nada é gravado.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Encerrar", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
