package br.com.intelientrega.account

import android.content.Context
import br.com.intelientrega.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class Account(val userId: String, val email: String)

data class PaymentItem(val status: String, val amountCents: Int?, val date: String?)

data class SubscriptionStatus(
    val state: String,
    val title: String,
    val help: String,
    val active: Boolean,
    val paidUntil: String?,
    val subStatus: String?,
    val baseCents: Int?,
    val discountCents: Int?,
    val amountCents: Int?,
    val couponCode: String?,
    val nextPaymentDate: String?,
    val canCancel: Boolean,
    val payments: List<PaymentItem>,
    val sandbox: Boolean,
)

data class CouponPreview(
    val valid: Boolean,
    val code: String?,
    val percent: Int?,
    val baseCents: Int,
    val discountCents: Int,
    val totalCents: Int,
    val message: String?,
)

sealed interface SignUpResult {
    data object LoggedIn : SignUpResult
    data object ConfirmEmail : SignUpResult
}

/**
 * Conta, sessão e assinatura. O acesso pago só é considerado ativo com um comprovante
 * assinado pelo servidor, válido, do usuário logado e dentro da validade.
 */
class AccountRepository private constructor(context: Context) {
    companion object {
        @Volatile private var instance: AccountRepository? = null
        fun get(context: Context): AccountRepository =
            instance ?: synchronized(this) { instance ?: AccountRepository(context.applicationContext).also { instance = it } }

        private const val K_ACCESS = "access_token"
        private const val K_REFRESH = "refresh_token"
        private const val K_EXPIRES = "expires_at"
        private const val K_USER = "user_id"
        private const val K_EMAIL = "email"
        private const val K_ENTITLEMENT = "entitlement"
    }

    private val store = SecureStore(context)
    private val verifier = EntitlementVerifier(BuildConfig.ENTITLEMENT_PUBLIC_KEY)
    private val api = SupabaseApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
    private val tokenLock = Mutex()

    /** Faltam SUPABASE_URL, SUPABASE_ANON_KEY ou ENTITLEMENT_PUBLIC_KEY no build. */
    val isConfigured: Boolean =
        BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_ANON_KEY.isNotBlank() && verifier.isConfigured

    private val _account = MutableStateFlow(loadAccount())
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _status = MutableStateFlow<SubscriptionStatus?>(null)
    val status: StateFlow<SubscriptionStatus?> = _status.asStateFlow()

    private val _access = MutableStateFlow(computeAccess())
    val access: StateFlow<Boolean> = _access.asStateFlow()

    private fun loadAccount(): Account? {
        val id = store.get(K_USER) ?: return null
        if (store.get(K_REFRESH) == null) return null
        return Account(id, store.get(K_EMAIL).orEmpty())
    }

    private fun computeAccess(): Boolean =
        verifier.verify(store.get(K_ENTITLEMENT), _account.value?.userId, System.currentTimeMillis() / 1000) != null

    /** Checagem pontual (o comprovante pode vencer enquanto o app está aberto). */
    fun hasAccess(): Boolean = computeAccess().also { _access.value = it }

    // ------------------------------------------------------------ Sessão

    private fun saveSession(j: JSONObject) {
        val user = j.getJSONObject("user")
        store.put(K_ACCESS, j.getString("access_token"))
        store.put(K_REFRESH, j.getString("refresh_token"))
        store.put(K_EXPIRES, (System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600)).toString())
        store.put(K_USER, user.getString("id"))
        store.put(K_EMAIL, user.optString("email"))
        if (_account.value?.userId != user.getString("id")) store.put(K_ENTITLEMENT, null)
        _account.value = Account(user.getString("id"), user.optString("email"))
        _access.value = computeAccess()
    }

    private suspend fun accessToken(): String = tokenLock.withLock {
        val access = store.get(K_ACCESS)
        val exp = store.get(K_EXPIRES)?.toLongOrNull() ?: 0
        if (access != null && exp - 60 > System.currentTimeMillis() / 1000) return access
        val refresh = store.get(K_REFRESH) ?: throw ApiException(401, "no_session", "Entre na sua conta.")
        try {
            api.refresh(refresh).also(::saveSession).getString("access_token")
        } catch (e: ApiException) {
            if (e.status in 400..499) clearLocal()
            throw if (e.status in 400..499) ApiException(401, "session_expired", "Sessão expirada. Entre de novo.") else e
        }
    }

    private fun clearLocal() {
        store.clear()
        _account.value = null
        _status.value = null
        _access.value = false
    }

    suspend fun signUp(email: String, password: String): SignUpResult {
        val j = api.signUp(email.trim(), password)
        // Com confirmação de e-mail ativa no Supabase, não vem sessão: o usuário confirma e depois entra.
        return if (j.has("access_token")) { saveSession(j); refreshStatusQuietly(); SignUpResult.LoggedIn }
        else SignUpResult.ConfirmEmail
    }

    suspend fun signIn(email: String, password: String) {
        saveSession(api.signIn(email.trim(), password))
        refreshStatusQuietly()
    }

    suspend fun sendRecovery(email: String) { api.sendRecovery(email.trim()) }

    /** Código de 6 dígitos do e-mail de recuperação + nova senha. Entra na conta ao final. */
    suspend fun resetPassword(email: String, code: String, newPassword: String) {
        val j = api.verifyRecovery(email.trim(), code.trim())
        saveSession(j)
        api.updatePassword(j.getString("access_token"), newPassword)
        refreshStatusQuietly()
    }

    suspend fun signOut() {
        runCatching { api.signOut(accessToken()) }
        clearLocal()
    }

    // ------------------------------------------------------------ Assinatura

    private suspend fun refreshStatusQuietly() { runCatching { refreshStatus(false) } }

    suspend fun refreshStatus(force: Boolean): SubscriptionStatus {
        val j = api.function("subscription-status", accessToken(), JSONObject().put("refresh", force))
        return applyStatus(j)
    }

    private fun applyStatus(j: JSONObject): SubscriptionStatus {
        // O comprovante só é guardado; quem decide se vale é o verificador (assinatura + validade + usuário).
        store.put(K_ENTITLEMENT, j.optString("entitlement").ifBlank { null })
        val sub = j.optJSONObject("subscription")
        val access = j.optJSONObject("access")
        val pays = j.optJSONArray("payments")
        val s = SubscriptionStatus(
            state = j.optString("state", "none"),
            title = j.optString("title"),
            help = j.optString("help"),
            active = access?.optBoolean("active") == true,
            paidUntil = access?.optStringOrNull("paid_until"),
            subStatus = sub?.optStringOrNull("status"),
            baseCents = sub?.optIntOrNull("base_amount_cents"),
            discountCents = sub?.optIntOrNull("discount_cents"),
            amountCents = sub?.optIntOrNull("amount_cents"),
            couponCode = sub?.optStringOrNull("coupon_code"),
            nextPaymentDate = sub?.optStringOrNull("next_payment_date"),
            canCancel = sub?.optBoolean("can_cancel") == true,
            payments = (0 until (pays?.length() ?: 0)).map { i ->
                val p = pays!!.getJSONObject(i)
                PaymentItem(p.optString("status"), p.optIntOrNull("amount_cents"),
                    p.optStringOrNull("approved_at") ?: p.optStringOrNull("debit_date"))
            },
            sandbox = j.optBoolean("sandbox"),
        )
        _status.value = s
        _access.value = computeAccess()
        return s
    }

    suspend fun previewCoupon(code: String?): CouponPreview {
        val j = api.function("coupon-preview", accessToken(), JSONObject().put("code", code ?: ""))
        return CouponPreview(
            valid = j.optBoolean("valid"),
            code = j.optStringOrNull("code"),
            percent = j.optIntOrNull("discount_percent"),
            baseCents = j.getInt("baseCents"),
            discountCents = j.getInt("discountCents"),
            totalCents = j.getInt("totalCents"),
            message = j.optStringOrNull("message"),
        )
    }

    /** Devolve o link do checkout do Mercado Pago. O valor é decidido pelo servidor. */
    suspend fun startCheckout(validatedCoupon: String?): String {
        val body = JSONObject().apply { if (validatedCoupon != null) put("coupon_code", validatedCoupon) }
        return api.function("subscription-create", accessToken(), body).getString("init_point")
    }

    suspend fun cancelRenewal(): SubscriptionStatus =
        applyStatus(api.function("subscription-cancel", accessToken()))
}

private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).ifBlank { null }
private fun JSONObject.optIntOrNull(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
