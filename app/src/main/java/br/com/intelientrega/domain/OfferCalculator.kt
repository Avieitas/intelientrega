package br.com.intelientrega.domain

/**
 * Cálculo puro, sem Android. Nunca inventa dado ausente:
 * - sem tempo, não calcula sobra/hora;
 * - sem distância de retorno informada, não soma retorno;
 * - com distância total exibida, não soma os trechos.
 */
object OfferCalculator {
    const val MAX_OFFER = 2_000.0
    const val MAX_KM = 300.0
    const val MAX_MIN = 600.0

    fun calculate(input: OfferInput, s: CostSettings): CalcResult {
        val settingsErrors = s.validate()
        if (settingsErrors.isNotEmpty()) return CalcResult(Verdict.CONFIG_INVALIDA, reasons = settingsErrors)

        val reasons = mutableListOf<String>()

        val value = input.offerValue?.let { v ->
            if (inRange(v.value, MAX_OFFER)) v.value
            else { reasons += "Valor da oferta inválido ou fora do esperado (até R$ 2.000)."; null }
        }

        // ---- Distância ----
        val distParts = mutableListOf<String>()
        var partial = false
        var baseKm: Double? = null
        val total = input.totalDistanceKm?.let { checkKm(it, "Distância total", reasons) }
        val pickup = input.pickupKm?.let { checkKm(it, "Trecho até a coleta", reasons) }
        val delivery = input.deliveryKm?.let { checkKm(it, "Trecho coleta → cliente", reasons) }
        when {
            total != null -> {
                baseKm = total.value
                distParts += "Distância total ${km(total.value)} (${total.source.label})"
                if (pickup != null || delivery != null) distParts += "Trechos ignorados: a total já os inclui"
            }
            pickup != null && delivery != null -> {
                baseKm = pickup.value + delivery.value
                distParts += "Até a coleta ${km(pickup.value)} (${pickup.source.label})"
                distParts += "Coleta → cliente ${km(delivery.value)} (${delivery.source.label})"
            }
            pickup != null -> {
                baseKm = pickup.value
                partial = true
                distParts += "Só o trecho até a coleta: ${km(pickup.value)}"
            }
            delivery != null -> {
                baseKm = delivery.value
                partial = true
                distParts += "Só o trecho coleta → cliente: ${km(delivery.value)}"
            }
        }
        if (partial) reasons += "Só um trecho da distância é conhecido. Informe o outro ou a distância total."
        val repo = input.repositionKm?.takeIf { it.value.isFinite() && it.value > 0 && it.value <= MAX_KM }
        val distanceKm = baseKm?.let { b ->
            if (repo != null) {
                distParts += "Reposicionamento ${km(repo.value)} (${repo.source.label})"
                b + repo.value
            } else b
        }

        // ---- Tempo ----
        val timeParts = mutableListOf<String>()
        val shown = input.displayedTimeMin?.let { t ->
            if (inRange(t.value, MAX_MIN)) t
            else { reasons += "Tempo inválido ou fora do esperado (até 600 min)."; null }
        }
        // Espera e reposicionamento só entram se houver tempo da oferta: nunca criamos um tempo do nada.
        val timeMin = shown?.let { base ->
            var t = base.value
            timeParts += "Tempo da oferta ${min(base.value)} (${base.source.label})"
            val wait = input.extraWaitMin?.takeIf { it.value.isFinite() && it.value > 0 && it.value <= 180 }
            if (input.waitIncludedInDisplayedTime) {
                timeParts += "Espera já incluída no tempo da oferta (não somada de novo)"
            } else if (wait != null) {
                t += wait.value
                timeParts += "Espera ${min(wait.value)} (${wait.source.label})"
            }
            val repoMin = input.repositionMin?.takeIf { it.value.isFinite() && it.value > 0 && it.value <= MAX_MIN }
            if (repoMin != null) {
                t += repoMin.value
                timeParts += "Reposicionamento ${min(repoMin.value)} (${repoMin.source.label})"
            }
            t
        }

        if (value == null || distanceKm == null) {
            if (value == null && input.offerValue == null) reasons += "Valor da oferta não encontrado."
            if (distanceKm == null && input.totalDistanceKm == null && input.pickupKm == null && input.deliveryKm == null)
                reasons += "Distância não encontrada."
            return CalcResult(
                verdict = if (input.pendingReview) Verdict.CONFIRA else Verdict.INSUFICIENTE,
                distanceKm = distanceKm, distanceParts = distParts, distanceIsPartial = partial,
                timeMin = timeMin, timeParts = timeParts, reasons = reasons,
            )
        }

        // ---- Custos e indicadores ----
        val energyPerKm = s.energyPerKm
        val motoPerKm = energyPerKm + s.otherCostsPerKm
        val cost = CostBreakdown(
            energyPerKm = energyPerKm,
            otherPerKm = s.otherCostsPerKm,
            motoPerKm = motoPerKm,
            energyCost = distanceKm * energyPerKm,
            otherCost = distanceKm * s.otherCostsPerKm,
            totalCost = distanceKm * motoPerKm,
        )
        val surplus = value - cost.totalCost
        val grossPerKm = safeDiv(value, distanceKm)
        val surplusPerHour = timeMin?.let { safeDiv(surplus, it / 60.0) }
        val battery = if (s.isElectric) battery(distanceKm, surplus, input.batteryNowPct, s, reasons) else null

        // ---- Veredito ----
        val verdict = when {
            input.pendingReview || partial -> {
                if (input.pendingReview) reasons += "Há dados ambíguos ou incertos. Confira e toque em \"Confirmar dados\"."
                Verdict.CONFIRA
            }
            battery?.availableKm != null && distanceKm > battery.availableKm -> Verdict.BATERIA_INSUFICIENTE
            surplus <= 0 -> {
                reasons += "O valor não cobre combustível + reserva estimados para ${km(distanceKm)}."
                Verdict.PREJUIZO
            }
            else -> judge(s, timeMin, surplusPerHour, grossPerKm, reasons)
        }

        return CalcResult(
            verdict = verdict,
            distanceKm = distanceKm, distanceParts = distParts, distanceIsPartial = partial,
            timeMin = timeMin, timeParts = timeParts,
            cost = cost, surplus = surplus, grossPerKm = grossPerKm, surplusPerHour = surplusPerHour,
            battery = battery, reasons = reasons,
        )
    }

    private fun battery(
        distanceKm: Double, surplus: Double, now: Valued?, s: CostSettings, reasons: MutableList<String>,
    ): BatteryResult {
        val usedPct = distanceKm / s.rangeKm * 100.0
        val nowPct = now?.value?.takeIf { it.isFinite() && it > 0 && it <= 100 }
        if (now != null && nowPct == null) reasons += "Carga da bateria deve estar entre 1% e 100%."
        val afterPct = nowPct?.let { it - usedPct }
        val availableKm = nowPct?.let { (it - s.minBatteryReservePct).coerceAtLeast(0.0) / 100.0 * s.rangeKm }
        if (afterPct != null && availableKm != null && distanceKm > availableKm) {
            reasons += if (afterPct <= 0)
                "A entrega gasta ${pct(usedPct)} da bateria, e você tem ${pct(nowPct ?: 0.0)}. Não chega."
            else
                "Você terminaria com ${pct(afterPct)}, abaixo da sua reserva mínima de ${pct(s.minBatteryReservePct)}."
        }
        if (usedPct > 100) reasons += "A entrega passa da autonomia com carga cheia (${km(s.rangeKm)})."
        return BatteryResult(
            usedPct = usedPct,
            surplusPerPct = safeDiv(surplus, usedPct),
            nowPct = nowPct, afterPct = afterPct, availableKm = availableKm,
        )
    }

    private fun judge(
        s: CostSettings, timeMin: Double?, perHour: Double?, perKm: Double?, reasons: MutableList<String>,
    ): Verdict {
        val hourGoalActive = s.targetSurplusPerHour > 0
        val kmGoal = s.targetGrossPerKm
        var failed = false
        var checked = 0

        if (hourGoalActive) {
            if (timeMin == null || perHour == null) {
                reasons += "Sem tempo da oferta: não dá para comparar com a meta por hora."
            } else {
                checked++
                if (perHour < s.targetSurplusPerHour) {
                    failed = true
                    reasons += "Sobra/hora ${brl(perHour)} abaixo da meta de ${brl(s.targetSurplusPerHour)}."
                }
            }
        }
        if (kmGoal != null && perKm != null) {
            checked++
            if (perKm < kmGoal) {
                failed = true
                reasons += "R$/km bruto ${brl(perKm)} abaixo da meta de ${brl(kmGoal)}."
            }
        }
        return when {
            failed -> Verdict.ABAIXO_DA_META
            checked == 0 -> Verdict.INSUFICIENTE
            hourGoalActive && timeMin == null -> Verdict.ATENDE_PARCIAL
            else -> Verdict.COMPENSA
        }
    }

    private fun checkKm(v: Valued, name: String, reasons: MutableList<String>): Valued? =
        if (inRange(v.value, MAX_KM)) v
        else { reasons += "$name inválida ou fora do esperado (até 300 km)."; null }

    private fun inRange(x: Double, max: Double) = x.isFinite() && x > 0 && x <= max

    /** Divisão protegida: retorna null para divisor zero, negativo ou não finito. */
    fun safeDiv(a: Double, b: Double): Double? =
        if (!a.isFinite() || !b.isFinite() || b <= 0.0) null else (a / b).takeIf { it.isFinite() }
}
