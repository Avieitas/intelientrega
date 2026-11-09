package br.com.intelientrega

import br.com.intelientrega.domain.CostSettings
import br.com.intelientrega.domain.FieldSource
import br.com.intelientrega.domain.OfferCalculator
import br.com.intelientrega.domain.OfferDraft
import br.com.intelientrega.domain.Verdict
import br.com.intelientrega.parsing.BrNumbers
import br.com.intelientrega.parsing.BrTokenizer
import br.com.intelientrega.parsing.GenericOfferParser
import br.com.intelientrega.parsing.OcrText
import br.com.intelientrega.parsing.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Textos de teste SINTÉTICOS. Não representam telas reais da 99 ou do iFood.
 */
class ParserTest {
    private val eps = 1e-9
    private val parser = GenericOfferParser()

    @Test fun numerosBrasileiros() {
        assertEquals(15.90, BrNumbers.parse("15,90")!!, eps)
        assertEquals(1234.50, BrNumbers.parse("1.234,50")!!, eps)
        assertEquals(15.90, BrNumbers.parse("15.90")!!, eps)
        assertEquals(1234.0, BrNumbers.parse("1.234")!!, eps)
        assertEquals(15.0, BrNumbers.parse("15")!!, eps)
        assertNull(BrNumbers.parse("1,2,3"))
        assertNull(BrNumbers.parse(""))
    }

    @Test fun tokensBasicos() {
        val t = BrTokenizer.tokenizeLine("R$ 15,90 • 7,2 km • 800 m • 25 min", 0)
        assertEquals(listOf(TokenType.MONEY, TokenType.DISTANCE, TokenType.DISTANCE, TokenType.TIME), t.map { it.type })
        assertEquals(15.90, t[0].value, eps)
        assertEquals(7.2, t[1].value, eps)
        assertEquals(0.8, t[2].value, eps)
        assertEquals(25.0, t[3].value, eps)
    }

    @Test fun variacoesDeOcr() {
        val t = BrTokenizer.tokenizeLine("RS15,90 7.2km 1h 10min 20-30 min", 0)
        assertEquals(15.90, t[0].value, eps)
        assertEquals(7.2, t[1].value, eps)
        assertEquals(70.0, t[2].value, eps)
        assertEquals(30.0, t[3].value, eps)
        assertTrue(t[3].isRange)
    }

    @Test fun minNaoViraMetro() {
        val t = BrTokenizer.tokenizeLine("12 min", 0)
        assertEquals(1, t.size)
        assertEquals(TokenType.TIME, t[0].type)
    }

    @Test fun ofertaSimples() {
        val p = parser.parse(OcrText.fromPlainText("Nova oferta\nR$ 12,50\n6,4 km\n22 min"))
        assertEquals(12.5, p.price!!.candidate.value, eps)
        assertEquals(6.4, p.totalDistance!!.candidate.value, eps)
        assertTrue("distância sem rótulo deve ser conferida", p.totalDistance!!.needsReview)
        assertEquals(22.0, p.time!!.candidate.value, eps)
    }

    @Test fun trechosRotulados() {
        val p = parser.parse(OcrText.fromPlainText("R$ 14,00\nColeta 1,2 km\nEntrega 4,8 km\n25 min"))
        assertNull(p.totalDistance)
        assertEquals(1.2, p.pickupDistance!!.candidate.value, eps)
        assertEquals(4.8, p.deliveryDistance!!.candidate.value, eps)

        val d = OfferDraft.from(p, CostSettings())
        val r = OfferCalculator.calculate(d.toInput(CostSettings()), CostSettings())
        assertEquals(6.0, r.distanceKm!!, eps)
    }

    @Test fun rotuloNaLinhaAnterior() {
        val p = parser.parse(OcrText.fromPlainText("Até a coleta\n900 m\nAté o cliente\n3,1 km"))
        assertEquals(0.9, p.pickupDistance!!.candidate.value, eps)
        assertEquals(3.1, p.deliveryDistance!!.candidate.value, eps)
    }

    @Test fun totalPrevaleceSobreTrechos() {
        val p = parser.parse(OcrText.fromPlainText("Coleta 1 km\nEntrega 4 km\nTotal 5 km\nR$ 10,00"))
        assertEquals(5.0, p.totalDistance!!.candidate.value, eps)
        assertNull(p.pickupDistance)
    }

    @Test fun variosPrecosSaoAmbiguos() {
        val p = parser.parse(OcrText.fromPlainText("R$ 9,00\nR$ 12,00\n5 km\n20 min"))
        assertNull(p.price)
        assertEquals(2, p.priceCandidates.size)
        val d = OfferDraft.from(p, CostSettings())
        assertTrue(d.price.needsReview)
        assertEquals(2, d.price.candidates.size)
        val r = OfferCalculator.calculate(d.toInput(CostSettings()), CostSettings())
        assertEquals(Verdict.CONFIRA, r.verdict)
    }

    @Test fun precoRepetidoNaoEhAmbiguo() {
        val p = parser.parse(OcrText.fromPlainText("R$ 12,00\nResumo R$ 12,00\n5 km"))
        assertNotNull(p.price)
    }

    @Test fun bonusPedeConferencia() {
        val p = parser.parse(OcrText.fromPlainText("R$ 10,00\nBônus R$ 2,00\n5 km"))
        assertNull(p.price)
        assertTrue(p.issues.any { it.contains("bônus") })
    }

    @Test fun telaVazia() {
        val p = parser.parse(OcrText(emptyList()))
        assertTrue(!p.textFound)
        val r = OfferCalculator.calculate(OfferDraft.from(p, CostSettings()).toInput(CostSettings()), CostSettings())
        assertEquals(Verdict.INSUFICIENTE, r.verdict)
    }

    @Test fun correcaoMudaOrigemEConfirmacaoLiberaVeredito() {
        val p = parser.parse(OcrText.fromPlainText("R$ 20,00\n5 km\n20 min"))
        var d = OfferDraft.from(p, CostSettings())
        assertEquals(FieldSource.LIDO, d.price.source)
        d = d.copy(totalKm = d.totalKm.edited("6"))
        assertEquals(FieldSource.CORRIGIDO, d.totalKm.source)
        val r = OfferCalculator.calculate(d.toInput(CostSettings()), CostSettings())
        assertTrue(r.verdict != Verdict.CONFIRA)
    }
}
