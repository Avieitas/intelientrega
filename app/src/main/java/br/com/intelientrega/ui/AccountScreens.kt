package br.com.intelientrega.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import br.com.intelientrega.account.AccountRepository
import br.com.intelientrega.account.ApiException
import br.com.intelientrega.account.CouponPreview
import br.com.intelientrega.account.SignUpResult
import br.com.intelientrega.account.SubscriptionStatus
import br.com.intelientrega.account.normalizeCouponInput
import br.com.intelientrega.domain.brl
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun brlCents(cents: Int): String = brl(cents / 100.0)

fun brDate(iso: String?): String = iso?.let {
    runCatching {
        OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    }.getOrNull()
} ?: "—"

private val PAYMENT_LABELS = mapOf(
    "approved" to "Aprovado", "pending" to "Pendente", "in_process" to "Em análise", "scheduled" to "Agendado",
    "rejected" to "Recusado", "cancelled" to "Cancelado", "refunded" to "Estornado", "charged_back" to "Contestado",
)

/** Executa uma ação com estado de carregamento e mensagem de erro amigável. */
private class Busy {
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
}

@Composable
private fun rememberBusy() = remember { Busy() }

@Composable
private fun Feedback(b: Busy) {
    b.error?.let { Text(it, color = Brand.Bad, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
    b.info?.let { Text(it, color = Brand.Good, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
}

private fun msg(e: Throwable) = (e as? ApiException)?.message ?: "Algo deu errado. Tente de novo."

@Composable
private fun TextInput(
    label: String, value: String, onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = RoundedCornerShape(14.dp), colors = fieldColors(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

// ===================================================================== Aba Conta

@Composable
fun AccountTab(repo: AccountRepository) {
    val account by repo.account.collectAsState()
    if (!repo.isConfigured) {
        AppCard {
            Text("Servidor não configurado", style = MaterialTheme.typography.titleSmall)
            Text(
                "Este build não tem SUPABASE_URL, SUPABASE_ANON_KEY e ENTITLEMENT_PUBLIC_KEY. Veja o README (seção Assinatura).",
                style = MaterialTheme.typography.bodySmall, color = Brand.Muted,
            )
        }
        return
    }
    if (account == null) AuthForms(repo) else SubscriptionScreen(repo)
}

// ===================================================================== Cadastro / login / recuperação

private enum class AuthMode(val label: String) { ENTRAR("Entrar"), CRIAR("Criar conta"), RECUPERAR("Recuperar") }

@Composable
private fun AuthForms(repo: AccountRepository) {
    var mode by rememberSaveable { mutableStateOf(AuthMode.ENTRAR) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    val b = rememberBusy()
    val scope = rememberCoroutineScope()

    fun run(block: suspend () -> Unit) {
        b.loading = true; b.error = null; b.info = null
        scope.launch {
            try { block() } catch (e: Exception) { b.error = msg(e) } finally { b.loading = false }
        }
    }

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoMark(48.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    when (mode) {
                        AuthMode.ENTRAR -> "Bem-vindo de volta"
                        AuthMode.CRIAR -> "Crie sua conta"
                        AuthMode.RECUPERAR -> "Recuperar senha"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text("A assinatura fica ligada à sua conta.", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        Segmented(AuthMode.entries.map { it to it.label }, mode) { mode = it; b.error = null; b.info = null }
        Spacer(Modifier.height(12.dp))

        TextInput("E-mail", email, { email = it }, KeyboardType.Email)

        when (mode) {
            AuthMode.ENTRAR -> {
                TextInput("Senha", password, { password = it }, KeyboardType.Password, password = true)
                Spacer(Modifier.height(10.dp))
                PrimaryButton(if (b.loading) "Entrando…" else "Entrar", enabled = !b.loading, onClick = {
                    if (email.isBlank() || password.isBlank()) { b.error = "Preencha e-mail e senha."; return@PrimaryButton }
                    run { repo.signIn(email, password) }
                })
            }
            AuthMode.CRIAR -> {
                TextInput("Senha (mínimo 8 caracteres)", password, { password = it }, KeyboardType.Password, password = true)
                TextInput("Confirme a senha", confirm, { confirm = it }, KeyboardType.Password, password = true)
                Spacer(Modifier.height(10.dp))
                PrimaryButton(if (b.loading) "Criando…" else "Criar conta", enabled = !b.loading, onClick = {
                    when {
                        !email.contains('@') -> b.error = "Digite um e-mail válido."
                        password.length < 8 -> b.error = "A senha precisa ter pelo menos 8 caracteres."
                        password != confirm -> b.error = "As senhas não são iguais."
                        else -> run {
                            when (repo.signUp(email, password)) {
                                SignUpResult.LoggedIn -> Unit
                                SignUpResult.ConfirmEmail -> {
                                    b.info = "Conta criada! Abra o e-mail de confirmação (veja o spam também) e depois entre aqui."
                                    mode = AuthMode.ENTRAR
                                }
                            }
                        }
                    }
                })
            }
            AuthMode.RECUPERAR -> {
                if (!codeSent) {
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("Enviar código", enabled = !b.loading, onClick = {
                        if (!email.contains('@')) { b.error = "Digite o e-mail da conta."; return@PrimaryButton }
                        run {
                            repo.sendRecovery(email)
                            codeSent = true
                            b.info = "Se existir uma conta com esse e-mail, enviamos um código de 6 dígitos."
                        }
                    })
                } else {
                    TextInput("Código do e-mail", code, { code = it.filter(Char::isDigit).take(10) }, KeyboardType.Number)
                    TextInput("Nova senha (mínimo 8 caracteres)", password, { password = it }, KeyboardType.Password, password = true)
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("Redefinir senha e entrar", enabled = !b.loading, onClick = {
                        when {
                            code.length < 6 -> b.error = "Digite o código recebido."
                            password.length < 8 -> b.error = "A senha precisa ter pelo menos 8 caracteres."
                            else -> run { repo.resetPassword(email, code, password) }
                        }
                    })
                    TextButton(onClick = { codeSent = false; code = "" }) { Text("Reenviar código", color = Brand.Yellow) }
                }
            }
        }
        Feedback(b)
    }
}

// ===================================================================== Assinatura e status

@Composable
private fun SubscriptionScreen(repo: AccountRepository) {
    val ctx = LocalContext.current
    val account by repo.account.collectAsState()
    val status by repo.status.collectAsState()
    val b = rememberBusy()
    val scope = rememberCoroutineScope()
    var confirmCancel by remember { mutableStateOf(false) }

    fun run(block: suspend () -> Unit) {
        b.loading = true; b.error = null; b.info = null
        scope.launch { try { block() } catch (e: Exception) { b.error = msg(e) } finally { b.loading = false } }
    }

    LaunchedEffect(account?.userId) { if (status == null) run { repo.refreshStatus(false) } }

    AppCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = Brand.Muted, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Conectado como", style = MaterialTheme.typography.labelSmall, color = Brand.Muted)
                Text(account?.email.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            TextButton(onClick = { run { repo.signOut() } }) { Text("Sair", color = Brand.Muted) }
        }
    }
    Spacer(Modifier.height(12.dp))

    val s = status
    if (s == null) {
        AppCard {
            Text(if (b.loading) "Consultando assinatura…" else "Não foi possível consultar a assinatura.")
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Tentar de novo", { run { repo.refreshStatus(true) } }, icon = Icons.Filled.Refresh)
            Feedback(b)
        }
        return
    }

    if (s.sandbox) {
        Pill("MODO DE TESTE · nenhuma cobrança real", Brand.Mid, Modifier.padding(bottom = 10.dp))
    }

    StatusCard(s)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Atualizar", { run { repo.refreshStatus(true) } }, Modifier.weight(1f), enabled = !b.loading, icon = Icons.Filled.Refresh)
        if (s.canCancel && s.subStatus != "cancelled") {
            SecondaryButton("Cancelar renovação", { confirmCancel = true }, Modifier.weight(1f), enabled = !b.loading)
        }
    }
    Feedback(b)

    // Pode assinar: sem acesso, ou com renovação já cancelada (a nova cobrança começa no fim do período pago).
    val canSubscribe = s.state in setOf("none", "cancelled", "expired", "payment_failed", "pending", "active_cancelled", "courtesy")
    if (canSubscribe) {
        Spacer(Modifier.height(16.dp))
        CheckoutCard(repo, s) { url -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            containerColor = Brand.Surface,
            title = { Text("Cancelar a renovação?") },
            text = {
                Text(
                    if (s.active) "Não haverá novas cobranças. Você mantém o acesso até ${brDate(s.paidUntil)}."
                    else "A assinatura pendente será cancelada no Mercado Pago."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    run { repo.cancelRenewal(); b.info = "Renovação cancelada." }
                }) { Text("Cancelar renovação", color = Brand.Bad) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Voltar", color = Brand.Text) } },
        )
    }
}

@Composable
private fun StatusCard(s: SubscriptionStatus) {
    val color = when (s.state) {
        "active", "courtesy" -> Brand.Good
        "active_cancelled", "pending" -> Brand.Mid
        else -> Brand.Bad
    }
    AppCard(border = color.copy(alpha = 0.5f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(s.title, style = MaterialTheme.typography.titleMedium, color = color)
        }
        Spacer(Modifier.height(4.dp))
        Text(s.help, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
        if (s.active) {
            Spacer(Modifier.height(12.dp))
            Text("ACESSO ATÉ", style = MaterialTheme.typography.labelSmall, color = Brand.Muted)
            Text(brDate(s.paidUntil), style = MaterialTheme.typography.headlineSmall)
        }
        s.amountCents?.let { amt ->
            Spacer(Modifier.height(8.dp))
            val coupon = s.couponCode?.let { " · cupom $it" }.orEmpty()
            Text("Valor mensal: ${brlCents(amt)}$coupon", style = MaterialTheme.typography.bodyMedium)
        }
        if (s.subStatus == "authorized" && s.nextPaymentDate != null)
            Text("Próxima cobrança: ${brDate(s.nextPaymentDate)}", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)

        if (s.payments.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Brand.Outline)
            Text("Cobranças recentes", style = MaterialTheme.typography.titleSmall)
            s.payments.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(brDate(p.date), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                    Text(p.amountCents?.let(::brlCents) ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Pill(PAYMENT_LABELS[p.status] ?: p.status, if (p.status == "approved") Brand.Good else Brand.Muted)
                }
            }
        }
    }
}

@Composable
private fun CheckoutCard(repo: AccountRepository, s: SubscriptionStatus, openUrl: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<CouponPreview?>(null) }
    val b = rememberBusy()
    val scope = rememberCoroutineScope()

    // Preço base vem do servidor, nunca fixado no app.
    LaunchedEffect(Unit) { runCatching { preview = repo.previewCoupon(null) } }

    AppCard(border = Brand.Yellow.copy(alpha = 0.5f)) {
        Pill("PLANO MENSAL", Brand.Yellow)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(preview?.let { brlCents(it.totalCents) } ?: "R$ —", style = MaterialTheme.typography.displaySmall)
            Text("/mês", color = Brand.Muted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        if (preview?.valid == true && (preview?.discountCents ?: 0) > 0) {
            Text(
                "de ${brlCents(preview!!.baseCents)} com o cupom ${preview!!.code} (−${preview!!.percent}%)",
                color = Brand.Good, style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))
        listOf(
            "Análise de ofertas do 99 e do iFood na hora",
            "Semáforo com as suas metas de R$/km e R$/hora",
            "Modo moto a combustível e elétrica",
            "Cancele quando quiser, direto pelo app",
        ).forEach {
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, null, tint = Brand.Good, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    // Mudou o código: o total exibido deixa de valer até validar de novo.
                    if (preview?.valid == true && normalizeCouponInput(it) != preview?.code) {
                        scope.launch { runCatching { preview = repo.previewCoupon(null) } }
                    }
                },
                label = { Text("Cupom de criador") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), colors = fieldColors(), modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            SecondaryButton("Aplicar", enabled = !b.loading && input.isNotBlank(), modifier = Modifier.padding(top = 6.dp), onClick = {
                b.loading = true; b.error = null
                scope.launch {
                    try {
                        val p = repo.previewCoupon(input)
                        preview = p
                        if (!p.valid) b.error = p.message ?: "Código inválido."
                    } catch (e: Exception) { b.error = msg(e) } finally { b.loading = false }
                }
            })
        }
        preview?.let { p ->
            if (p.valid && p.discountCents > 0) {
                Spacer(Modifier.height(8.dp))
                PriceLine("Assinatura", brlCents(p.baseCents))
                PriceLine("Desconto ${p.code} (${p.percent}%)", "− ${brlCents(p.discountCents)}")
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Brand.Outline)
                PriceLine("Total por mês", brlCents(p.totalCents), bold = true)
                Text("O desconto vale em todas as renovações enquanto a assinatura estiver ativa.",
                    style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
            }
        }
        Feedback(b)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            preview?.let { "Assinar por ${brlCents(it.totalCents)}/mês" } ?: "Carregando preço…",
            enabled = !b.loading && preview != null,
            onClick = {
                b.loading = true; b.error = null
                scope.launch {
                    try {
                        val coupon = preview?.takeIf { it.valid }?.code
                        openUrl(repo.startCheckout(coupon))
                        b.info = "Conclua o pagamento no Mercado Pago e volte ao app. O acesso é liberado quando o Mercado Pago confirmar."
                    } catch (e: Exception) { b.error = msg(e) } finally { b.loading = false }
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = Brand.Muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Pagamento no Mercado Pago. O app não vê nem guarda os dados do seu cartão." +
                    if (s.state == "active_cancelled") " A nova cobrança começa quando o período atual terminar." else "",
                style = MaterialTheme.typography.bodySmall, color = Brand.Muted,
            )
        }
    }
}

@Composable
private fun PriceLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else null)
        Text(value, fontWeight = if (bold) FontWeight.Bold else null, fontFamily = Sora)
    }
}

// ===================================================================== Acesso bloqueado

@Composable
fun AccessBlocked(repo: AccountRepository, onGoToAccount: () -> Unit) {
    val account by repo.account.collectAsState()
    val status by repo.status.collectAsState()
    AppCard(border = Brand.Yellow.copy(alpha = 0.4f)) {
        Box(
            Modifier.size(44.dp).background(Brand.Yellow.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Lock, null, tint = Brand.Yellow) }
        Spacer(Modifier.height(10.dp))
        Text("Libere a análise de ofertas", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        val text = when {
            account == null -> "Entre na sua conta e assine para saber, na hora, se cada corrida vale a pena."
            status == null -> "Não conseguimos confirmar sua assinatura. Conecte-se à internet e abra a aba Conta."
            else -> "${status!!.title}. ${status!!.help}"
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Brand.Muted)
        Spacer(Modifier.height(4.dp))
        Text("Custos e metas continuam disponíveis sem assinatura.", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
        Spacer(Modifier.height(14.dp))
        PrimaryButton(if (account == null) "Entrar ou criar conta" else "Ver planos", onGoToAccount)
    }
}
