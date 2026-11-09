package br.com.intelientrega.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.intelientrega.domain.CalcResult
import br.com.intelientrega.domain.CostSettings
import br.com.intelientrega.domain.DraftField
import br.com.intelientrega.domain.FieldSource
import br.com.intelientrega.domain.OfferCalculator
import br.com.intelientrega.domain.OfferDraft
import br.com.intelientrega.domain.Semaphore
import br.com.intelientrega.domain.Signal
import br.com.intelientrega.domain.Verdict
import br.com.intelientrega.domain.brl
import br.com.intelientrega.domain.km
import br.com.intelientrega.domain.min
import br.com.intelientrega.domain.pct
import br.com.intelientrega.overlay.PanelUi
import br.com.intelientrega.parsing.Platform

/** Textos curtos do veredito, para o selo. O texto completo continua em Verdict.label. */
private fun Verdict.short(): String = when (this) {
    Verdict.COMPENSA -> "Vale a pena"
    Verdict.ATENDE_PARCIAL -> "Atende em parte"
    Verdict.ABAIXO_DA_META -> "Abaixo da meta"
    Verdict.PREJUIZO -> "Prejuízo"
    Verdict.BATERIA_INSUFICIENTE -> "Bateria não dá"
    Verdict.CONFIRA -> "Confira os dados"
    Verdict.INSUFICIENTE -> "Faltam dados"
    Verdict.CONFIG_INVALIDA -> "Revise os custos"
}

/** Painel exibido sobre outros apps. */
@Composable
fun OverlayPanel(
    ui: PanelUi,
    settings: CostSettings,
    platform: Platform,
    onDraftChange: (OfferDraft) -> Unit,
    onPlatformChange: (Platform) -> Unit,
    onReanalyze: () -> Unit,
    onClose: () -> Unit,
    onStopSession: () -> Unit,
) {
    AppTheme {
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
        Surface(
            modifier = Modifier.fillMaxWidth().padding(8.dp).heightIn(max = maxH),
            shape = RoundedCornerShape(24.dp),
            color = Brand.Bg,
            border = BorderStroke(1.dp, Brand.Outline),
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoMark(26.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("InteliEntrega", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (ui is PanelUi.Offer) Pill(platform.label, Brand.Muted)
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Fechar", tint = Brand.Muted) }
                }
                when (ui) {
                    PanelUi.Hidden -> Unit
                    PanelUi.Analyzing -> Row(Modifier.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = Brand.Yellow)
                        Spacer(Modifier.width(12.dp))
                        Text("Lendo a oferta na tela…", style = MaterialTheme.typography.bodyLarge)
                    }
                    is PanelUi.Message -> Column(Modifier.padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = Brand.Mid, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(ui.title, style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(ui.body, style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
                    }
                    is PanelUi.Offer -> OfferAnalysisCard(ui.draft, settings, onDraftChange, editorOpen = false) {
                        Text("Leitura feita como", style = MaterialTheme.typography.labelSmall, color = Brand.Muted)
                        PlatformChips(platform, onPlatformChange)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton("Ler de novo", onReanalyze, Modifier.weight(1f), icon = Icons.Filled.Refresh)
                    TextButton(onClick = onStopSession) { Text("Encerrar sessão", color = Brand.Muted) }
                }
            }
        }
    }
}

@Composable
fun PlatformChips(selected: Platform, onChange: (Platform) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Platform.entries.forEach { p ->
            FilterChip(
                selected = p == selected, onClick = { onChange(p) }, label = { Text(p.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Brand.Yellow, selectedLabelColor = Brand.OnYellow,
                    containerColor = Brand.SurfaceHigh, labelColor = Brand.Text,
                ),
                border = null,
            )
        }
    }
}

/**
 * Resultado (valor, veredito, semáforo) + edição dos dados. Reutilizado no painel flutuante e no
 * teste dentro do app. [extraEditorContent] aparece no topo da área de correção.
 */
@Composable
fun OfferAnalysisCard(
    draft: OfferDraft,
    settings: CostSettings,
    onChange: (OfferDraft) -> Unit,
    editorOpen: Boolean = false,
    extraEditorContent: (@Composable () -> Unit)? = null,
) {
    val result = remember(draft, settings) { OfferCalculator.calculate(draft.toInput(settings), settings) }
    var open by rememberSaveable { mutableStateOf(editorOpen) }

    OfferHero(draft, result, settings)

    if (draft.hasPendingReview) {
        Spacer(Modifier.height(10.dp))
        AppCard(padding = androidx.compose.foundation.layout.PaddingValues(12.dp), border = Brand.Mid.copy(alpha = 0.5f)) {
            Text("Confira antes de decidir", style = MaterialTheme.typography.titleSmall, color = Brand.Mid)
            draft.issues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Brand.Muted) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Está certo", { onChange(draft.confirm()) }, Modifier.weight(1f))
                SecondaryButton("Corrigir", { open = true }, Modifier.weight(1f).height(54.dp), icon = Icons.Filled.Edit)
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Edit, null, tint = Brand.Yellow, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (open) "Esconder detalhes" else "Corrigir dados e ver detalhes", color = Brand.Yellow,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = Brand.Yellow)
    }
    AnimatedVisibility(open) {
        Column {
            extraEditorContent?.let { it(); Spacer(Modifier.height(8.dp)) }
            OfferEditor(draft, settings, onChange)
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Brand.Outline)
            ResultDetails(result, settings)
        }
    }
}

@Composable
private fun OfferHero(draft: OfferDraft, r: CalcResult, s: CostSettings) {
    val signal = Semaphore.of(r.verdict)
    val c = signal.color()
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    draft.price.number?.let { brl(it) } ?: "R$ —",
                    style = MaterialTheme.typography.displaySmall,
                )
                val sub = listOfNotNull(r.distanceKm?.let { km(it) }, r.timeMin?.let { min(it) }).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, color = Brand.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Box(
                Modifier.background(c, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(r.verdict.short(), color = if (signal == Signal.NEUTRO) Brand.Text else androidx.compose.ui.graphics.Color.Black,
                    fontFamily = Sora, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        MetricStrip(Semaphore.metrics(r, draft.price.number, s))
        if (r.reasons.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            r.reasons.take(3).forEach {
                Row(Modifier.padding(vertical = 1.dp)) {
                    Box(Modifier.padding(top = 7.dp).size(6.dp).background(c, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                }
            }
        }
    }
}

@Composable
private fun OfferEditor(draft: OfferDraft, settings: CostSettings, onChange: (OfferDraft) -> Unit) {
    NumField("Valor da oferta", "R$", draft.price, 2) { onChange(draft.copy(price = it, confirmed = false)) }
    NumField("Distância total", "km", draft.totalKm) { onChange(draft.copy(totalKm = it, confirmed = false)) }
    Text(
        "Se a tela mostrar só os trechos, deixe a total em branco e preencha os dois.",
        style = MaterialTheme.typography.bodySmall, color = Brand.Muted,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) { NumField("Até a coleta", "km", draft.pickupKm) { onChange(draft.copy(pickupKm = it)) } }
        Column(Modifier.weight(1f)) { NumField("Até o cliente", "km", draft.deliveryKm) { onChange(draft.copy(deliveryKm = it)) } }
    }
    NumField("Volta / reposição (opcional)", "km", draft.repositionKm) { onChange(draft.copy(repositionKm = it)) }
    NumField("Tempo da oferta", "min", draft.timeMin) { onChange(draft.copy(timeMin = it, confirmed = false)) }
    if (settings.isElectric) {
        NumField("Bateria agora (opcional)", "%", draft.batteryPct) { onChange(draft.copy(batteryPct = it)) }
    }
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Esse tempo já inclui a espera", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = draft.waitIncluded, onCheckedChange = { onChange(draft.copy(waitIncluded = it)) },
            colors = SwitchDefaults.colors(checkedTrackColor = Brand.Yellow, checkedThumbColor = Brand.OnYellow),
        )
    }
}

@Composable
private fun NumField(label: String, suffix: String, f: DraftField, decimals: Int? = null, onChange: (DraftField) -> Unit) {
    OutlinedTextField(
        value = f.text,
        onValueChange = { onChange(f.edited(it)) },
        label = { Text(label) },
        suffix = { Text(suffix, color = Brand.Muted) },
        singleLine = true,
        isError = f.isInvalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        supportingText = {
            val src = f.source?.label ?: "não encontrado"
            Text(if (f.needsReview) "$src · confira" else src, color = if (f.needsReview) Brand.Mid else Brand.Muted)
        },
        shape = RoundedCornerShape(14.dp), colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    if (f.candidates.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Lidos na tela:", style = MaterialTheme.typography.bodySmall, color = Brand.Muted,
                modifier = Modifier.align(Alignment.CenterVertically))
            f.candidates.forEach { c ->
                FilterChip(
                    selected = f.number == c.value,
                    onClick = { onChange(f.chose(c, decimals)) },
                    label = { Text(c.raw) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Brand.Yellow, selectedLabelColor = Brand.OnYellow,
                        containerColor = Brand.SurfaceHigh,
                    ),
                    border = null,
                )
            }
        }
    }
}

@Composable
private fun ResultDetails(r: CalcResult, s: CostSettings) {
    @Composable fun line(label: String, value: String, note: String? = null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
            }
            Spacer(Modifier.width(8.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, fontFamily = Sora)
        }
    }
    val est = FieldSource.ESTIMADO.label

    Text("Como chegamos nesse resultado", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    line("Distância considerada", r.distanceKm?.let { km(it) + if (r.distanceIsPartial) " (parcial)" else "" } ?: "—",
        r.distanceParts.joinToString(" · ").ifEmpty { null })
    line("Tempo considerado", r.timeMin?.let { min(it) } ?: "sem tempo", r.timeParts.joinToString(" · ").ifEmpty { null })

    r.cost?.let { c ->
        if (s.isElectric) {
            line("Energia (recarga)", brl(c.energyCost),
                "${brl(c.energyPerKm)}/km = carga cheia ${brl(s.fullChargeCost)} ÷ ${km(s.rangeKm)} · $est")
            line("Reserva (manutenção, pneus, bateria)", brl(c.otherCost), "${brl(c.otherPerKm)}/km · $est")
            line("Custo do veículo", brl(c.totalCost), "${brl(c.motoPerKm)}/km")
        } else {
            line("Combustível", brl(c.energyCost), "${brl(c.energyPerKm)}/km = ${brl(s.fuelPricePerLiter)}/l ÷ ${s.kmPerLiter} km/l · $est")
            line("Reserva (manutenção, pneus, óleo)", brl(c.otherCost), "${brl(c.otherPerKm)}/km · $est")
            line("Custo da moto", brl(c.totalCost), "${brl(c.motoPerKm)}/km")
        }
    }
    line("Sobra estimada", r.surplus?.let { brl(it) } ?: "—",
        if (s.isElectric) "valor − energia − reserva" else "valor − combustível − reserva")
    line("Valor bruto por km", r.grossPerKm?.let { brl(it) + "/km" } ?: "—", s.targetGrossPerKm?.let { "meta ${brl(it)}/km" })
    line("Sobra por hora (projeção)", r.surplusPerHour?.let { brl(it) + "/h" } ?: "precisa do tempo",
        if (s.targetSurplusPerHour > 0) "meta ${brl(s.targetSurplusPerHour)}/h" else null)

    r.battery?.let { b ->
        Spacer(Modifier.height(6.dp))
        Text("Bateria", style = MaterialTheme.typography.titleSmall)
        line("Gasto nesta entrega", "≈ ${pct(b.usedPct)}", "da carga cheia (autonomia ${km(s.rangeKm)})")
        line("Sobra por 1% de bateria", b.surplusPerPct?.let { brl(it) } ?: "—", "quanto maior, melhor uso da carga")
        if (b.nowPct != null && b.afterPct != null) {
            line("Carga ao fim da entrega", "≈ ${pct(b.afterPct.coerceAtLeast(0.0))}",
                "agora ${pct(b.nowPct)} · reserva mínima ${pct(s.minBatteryReservePct)}")
        } else {
            Text("Informe a bateria agora para saber se a carga dá conta.", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        "A sobra desconta só ${if (s.isElectric) "a energia" else "o combustível"} e a sua reserva por km. Não é lucro líquido: não inclui " +
            "impostos, taxas, celular, alimentação, multas nem tempo parado entre pedidos. A sobra por hora é uma " +
            "projeção desta oferta e não garante pedidos seguidos.",
        style = MaterialTheme.typography.bodySmall,
        color = Brand.Muted,
    )
}
