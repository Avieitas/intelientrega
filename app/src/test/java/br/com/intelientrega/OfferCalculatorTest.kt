package br.com.intelientrega

import br.com.intelientrega.domain.CostSettings
import br.com.intelientrega.domain.FieldSource.CONFIGURADO
import br.com.intelientrega.domain.FieldSource.LIDO
import br.com.intelientrega.domain.OfferCalculator
import br.com.intelientrega.domain.OfferInput
import br.com.intelientrega.domain.Valued
import br.com.intelientrega.domain.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferCalculatorTest {
    // combustível 6,00 / 30 km/l = 0,20/km; + 0,10 reserva = 0,30/km
    private val s = CostSettings(
        fuelPricePerLiter = 6.0, kmPerLiter = 30.0, otherCostsPerKm = 0.10,
        targetSurplusPerHour = 20.0, targetGrossPerKm = null, extraWaitMin = 5.0,
    )

    private fun lido(v: Double) = Valued(v, LIDO)
    private val eps = 1e-9

    @Test fun formulasBasicas() {
        val r = OfferCalculator.calculate(
            OfferInput(offerValue = lido(15.0), totalDistanceKm = lido(10.0), displayedTimeMin = lido(25.0),
                extraWaitMin = Valued(5.0, CONFIGURADO)), s,
        )
        val c = r.cost!!
        assertEquals(0.20, c.energyPerKm, eps)
        assertEquals(0.30, c.motoPerKm, eps)
        assertEquals(3.0, c.totalCost, eps)
        assertEquals(12.0, r.surplus!!, eps)
        assertEquals(1.5, r.grossPerKm!!, eps)
        assertEquals(30.0, r.timeMin!!, eps) // 25 + 5 de espera
        assertEquals(24.0, r.surplusPerHour!!, eps)
        assertEquals(Verdict.COMPENSA, r.verdict)
    }

    @Test fun esperaJaIncluidaNaoSomaDuasVezes() {
        val r = OfferCalculator.calculate(
            OfferInput(offerValue = lido(15.0), totalDistanceKm = lido(10.0), displayedTimeMin = lido(30.0),
                waitIncludedInDisplayedTime = true, extraWaitMin = Valued(5.0, CONFIGURADO)), s,
        )
        assertEquals(30.0, r.timeMin!!, eps)
    }

    @Test fun totalExibidaNaoSomaTrechos() {
        val r = OfferCalculator.calculate(
            OfferInput(offerValue = lido(15.0), totalDistanceKm = lido(8.0), pickupKm = lido(2.0), deliveryKm = lido(6.0)), s,
        )
        assertEquals(8.0, r.distanceKm!!, eps)
    }

    @Test fun somaTrechosEReposicionamento() {
        val r = OfferCalculator.calculate(
            OfferInput(offerValue = lido(15.0), pickupKm = lido(2.0), deliveryKm = lido(6.0),
                repositionKm = Valued(1.5, CONFIGURADO)), s,
        )
        assertEquals(9.5, r.distanceKm!!, eps)
    }

    @Test fun semTempoNaoInventaSobraHora() {
        val r = OfferCalculator.calculate(
            OfferInput(offerValue = lido(15.0), totalDistanceKm = lido(10.0), extraWaitMin = Valued(5.0, CONFIGURADO)), s,
        )
        assertNull(r.timeMin)
        assertNull(r.surplusPerHour)
        assertNotNull(r.grossPerKm)
        assertEquals(Verdict.INSUFICIENTE, r.verdict) // só há meta por hora, e falta tempo
    }

    @Test fun semTempoComMetaKmAtendeParcial() {
        val r = OfferCalculator.calculate(
            OfferInput(offerValue = lido(15.0), totalDistanceKm = lido(10.0)), s.copy(targetGrossPerKm = 1.2),
        )
        assertEquals(Verdict.ATENDE_PARCIAL, r.verdict)
    }

    @Test fun faltandoValorOuDistancia() {
        assertEquals(Verdict.INSUFICIENTE, OfferCalculator.calculate(OfferInput(totalDistanceKm = lido(5.0)), s).verdict)
        assertEquals(Verdict.INSUFICIENTE, OfferCalculator.calculate(OfferInput(offerValue = lido(5.0)), s).verdict)
    }

    @Test fun ambiguoPedeConferencia() {
        val r = OfferCalculator.calculate(
            OfferInput(offerValue = lido(15.0), totalDistanceKm = lido(10.0), displayedTimeMin = lido(25.0), pendingReview = true), s,
        )
        assertEquals(Verdict.CONFIRA, r.verdict)
    }

    @Test fun distanciaZeroOuInvalidaNaoDivide() {
        val r = OfferCalculator.calculate(OfferInput(offerValue = lido(15.0), totalDistanceKm = lido(0.0)), s)
        assertEquals(Verdict.INSUFICIENTE, r.verdict)
        assertNull(r.grossPerKm)
        val r2 = OfferCalculator.calculate(OfferInput(offerValue = lido(15.0), totalDistanceKm = lido(Double.NaN)), s)
        assertEquals(Verdict.INSUFICIENTE, r2.verdict)
        assertNull(OfferCalculator.safeDiv(1.0, 0.0))
    }

    @Test fun tempoZeroNaoDivide() {
        val r = OfferCalculator.calculate(
            OfferInput(offerValue = lido(15.0), totalDistanceKm = lido(10.0), displayedTimeMin = lido(0.0)), s,
        )
        assertNull(r.surplusPerHour)
    }

    @Test fun configInvalida() {
        val r = OfferCalculator.calculate(
            OfferInput(offerValue = lido(15.0), totalDistanceKm = lido(10.0)), s.copy(kmPerLiter = 0.0),
        )
        assertEquals(Verdict.CONFIG_INVALIDA, r.verdict)
    }

    @Test fun prejuizo() {
        val r = OfferCalculator.calculate(OfferInput(offerValue = lido(2.0), totalDistanceKm = lido(10.0)), s)
        assertEquals(Verdict.PREJUIZO, r.verdict)
        assertTrue(r.surplus!! < 0)
    }

    @Test fun abaixoDaMeta() {
        val r = OfferCalculator.calculate(
            OfferInput(offerValue = lido(8.0), totalDistanceKm = lido(10.0), displayedTimeMin = lido(40.0)), s,
        )
        assertEquals(Verdict.ABAIXO_DA_META, r.verdict)
    }

    @Test fun umTrechoSoEhParcial() {
        val r = OfferCalculator.calculate(OfferInput(offerValue = lido(15.0), deliveryKm = lido(5.0)), s)
        assertTrue(r.distanceIsPartial)
        assertEquals(Verdict.CONFIRA, r.verdict)
    }
}
