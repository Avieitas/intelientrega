package br.com.intelientrega

import br.com.intelientrega.account.EntitlementVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Usa um par de chaves e um token gerados pelo código do servidor (pure.ts / Web Crypto).
 * Rode com -Dent.dir=<pasta com pub.b64 e token.txt>; sem isso, o teste é ignorado.
 */
class EntitlementVerifierTest {
    private val dir = System.getProperty("ent.dir")?.let(::File)

    @Test fun tokenDoServidor() {
        val d = dir ?: return
        val v = EntitlementVerifier(File(d, "pub.b64").readText())
        val token = File(d, "token.txt").readText().trim()
        val now = System.currentTimeMillis() / 1000
        val c = v.verify(token, "u1", now)
        assertNotNull("token válido do servidor deve verificar", c)
        assertEquals("2099-01-01T00:00:00Z", c!!.paidUntil)
        assertNull("outro usuário", v.verify(token, "u2", now))
        assertNull("vencido", v.verify(token, "u1", 4102444800L + 1))
        val tampered = token.substringBefore('.').dropLast(2) + "AA." + token.substringAfter('.')
        assertNull("payload adulterado", v.verify(tampered, "u1", now))
    }

    @Test fun semChaveNaoLibera() {
        val v = EntitlementVerifier("")
        assertNull(v.verify("a.b", "u1", 0))
    }
}
