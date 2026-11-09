package br.com.intelientrega.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import br.com.intelientrega.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AvailableUpdate(
    val versionName: String,
    val notes: String?,
    val pageUrl: String,
    /** Link direto do arquivo da versão nova (mesmo endereço https do version.json). */
    val apkUrl: String?,
)

/**
 * O app é distribuído fora da Play Store: consulta um version.json público na página de download.
 *
 * A atualização é baixada pelo navegador, de propósito. Instalar por dentro do app exige a
 * permissão REQUEST_INSTALL_PACKAGES, e com ela o Play Protect passa a classificar o app como
 * "potencialmente nocivo" (sobreposição + captura de tela + instalar apps é o padrão de golpes
 * bancários). O navegador já foi autorizado pela pessoa na primeira instalação, e o Android só
 * aceita a versão nova se ela tiver a mesma assinatura do app instalado.
 * Só lê um arquivo estático; não envia nenhum dado do usuário.
 */
object UpdateChecker {
    val available = MutableStateFlow<AvailableUpdate?>(null)
    @Volatile private var checked = false

    suspend fun check() {
        val url = BuildConfig.UPDATE_URL
        if (checked || !url.startsWith("https://")) return
        checked = true
        runCatching {
            val body = withContext(Dispatchers.IO) {
                OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            } ?: return
            val j = JSONObject(body)
            val code = j.optInt("versionCode", 0)
            val page = j.optString("pageUrl")
            if (code > BuildConfig.VERSION_CODE && page.startsWith("https://")) {
                val apk = j.optString("apkUrl").takeIf { sameHttpsHost(it, url) }
                available.value = AvailableUpdate(j.optString("versionName"), j.optString("notes").ifBlank { null }, page, apk)
            }
        }
    }

    private fun sameHttpsHost(candidate: String, reference: String): Boolean {
        val a = runCatching { Uri.parse(candidate) }.getOrNull() ?: return false
        return a.scheme == "https" && !a.host.isNullOrBlank() && a.host == Uri.parse(reference).host
    }
}

@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current
    val update by UpdateChecker.available.collectAsState()
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { UpdateChecker.check() }
    val u = update ?: return
    val open: (String) -> Unit = { url -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

    AppCard(border = Brand.Yellow.copy(alpha = 0.6f), modifier = Modifier.padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("Novo", Brand.Yellow, solid = true)
            Spacer(Modifier.width(8.dp))
            Text("Versão ${u.versionName} disponível", style = MaterialTheme.typography.titleSmall)
        }
        u.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Brand.Muted, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.height(10.dp))
        if (u.apkUrl != null) {
            Text(
                if (started) "Quando o download terminar, toque em Abrir e depois em Atualizar. Seus custos, metas e login continuam."
                else "O download começa direto, sem abrir o site. Depois é só tocar em Abrir e em Atualizar.",
                style = MaterialTheme.typography.bodySmall, color = Brand.Muted,
            )
            Spacer(Modifier.height(10.dp))
            PrimaryButton(if (started) "Baixar de novo" else "Atualizar agora", { started = true; open(u.apkUrl) })
        } else {
            PrimaryButton("Baixar atualização", { open(u.pageUrl) })
        }
    }
}
