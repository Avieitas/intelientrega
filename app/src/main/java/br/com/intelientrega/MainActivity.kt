package br.com.intelientrega

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import br.com.intelientrega.account.AccountRepository
import br.com.intelientrega.capture.ScreenCaptureService
import br.com.intelientrega.data.SettingsRepository
import br.com.intelientrega.domain.CostSettings
import br.com.intelientrega.domain.OfferCalculator
import br.com.intelientrega.domain.OfferDraft
import br.com.intelientrega.domain.Semaphore
import br.com.intelientrega.ui.color
import br.com.intelientrega.domain.VehicleType
import br.com.intelientrega.domain.brl
import br.com.intelientrega.domain.plain
import br.com.intelientrega.parsing.BrNumbers
import br.com.intelientrega.parsing.OcrText
import br.com.intelientrega.parsing.OfferParsers
import br.com.intelientrega.parsing.Platform
import br.com.intelientrega.ui.AccessBlocked
import br.com.intelientrega.ui.AccountTab
import br.com.intelientrega.ui.AppCard
import br.com.intelientrega.ui.AppTheme
import br.com.intelientrega.ui.Brand
import br.com.intelientrega.ui.CheckStep
import br.com.intelientrega.ui.LogoMark
import br.com.intelientrega.ui.NumberField
import br.com.intelientrega.ui.OfferAnalysisCard
import br.com.intelientrega.ui.Pill
import br.com.intelientrega.ui.PlatformChips
import br.com.intelientrega.ui.PrimaryButton
import br.com.intelientrega.ui.SecondaryButton
import br.com.intelientrega.ui.SectionHeader
import br.com.intelientrega.ui.Segmented
import br.com.intelientrega.ui.Sora
import br.com.intelientrega.ui.UpdateBanner
import br.com.intelientrega.ui.fieldColors
import kotlinx.coroutines.launch

private const val TAB_HOME = 0
private const val TAB_TEST = 1
private const val TAB_COSTS = 2
private const val TAB_ACCOUNT = 3

class MainActivity : ComponentActivity() {
    /** Retorno do checkout: intelientrega://assinatura (aberto pela página de back_url). */
    private val returnedFromCheckout = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        handleIntent(intent)
        val repo = SettingsRepository(applicationContext)
        val account = AccountRepository.get(applicationContext)
        setContent { AppTheme { MainScreen(repo, account, returnedFromCheckout) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme == "intelientrega" && d.host == "assinatura") returnedFromCheckout.value = true
    }
}

private data class NavItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val NAV = listOf(
    NavItem("Início", Icons.Filled.Home),
    NavItem("Testar", Icons.Filled.Search),
    NavItem("Custos", Icons.Filled.Build),
    NavItem("Conta", Icons.Filled.AccountCircle),
)

@Composable
private fun MainScreen(
    repo: SettingsRepository,
    account: AccountRepository,
    returnedFromCheckout: androidx.compose.runtime.MutableState<Boolean>,
) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_HOME) }
    val settings by repo.settings.collectAsState(initial = null)
    val hasAccess by account.access.collectAsState()
    val user by account.account.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Ao voltar para o app, revalida no servidor (o servidor limita a frequência de consultas ao MP).
    LaunchedEffect(lifecycleOwner, user?.userId) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            account.hasAccess()
            if (user != null) runCatching { account.refreshStatus(returnedFromCheckout.value) }
        }
    }
    LaunchedEffect(returnedFromCheckout.value) {
        if (returnedFromCheckout.value) {
            tab = TAB_ACCOUNT
            if (user != null) runCatching { account.refreshStatus(true) }
            returnedFromCheckout.value = false
        }
    }

    Scaffold(
        containerColor = Brand.Bg,
        bottomBar = {
            NavigationBar(containerColor = Brand.Surface, tonalElevation = 0.dp) {
                NAV.forEachIndexed { i, item ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(item.icon, null) }, label = { Text(item.label, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Brand.OnYellow, indicatorColor = Brand.Yellow,
                            selectedTextColor = Brand.Yellow, unselectedIconColor = Brand.Muted, unselectedTextColor = Brand.Muted,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 24.dp),
        ) {
            UpdateBanner()
            when (tab) {
                // Recursos pagos: só com comprovante de acesso válido emitido pelo servidor.
                TAB_HOME -> HomeTab(s, hasAccess, account, onGoTo = { tab = it })
                TAB_TEST -> {
                    ScreenTitle("Testar uma oferta", "Cole o texto de uma oferta e veja o resultado sem abrir o 99 ou o iFood.")
                    if (hasAccess) TestTab(s) else AccessBlocked(account) { tab = TAB_ACCOUNT }
                }
                TAB_COSTS -> {
                    ScreenTitle("Custos e metas", "Usados em todas as contas. Ficam só no seu celular.")
                    SettingsTab(s) { repo.save(it) }
                }
                TAB_ACCOUNT -> {
                    ScreenTitle("Conta", null)
                    AccountTab(account)
                }
            }
        }
    }
}

@Composable
private fun ScreenTitle(title: String, subtitle: String?) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
    }
}

// ------------------------------------------------------------------ Início

@Composable
private fun HomeTab(s: CostSettings, hasAccess: Boolean, account: AccountRepository, onGoTo: (Int) -> Unit) {
    val status by account.status.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        LogoMark(44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("InteliEntrega", style = MaterialTheme.typography.titleLarge)
            Text("Seu copiloto de ofertas", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
        }
        when {
            hasAccess && status?.state == "courtesy" -> Pill("Cortesia", Brand.Good)
            hasAccess -> Pill("Assinante", Brand.Good)
            else -> Pill("Sem assinatura", Brand.Mid)
        }
    }
    Spacer(Modifier.height(16.dp))
    if (hasAccess) SessionCard() else AccessBlocked(account) { onGoTo(TAB_ACCOUNT) }
    GoalsCard(s) { onGoTo(TAB_COSTS) }
    TipsCard()
}

@Composable
private fun SessionCard() {
    val ctx = LocalContext.current
    val running by ScreenCaptureService.running.collectAsState()
    val endReason by ScreenCaptureService.lastEndReason.collectAsState()
    var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var canNotify by remember { mutableStateOf(hasNotificationPermission(ctx)) }

    // Reavalia permissões ao voltar das telas de configuração do Android.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            canOverlay = Settings.canDrawOverlays(ctx)
            canNotify = hasNotificationPermission(ctx)
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = it }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == android.app.Activity.RESULT_OK && data != null) {
            // O Intent de consentimento vai direto para o serviço e é usado UMA vez.
            ScreenCaptureService.start(ctx, res.resultCode, data)
            Toast.makeText(ctx, "Pronto! Abra a oferta e toque na bolinha R$?", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(ctx, "Captura não autorizada.", Toast.LENGTH_SHORT).show()
        }
    }
    val needNotify = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val ready = canOverlay && (!needNotify || canNotify)

    AppCard(border = if (running) Brand.Good.copy(alpha = 0.6f) else Brand.Outline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (running) Brand.Good else Brand.Muted, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(if (running) "Leitura ligada" else "Leitura desligada", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (running) "Abra o 99 ou o iFood. Quando chegar uma oferta, toque na bolinha amarela R$? para ver se vale a pena."
            else "Ligue a leitura antes de começar a rodar. O Android pede autorização a cada sessão.",
            style = MaterialTheme.typography.bodyMedium, color = Brand.Muted,
        )
        Spacer(Modifier.height(14.dp))
        if (running) {
            PrimaryButton("Encerrar sessão", { ctx.startService(ScreenCaptureService.stopIntent(ctx)) },
                color = Brand.SurfaceHigh, contentColor = Brand.Text)
        } else {
            PrimaryButton("Iniciar sessão", {
                val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }, enabled = ready, icon = Icons.Filled.PlayArrow)
        }
        endReason?.let { if (!running) Text("Última sessão: $it", style = MaterialTheme.typography.bodySmall, color = Brand.Muted, modifier = Modifier.padding(top = 8.dp)) }

        if (!ready || !running) {
            Spacer(Modifier.height(10.dp))
            Text("ANTES DE COMEÇAR", style = MaterialTheme.typography.labelSmall, color = Brand.Muted)
            CheckStep(canOverlay, 1, "Mostrar sobre outros apps", "Para a bolinha e o resultado aparecerem em cima do 99/iFood.") {
                if (!canOverlay) SecondaryButton("Permitir", {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                })
            }
            if (needNotify) CheckStep(canNotify, 2, "Notificações", "Mostram a sessão ativa e o botão Encerrar.") {
                if (!canNotify) SecondaryButton("Permitir", { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) })
            }
            CheckStep(running, if (needNotify) 3 else 2, "Iniciar sessão", "Escolha \"Tela inteira\" quando o Android perguntar.")
        }
    }
}

@Composable
private fun GoalsCard(s: CostSettings, onEdit: () -> Unit) {
    SectionHeader("Suas metas", "Verde: bateu a meta · Laranja: perto (80%) · Vermelho: abaixo")
    AppCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GoalBox("Meta hora", if (s.targetSurplusPerHour > 0) brl(s.targetSurplusPerHour) else "—", "/h", Modifier.weight(1f))
            GoalBox("Meta km", s.targetGrossPerKm?.let { brl(it) } ?: "—", "/km", Modifier.weight(1f))
            GoalBox("Seu custo", brl(s.energyPerKm + s.otherCostsPerKm), "/km", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().clickable(onClick = onEdit), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Edit, null, tint = Brand.Yellow, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "${if (s.isElectric) "Elétrica" else "Moto a combustível"} · ajustar custos e metas",
                color = Brand.Yellow, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun GoalBox(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier.background(Brand.SurfaceHigh, RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Brand.Muted, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value.removePrefix("R$ "), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1)
            if (value != "—") Text(unit, fontSize = 11.sp, color = Brand.Muted, modifier = Modifier.padding(start = 1.dp, bottom = 2.dp))
        }
    }
}

@Composable
private fun TipsCard() {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionHeader("Bom saber")
    AppCard {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("Privacidade e como funciona", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = Brand.Muted)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 8.dp)) {
                listOf(
                    "App independente: não tem vínculo com a 99, o iFood ou outras plataformas.",
                    "Só lê a tela quando você toca na bolinha. Não aceita nem recusa corridas por você.",
                    "A leitura é feita no próprio celular. Nenhuma imagem é salva ou enviada.",
                    "Alguns apps bloqueiam capturas. Se a leitura vier vazia, use \"Corrigir dados\".",
                    "Ao bloquear a tela, a sessão termina e precisa ser iniciada de novo.",
                ).forEach {
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Box(Modifier.padding(top = 7.dp).size(5.dp).background(Brand.Yellow, CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                    }
                }
            }
        }
    }
}

private fun hasNotificationPermission(ctx: android.content.Context) =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

// ------------------------------------------------------------------ Custos e metas

@Composable
private fun SettingsTab(current: CostSettings, onSave: suspend (CostSettings) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var vehicle by remember(current) { mutableStateOf(current.vehicleType) }
    var kwh by remember(current) { mutableStateOf(plain(current.batteryKwh)) }
    var kwhPrice by remember(current) { mutableStateOf(plain(current.energyPricePerKwh, 2)) }
    var range by remember(current) { mutableStateOf(plain(current.rangeKm)) }
    var minBat by remember(current) { mutableStateOf(plain(current.minBatteryReservePct)) }
    var fuel by remember(current) { mutableStateOf(plain(current.fuelPricePerLiter, 2)) }
    var kml by remember(current) { mutableStateOf(plain(current.kmPerLiter)) }
    var other by remember(current) { mutableStateOf(plain(current.otherCostsPerKm)) }
    var hour by remember(current) { mutableStateOf(plain(current.targetSurplusPerHour)) }
    var grossKm by remember(current) { mutableStateOf(current.targetGrossPerKm?.let { plain(it) } ?: "") }
    var wait by remember(current) { mutableStateOf(plain(current.extraWaitMin)) }
    var waitIncl by remember(current) { mutableStateOf(current.waitIncludedByDefault) }
    var repoKm by remember(current) { mutableStateOf(plain(current.defaultRepositionKm)) }
    var repoMin by remember(current) { mutableStateOf(plain(current.defaultRepositionMin)) }
    var errors by remember { mutableStateOf(emptyList<String>()) }

    Segmented(
        listOf(VehicleType.COMBUSTAO to "Moto a combustível", VehicleType.ELETRICO to "Elétrica / autopropelido"),
        vehicle,
    ) { vehicle = it }

    if (vehicle == VehicleType.ELETRICO) {
        SectionHeader("Bateria e recarga")
        AppCard {
            NumberField("Autonomia real com carga cheia", range, { range = it }, suffix = "km",
                help = "Rodando em entrega, com baú e subidas. Não use a do fabricante.")
            NumberField("Capacidade da bateria", kwh, { kwh = it }, suffix = "kWh",
                help = "Se a bateria mostra volts e Ah: V × Ah ÷ 1000. Ex.: 48 V × 20 Ah = 0,96 kWh.")
            NumberField("Tarifa de energia", kwhPrice, { kwhPrice = it }, suffix = "R$/kWh",
                help = "Está na conta de luz. Some uns 15% pela perda do carregador.")
            val chargeCost = (BrNumbers.parse(kwh) ?: 0.0) * (BrNumbers.parse(kwhPrice) ?: 0.0)
            if (chargeCost > 0) Pill("Uma carga cheia custa ≈ ${brl(chargeCost)}", Brand.Yellow, Modifier.padding(vertical = 4.dp))
            NumberField("Bateria mínima ao fim da entrega", minBat, { minBat = it }, suffix = "%")
            NumberField("Reserva por km", other, { other = it }, suffix = "R$/km",
                help = "Manutenção, pneus e a parte do preço de uma bateria nova por km rodado.")
        }
    } else {
        SectionHeader("Custos da moto")
        AppCard {
            NumberField("Preço do combustível", fuel, { fuel = it }, suffix = "R$/l")
            NumberField("Consumo da moto", kml, { kml = it }, suffix = "km/l")
            NumberField("Reserva por km", other, { other = it }, suffix = "R$/km",
                help = "Um valor único para manutenção, pneus, óleo e depreciação. Combustível fica de fora.")
        }
    }

    SectionHeader("Metas", "Definem as cores do semáforo em cada oferta.")
    AppCard {
        NumberField("Meta de sobra por hora", hour, { hour = it }, suffix = "R$/h")
        NumberField("Meta de valor por km (opcional)", grossKm, { grossKm = it }, suffix = "R$/km")
    }

    SectionHeader("Tempo e deslocamento extras")
    AppCard {
        NumberField("Espera adicional", wait, { wait = it }, suffix = "min")
        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("O tempo da oferta já inclui a espera", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = waitIncl, onCheckedChange = { waitIncl = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Brand.Yellow, checkedThumbColor = Brand.OnYellow))
        }
        NumberField("Volta / reposição após a entrega (opcional)", repoKm, { repoKm = it }, suffix = "km")
        NumberField("Tempo da volta (opcional)", repoMin, { repoMin = it }, suffix = "min")
    }

    if (errors.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        AppCard(border = Brand.Bad.copy(alpha = 0.6f)) {
            errors.forEach { Text("• $it", color = Brand.Bad, style = MaterialTheme.typography.bodySmall) }
        }
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Salvar", onClick = {
        fun n(s: String) = BrNumbers.parse(s) ?: Double.NaN
        // Campos do outro tipo de veículo (escondidos) mantêm o valor salvo se estiverem inválidos.
        fun keep(s: String, old: Double) = BrNumbers.parse(s) ?: old
        val electric = vehicle == VehicleType.ELETRICO
        val s = CostSettings(
            vehicleType = vehicle,
            batteryKwh = if (electric) n(kwh) else keep(kwh, current.batteryKwh),
            energyPricePerKwh = if (electric) n(kwhPrice) else keep(kwhPrice, current.energyPricePerKwh),
            rangeKm = if (electric) n(range) else keep(range, current.rangeKm),
            minBatteryReservePct = if (electric) n(minBat) else keep(minBat, current.minBatteryReservePct),
            fuelPricePerLiter = if (electric) keep(fuel, current.fuelPricePerLiter) else n(fuel),
            kmPerLiter = if (electric) keep(kml, current.kmPerLiter) else n(kml),
            otherCostsPerKm = n(other),
            targetSurplusPerHour = n(hour), targetGrossPerKm = grossKm.takeIf { it.isNotBlank() }?.let(::n),
            extraWaitMin = n(wait), waitIncludedByDefault = waitIncl,
            defaultRepositionKm = repoKm.takeIf { it.isNotBlank() }?.let(::n) ?: 0.0,
            defaultRepositionMin = repoMin.takeIf { it.isNotBlank() }?.let(::n) ?: 0.0,
        )
        errors = s.validate()
        if (errors.isEmpty()) scope.launch {
            onSave(s)
            Toast.makeText(ctx, "Custos e metas salvos", Toast.LENGTH_SHORT).show()
        }
    })
}

// ------------------------------------------------------------------ Teste sem captura

/** Permite validar parser e cálculo colando um texto, sem MediaProjection. */
@Composable
private fun TestTab(settings: CostSettings) {
    var text by rememberSaveable { mutableStateOf("Nova oferta\nR$ 12,50\nColeta 1,2 km\nEntrega 4,8 km\n25 min") }
    var platform by remember { mutableStateOf(Platform.GENERICO) }
    var draft by remember { mutableStateOf<OfferDraft?>(null) }

    AppCard {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 4,
            label = { Text("Texto da oferta") }, shape = RoundedCornerShape(14.dp), colors = fieldColors(),
        )
        Spacer(Modifier.height(8.dp))
        PlatformChips(platform) { platform = it }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Analisar", {
                draft = OfferDraft.from(OfferParsers.forPlatform(platform).parse(OcrText.fromPlainText(text)), settings)
            }, Modifier.weight(1f))
            SecondaryButton("Digitar à mão", { draft = OfferDraft(waitIncluded = settings.waitIncludedByDefault) },
                Modifier.weight(1f).height(54.dp))
        }
    }
    draft?.let { d ->
        Spacer(Modifier.height(12.dp))
        val verdict = remember(d, settings) { OfferCalculator.calculate(d.toInput(settings), settings).verdict }
        AppCard(border = Semaphore.of(verdict).color().copy(alpha = 0.5f)) {
            OfferAnalysisCard(d, settings, { draft = it }, editorOpen = true)
        }
    }
}
