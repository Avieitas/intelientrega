package br.com.intelientrega.domain

/** Cor de cada indicador no cartão de resultado. NEUTRO = sem meta para comparar. */
enum class Signal { BOM, MEDIO, RUIM, NEUTRO }

data class Metric(val label: String, val value: String, val unit: String, val signal: Signal)

/**
 * Semáforo por indicador, com base nas metas do usuário:
 * - verde: atinge a meta;
 * - amarelo: entre [AMBER_RATIO] da meta e a meta;
 * - vermelho: abaixo disso (ou sobra negativa).
 * Sem meta configurada, o indicador fica neutro: nunca inventamos um "bom" sem critério.
 */
object Semaphore {
    const val AMBER_RATIO = 0.8

    /** Margem = sobra ÷ valor. Acima de 70% é boa; entre 50% e 70% é média. */
    const val MARGIN_GOOD = 0.70
    const val MARGIN_OK = 0.50

    fun vsGoal(value: Double?, goal: Double?): Signal = when {
        value == null || goal == null || goal <= 0 -> Signal.NEUTRO
        value >= goal -> Signal.BOM
        value >= goal * AMBER_RATIO -> Signal.MEDIO
        else -> Signal.RUIM
    }

    fun surplus(value: Double?): Signal = when {
        value == null -> Signal.NEUTRO
        value <= 0 -> Signal.RUIM
        else -> Signal.BOM
    }

    fun margin(ratio: Double?): Signal = when {
        ratio == null -> Signal.NEUTRO
        ratio >= MARGIN_GOOD -> Signal.BOM
        ratio >= MARGIN_OK -> Signal.MEDIO
        else -> Signal.RUIM
    }

    /** Bateria: verde se sobra acima da reserva com folga, amarelo se perto, vermelho se não chega. */
    fun battery(afterPct: Double?, reservePct: Double): Signal = when {
        afterPct == null -> Signal.NEUTRO
        afterPct < reservePct -> Signal.RUIM
        afterPct < reservePct + 10 -> Signal.MEDIO
        else -> Signal.BOM
    }

    /** Os 4 indicadores do cartão compacto, na ordem de exibição. */
    fun metrics(r: CalcResult, offerValue: Double?, s: CostSettings): List<Metric> {
        val margin = r.surplus?.let { sp -> offerValue?.takeIf { it > 0 }?.let { sp / it } }
        val last = if (s.isElectric) {
            val b = r.battery
            if (b?.afterPct != null) Metric("Bateria no fim", pct(b.afterPct.coerceAtLeast(0.0)), "", battery(b.afterPct, s.minBatteryReservePct))
            else Metric("Usa da bateria", b?.let { pct(it.usedPct) } ?: "—", "", Signal.NEUTRO)
        } else {
            Metric("Margem", margin?.let { pct(it * 100) } ?: "—", "", margin(margin))
        }
        return listOf(
            Metric("Por km", r.grossPerKm?.let { brlShort(it) } ?: "—", "/km", vsGoal(r.grossPerKm, s.targetGrossPerKm)),
            Metric("Por hora", r.surplusPerHour?.let { brlShort(it) } ?: "—", "/h",
                vsGoal(r.surplusPerHour, s.targetSurplusPerHour.takeIf { it > 0 })),
            Metric("Sobra", r.surplus?.let { brlShort(it) } ?: "—", "", surplus(r.surplus)),
            last,
        )
    }

    /** Sinal geral do veredito, para a cor do cabeçalho e da bolinha. */
    fun of(v: Verdict): Signal = when (v) {
        Verdict.COMPENSA -> Signal.BOM
        Verdict.ATENDE_PARCIAL, Verdict.ABAIXO_DA_META -> Signal.MEDIO
        Verdict.PREJUIZO, Verdict.BATERIA_INSUFICIENTE -> Signal.RUIM
        Verdict.CONFIRA, Verdict.INSUFICIENTE, Verdict.CONFIG_INVALIDA -> Signal.NEUTRO
    }
}

/** "R$ 2,15" sem o espaço largo, para caber nos blocos pequenos. */
fun brlShort(v: Double): String = brl(v).replace(" ", " ")
