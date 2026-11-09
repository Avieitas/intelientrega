package br.com.intelientrega.account

import org.json.JSONObject
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifica o comprovante de acesso emitido pelo servidor (função subscription-status).
 * Formato: base64url(JSON) + "." + base64url(assinatura ECDSA P-256 r||s).
 * O app só tem a chave PÚBLICA: não consegue gerar um comprovante, só conferir.
 */
class EntitlementVerifier(publicKeyB64: String) {
    data class Claims(val userId: String, val expSec: Long, val paidUntil: String)

    private val key: PublicKey? = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64.trim())))
    }.getOrNull()

    val isConfigured: Boolean get() = key != null

    fun verify(token: String?, expectedUserId: String?, nowSec: Long): Claims? {
        val k = key ?: return null
        if (token.isNullOrBlank() || expectedUserId.isNullOrBlank()) return null
        val parts = token.split('.')
        if (parts.size != 2) return null
        return runCatching {
            val raw = Base64.getUrlDecoder().decode(parts[1])
            if (raw.size != 64) return null
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(k)
            sig.update(parts[0].toByteArray(Charsets.US_ASCII))
            if (!sig.verify(rawToDer(raw))) return null

            val json = JSONObject(String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8))
            if (json.optInt("v") != 1) return null
            val sub = json.getString("sub")
            val exp = json.getLong("exp")
            val iat = json.getLong("iat")
            // Outro usuário, vencido, ou relógio do aparelho atrasado em relação à emissão: inválido.
            if (sub != expectedUserId || exp <= nowSec || nowSec < iat - 300) return null
            Claims(sub, exp, json.getString("paid_until"))
        }.getOrNull()
    }

    companion object {
        /** Web Crypto devolve r||s (64 bytes); java.security espera DER. */
        fun rawToDer(raw: ByteArray): ByteArray {
            fun int(b: ByteArray): ByteArray {
                var i = 0
                while (i < b.size - 1 && b[i] == 0.toByte()) i++
                val trimmed = b.copyOfRange(i, b.size)
                return if (trimmed[0].toInt() and 0x80 != 0) byteArrayOf(0) + trimmed else trimmed
            }
            val r = int(raw.copyOfRange(0, 32))
            val s = int(raw.copyOfRange(32, 64))
            val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
            return byteArrayOf(0x30, body.size.toByte()) + body
        }
    }
}
