package br.com.intelientrega.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, val code: String?, message: String) : Exception(message)

/**
 * Chamadas HTTP ao Supabase: Auth (GoTrue) e Edge Functions.
 * Sem interceptor de log: tokens e senhas nunca vão para o logcat.
 */
class SupabaseApi(baseUrl: String, private val anonKey: String) {
    private val base = baseUrl.trimEnd('/')
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private suspend fun send(method: String, path: String, body: JSONObject?, bearer: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val rb = (body ?: JSONObject()).toString().toRequestBody(jsonType)
            val req = Request.Builder().url(base + path)
                .header("apikey", anonKey)
                .header("Authorization", "Bearer ${bearer ?: anonKey}")
                .method(method, if (method == "GET") null else rb)
                .build()
            try {
                http.newCall(req).execute().use { res ->
                    val text = res.body?.string().orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                    if (!res.isSuccessful) throw toError(res.code, json)
                    json
                }
            } catch (e: IOException) {
                throw ApiException(0, "network", "Sem conexão com o servidor. Verifique sua internet.")
            }
        }

    private fun toError(status: Int, j: JSONObject): ApiException {
        val code = j.optString("error_code").ifBlank { j.optString("error") }.ifBlank { null }
        val raw = j.optString("msg").ifBlank { j.optString("error_description") }.ifBlank { j.optString("message") }
        // Edge Functions já mandam mensagem em português no campo "message".
        if (j.has("message") && j.has("error")) return ApiException(status, code, j.getString("message"))
        val msg = when {
            status == 429 -> "Muitas tentativas. Espere um pouco e tente de novo."
            code == "invalid_credentials" || raw.contains("Invalid login", true) -> "E-mail ou senha incorretos."
            code == "email_not_confirmed" || raw.contains("not confirmed", true) -> "Confirme seu e-mail pelo link que enviamos antes de entrar."
            code == "user_already_exists" || raw.contains("already registered", true) -> "Já existe uma conta com este e-mail. Entre ou recupere a senha."
            code == "weak_password" || raw.contains("password", true) && raw.contains("characters", true) -> "Senha fraca. Use pelo menos 8 caracteres."
            code == "otp_expired" || raw.contains("expired", true) -> "Código inválido ou expirado. Peça um novo."
            code == "validation_failed" || raw.contains("email", true) && raw.contains("invalid", true) -> "E-mail inválido."
            status == 401 -> "Sessão expirada. Entre de novo."
            else -> "Não foi possível concluir agora (erro $status). Tente de novo."
        }
        return ApiException(status, code, msg)
    }

    // ---------------- Auth
    suspend fun signUp(email: String, password: String) =
        send("POST", "/auth/v1/signup", JSONObject().put("email", email).put("password", password), null)
    suspend fun signIn(email: String, password: String) =
        send("POST", "/auth/v1/token?grant_type=password", JSONObject().put("email", email).put("password", password), null)
    suspend fun refresh(refreshToken: String) =
        send("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refreshToken), null)
    suspend fun sendRecovery(email: String) =
        send("POST", "/auth/v1/recover", JSONObject().put("email", email), null)
    suspend fun verifyRecovery(email: String, code: String) =
        send("POST", "/auth/v1/verify", JSONObject().put("type", "recovery").put("email", email).put("token", code), null)
    suspend fun updatePassword(accessToken: String, password: String) =
        send("PUT", "/auth/v1/user", JSONObject().put("password", password), accessToken)
    suspend fun signOut(accessToken: String) = send("POST", "/auth/v1/logout", null, accessToken)

    // ---------------- Edge Functions
    suspend fun function(name: String, accessToken: String, body: JSONObject = JSONObject()) =
        send("POST", "/functions/v1/$name", body, accessToken)
}
