package br.com.intelientrega

import br.com.intelientrega.domain.CostSettings
import br.com.intelientrega.domain.FieldSource.CORRIGIDO
import br.com.intelientrega.domain.FieldSource.LIDO
import br.com.intelientrega.domain.OfferCalculator
import br.com.intelientrega.domain.OfferDraft
import br.com.intelientrega.domain.OfferInput
import br.com.intelientrega.domain.Valued
import br.com.intelientrega.domain.VehicleType
import br.com.intelientrega.domain.Verdict
import br.com.intelientrega.parsing.GenericOfferParser
import br.com.intelientrega.parsing.OcrText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElectricTest {
    private val eps = 1e-9
    // 1 kWh × R$ 1,00 = R$ 1,00 por carga; 50 km de autonomia → R$ 0,02/km de energia; + 0,08 reserva = 0,10/km
    private val s = CostSettings(
        vehicleType = VehicleType.ELETRICO, batteryKwh = 1.0, energyPricePerKwh = 1.0, rangeKm = 50.0,
        minBatteryReservePct = 10.0, otherCostsPerKm = 0.08, targetSurplusPerHour = 20.0, extraWaitMin = 0.0,
        // valores de combustão inválidos de propósito: não devem afetar o modo elétrico
        fuelPricePerLiter = 0.0, kmPerLiter = 0.0,
    )
    private fun v(x: Double) = Valued(x, LIDO)

    @Test fun custoEletrico() {
        val r = OfferCalculator.calculate(OfferInput(offerValue = v(12.0), totalDistanceKm = v(10.0), displayedTimeMin = v(30.0)), s)
        assertEquals(0.02, r.cost!!.energyPerKm, eps)
        assertEquals(0.10, r.cost!!.motoPerKm, eps)
        assertEquals(11.0, r.surplus!!, eps)
        assertEquals(20.0, r.battery!!.usedPct, eps)       // 10 km de 50 km
        assertEquals(0.55, r.battery!!.surplusPerPct!!, eps) // R$ 11 ÷ 20%
        assertNull(r.battery!!.afterPct)                     // carga atual não informada: não inventa
        assertEquals(Verdict.COMPENSA, r.verdict)
    }

    @Test fun bateriaDaComFolga() {
        val r = OfferCalculator.calculate(
            OfferInput(offerValue = v(12.0), totalDistanceKm = v(10.0), displayedTimeMin = v(30.0), batteryNowPct = Valued(60.0, CORRIGIDO)), s,
        )
        assertEquals(40.0, r.battery!!.afterPct!!, eps)
        assertEquals(25.0, r.battery!!.availableKm!!, eps) // (60 − 10)% de 50 km
        assertEquals(Verdict.COMPENSA, r.verdict)
    }

    @Test fun bateriaAbaixoDaReserva() {
        val r = OfferCalculator.calculate(
            OfferInput(offerValue = v(12.0), totalDistanceKm = v(10.0), displayedTimeMin = v(30.0), batteryNowPct = Valued(25.0, CORRIGIDO)), s,
        )
        // 25% → sobra 5%, abaixo da reserva de 10%
        assertEquals(Verdict.BATERIA_INSUFICIENTE, r.verdict)
    }

    @Test fun bateriaInvalidaEhIgnorada() {
        val r = OfferCalculator.calculate(
            OfferInput(offerValue = v(12.0), totalDistanceKm = v(10.0), displayedTimeMin = v(30.0), batteryNowPct = Valued(150.0, CORRIGIDO)), s,
        )
        assertNull(r.battery!!.nowPct)
        assertEquals(Verdict.COMPENSA, r.verdict)
    }

    @Test fun autonomiaZeroEhConfigInvalida() {
        val r = OfferCalculator.calculate(OfferInput(offerValue = v(12.0), totalDistanceKm = v(10.0)), s.copy(rangeKm = 0.0))
        assertEquals(Verdict.CONFIG_INVALIDA, r.verdict)
    }

    @Test fun combustaoNaoTemBateria() {
        val r = OfferCalculator.calculate(OfferInput(offerValue = v(12.0), totalDistanceKm = v(10.0)), CostSettings())
        assertNull(r.battery)
    }

    @Test fun cargaHerdadaDaOfertaAnterior() {
        val parser = GenericOfferParser()
        val first = OfferDraft.from(parser.parse(OcrText.fromPlainText("R$ 10,00\n5 km")), s)
        val withBattery = first.copy(batteryPct = first.batteryPct.edited("70"))
        val next = OfferDraft.from(parser.parse(OcrText.fromPlainText("R$ 8,00\n4 km")), s, previous = withBattery)
        assertEquals("70", next.batteryPct.text)
    }
}
