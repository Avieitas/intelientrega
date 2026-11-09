package br.com.intelientrega.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.intelientrega.domain.Metric

/** Marca: quadrado amarelo com "R$?" (o mesmo símbolo da bolinha flutuante). */
@Composable
fun LogoMark(size: Dp = 40.dp) {
    Box(
        Modifier.size(size).background(Brand.Yellow, RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "R$?", color = Brand.OnYellow, fontFamily = Sora, fontWeight = FontWeight.ExtraBold,
            fontSize = (size.value * 0.34f).sp, maxLines = 1,
        )
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    border: Color = Brand.Outline,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Brand.Surface,
        border = BorderStroke(1.dp, border),
    ) { Column(Modifier.padding(padding), content = content) }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 20.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, solid: Boolean = false) {
    Text(
        text,
        modifier = modifier
            .background(if (solid) color else color.copy(alpha = 0.16f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = if (solid) Color.Black else color,
        style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
        maxLines = 1,
    )
}

/** Bloco de indicador com barra de semáforo à esquerda. */
@Composable
fun RowScope.MetricTile(m: Metric) {
    val c = m.signal.color()
    Row(
        Modifier.weight(1f).heightIn(min = 64.dp)
            .background(Brand.SurfaceHigh, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp)),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().heightIn(min = 64.dp).background(c))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(m.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Brand.Muted, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    m.value.removePrefix("R$ "), fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = if (m.signal == br.com.intelientrega.domain.Signal.NEUTRO) Brand.Text else c,
                    maxLines = 1, overflow = TextOverflow.Clip,
                )
                if (m.unit.isNotEmpty()) Text(m.unit, fontSize = 11.sp, color = Brand.Muted, modifier = Modifier.padding(start = 1.dp, bottom = 2.dp))
            }
        }
    }
}

@Composable
fun MetricStrip(metrics: List<Metric>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        metrics.forEach { MetricTile(it) }
    }
}

@Composable
fun PrimaryButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    icon: ImageVector? = null, color: Color = Brand.Yellow, contentColor: Color = Brand.OnYellow,
) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = contentColor),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Brand.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Brand.Text),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Brand.Yellow, unfocusedBorderColor = Brand.Outline,
    focusedLabelColor = Brand.Yellow, cursorColor = Brand.Yellow,
    focusedContainerColor = Brand.SurfaceHigh, unfocusedContainerColor = Brand.SurfaceHigh,
)

/** Campo numérico com unidade à direita ("R$/l", "km/l"…). */
@Composable
fun NumberField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    suffix: String? = null, help: String? = null, isError: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, isError = isError,
        suffix = suffix?.let { { Text(it, color = Brand.Muted) } },
        supportingText = help?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(14.dp), colors = fieldColors(),
        modifier = modifier.fillMaxWidth().padding(vertical = 3.dp),
    )
}

/** Linha de checklist: bolinha (feito/pendente), título, explicação e ação opcional. */
@Composable
fun CheckStep(done: Boolean, number: Int, title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp)
                .background(if (done) Brand.Good else Color.Transparent, CircleShape)
                .border(1.5.dp, if (done) Brand.Good else Brand.Outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Filled.Check, null, tint = Color.Black, modifier = Modifier.size(18.dp))
            else Text("$number", color = Brand.Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
        }
        action?.invoke()
    }
}

/** Seletor segmentado grande (ex.: tipo de veículo). */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Brand.SurfaceHigh, RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (on) Brand.Yellow else Color.Transparent)
                    .clickable { onSelect(value) }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (on) Brand.OnYellow else Brand.Muted, fontWeight = FontWeight.Bold,
                    fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1,
                )
            }
        }
    }
}
