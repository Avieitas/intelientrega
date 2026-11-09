package br.com.intelientrega

import br.com.intelientrega.domain.CostSettings
import br.com.intelientrega.domain.FieldSource.LIDO
import br.com.intelientrega.domain.OfferCalculator
import br.com.intelientrega.domain.OfferInput
import br.com.intelientrega.domain.Semaphore
import br.com.intelientrega.domain.Signal
import br.com.intelientrega.domain.Valued
import br.com.intelientrega.domain.VehicleType
import br.com.intelientrega.domain.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class SemaphoreTest {
    private fun v(x: Double) = Valued(x, LIDO)

    @Test fun metaPorFaixa() {
        assertEquals(Signal.BOM, Semaphore.vsGoal(25.0, 25.0))
        assertEquals(Signal.MEDIO, Semaphore.vsGoal(20.0, 25.0)) // exatamente 80%
        assertEquals(Signal.RUIM, Semaphore.vsGoal(19.99, 25.0))
        assertEquals(Signal.NEUTRO, Semaphore.vsGoal(10.0, null))
        assertEquals(Signal.NEUTRO, Semaphore.vsGoal(null, 25.0))
        assertEquals(Signal.NEUTRO, Semaphore.vsGoal(10.0, 0.0))
    }

    @Test fun sobraEMargem() {
        assertEquals(Signal.RUIM, Semaphore.surplus(0.0))
        assertEquals(Signal.BOM, Semaphore.surplus(0.01))
        assertEquals(Signal.BOM, Semaphore.margin(0.70))
        assertEquals(Signal.MEDIO, Semaphore.margin(0.5))
        assertEquals(Signal.RUIM, Semaphore.margin(0.49))
    }

    @Test fun quatroIndicadoresCombustao() {
        val s = CostSettings(fuelPricePerLiter = 6.0, kmPerLiter = 30.0, otherCostsPerKm = 0.1,
            targetSurplusPerHour = 30.0, targetGrossPerKm = 2.0, extraWaitMin = 0.0)
        // 10 km × 0,30 = R$ 3,00 de custo; sobra 17; 30 min → 34/h; 2,00/km; margem 85%
        val r = OfferCalculator.calculate(OfferInput(offerValue = v(20.0), totalDistanceKm = v(10.0), displayedTimeMin = v(30.0)), s)
        assertEquals(Verdict.COMPENSA, r.verdict)
        val m = Semaphore.metrics(r, 20.0, s)
        assertEquals(listOf("Por km", "Por hora", "Sobra", "Margem"), m.map { it.label })
        assertEquals(listOf(Signal.BOM, Signal.BOM, Signal.BOM, Signal.BOM), m.map { it.signal })
        assertEquals("85%", m[3].value)
    }

    @Test fun semTempoPorHoraNeutro() {
        val s = CostSettings(targetSurplusPerHour = 30.0)
        val r = OfferCalculator.calculate(OfferInput(offerValue = v(20.0), totalDistanceKm = v(10.0)), s)
        val m = Semaphore.metrics(r, 20.0, s)
        assertEquals("—", m[1].value)
        assertEquals(Signal.NEUTRO, m[1].signal)
        assertEquals(Signal.NEUTRO, m[0].signal) // sem meta de R$/km
    }

    @Test fun eletricoMostraBateria() {
        val s = CostSettings(vehicleType = VehicleType.ELETRICO, batteryKwh = 1.0, energyPricePerKwh = 1.0,
            rangeKm = 50.0, minBatteryReservePct = 10.0, otherCostsPerKm = 0.0, targetSurplusPerHour = 0.0)
        val r = OfferCalculator.calculate(OfferInput(offerValue = v(15.0), totalDistanceKm = v(10.0), batteryNowPct = v(35.0)), s)
        val m = Semaphore.metrics(r, 15.0, s)
        assertEquals("Bateria no fim", m[3].label)
        assertEquals("15%", m[3].value) // 35% − 20% usados
        assertEquals(Signal.MEDIO, m[3].signal) // acima da reserva (10%), mas a menos de 10 pontos dela
    }

    @Test fun vereditoParaCor() {
        assertEquals(Signal.BOM, Semaphore.of(Verdict.COMPENSA))
        assertEquals(Signal.RUIM, Semaphore.of(Verdict.PREJUIZO))
        assertEquals(Signal.NEUTRO, Semaphore.of(Verdict.CONFIRA))
    }
}
