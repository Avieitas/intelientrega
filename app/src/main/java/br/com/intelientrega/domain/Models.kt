package br.com.intelientrega.domain

/** Origem de cada número mostrado. O painel exibe isso ao lado de cada campo. */
enum class FieldSource(val label: String) {
    LIDO("lido da tela"),
    CORRIGIDO("informado por você"),
    CONFIGURADO("das suas configurações"),
    CALCULADO("calculado"),
    ESTIMADO("estimado"),
}

data class Valued(val value: Double, val source: FieldSource)

enum class VehicleType(val label: String) { COMBUSTAO("Moto a combustível"), ELETRICO("Elétrico / autopropelido") }

/**
 * Configurações do motoboy. Valores em R$, km e minutos.
 * [otherCostsPerKm] é UMA reserva única para manutenção, pneus, óleo e depreciação.
 * O combustível fica de fora dela, para não ser contado duas vezes.
 */
data class CostSettings(
    val vehicleType: VehicleType = VehicleType.COMBUSTAO,
    // --- combustão ---
    val fuelPricePerLiter: Double = 6.30,
    val kmPerLiter: Double = 35.0,
    // --- elétrico ---
    /** Capacidade da bateria em kWh (volts × Ah ÷ 1000). */
    val batteryKwh: Double = 1.0,
    /** Tarifa de energia em R$/kWh. */
    val energyPricePerKwh: Double = 0.90,
    /** Autonomia REAL com carga cheia, rodando em entrega (não a do fabricante). */
    val rangeKm: Double = 45.0,
    /** Bateria mínima que o usuário quer manter ao fim da entrega (%). */
    val minBatteryReservePct: Double = 15.0,
    // --- comum ---
    val otherCostsPerKm: Double = 0.15,
    val targetSurplusPerHour: Double = 25.0,
    /** null = sem meta de R$/km bruto. */
    val targetGrossPerKm: Double? = null,
    val extraWaitMin: Double = 5.0,
    /** Se true, por padrão o tempo exibido na oferta é tratado como já incluindo a espera. */
    val waitIncludedByDefault: Boolean = false,
    val defaultRepositionKm: Double = 0.0,
    val defaultRepositionMin: Double = 0.0,
) {
    val isElectric: Boolean get() = vehicleType == VehicleType.ELETRICO

    /** Custo de energia por km: combustível (R$/l ÷ km/l) ou eletricidade (kWh × R$/kWh ÷ autonomia). */
    val energyPerKm: Double
        get() = if (isElectric) batteryKwh * energyPricePerKwh / rangeKm else fuelPricePerLiter / kmPerLiter

    /** Quanto custa encher a bateria do zero. */
    val fullChargeCost: Double get() = batteryKwh * energyPricePerKwh

    fun validate(): List<String> = buildList {
        if (isElectric) {
            if (!batteryKwh.isFinite() || batteryKwh <= 0 || batteryKwh > 50)
                add("Capacidade da bateria deve estar entre 0,01 e 50 kWh.")
            if (!energyPricePerKwh.isFinite() || energyPricePerKwh <= 0 || energyPricePerKwh > 10)
                add("Tarifa de energia deve estar entre R$ 0,01 e R$ 10 por kWh.")
            if (!rangeKm.isFinite() || rangeKm <= 0 || rangeKm > 500)
                add("Autonomia deve estar entre 1 e 500 km.")
            if (!minBatteryReservePct.isFinite() || minBatteryReservePct < 0 || minBatteryReservePct >= 100)
                add("Reserva mínima de bateria deve estar entre 0% e 99%.")
        } else {
            if (!fuelPricePerLiter.isFinite() || fuelPricePerLiter <= 0 || fuelPricePerLiter > 30)
                add("Preço do combustível deve estar entre R$ 0,01 e R$ 30,00 por litro.")
            if (!kmPerLiter.isFinite() || kmPerLiter <= 0 || kmPerLiter > 150)
                add("Consumo deve estar entre 0,1 e 150 km/l.")
        }
        if (!otherCostsPerKm.isFinite() || otherCostsPerKm < 0 || otherCostsPerKm > 10)
            add("Reserva de manutenção deve estar entre R$ 0 e R$ 10 por km.")
        if (!targetSurplusPerHour.isFinite() || targetSurplusPerHour < 0 || targetSurplusPerHour > 1000)
            add("Meta de sobra por hora deve estar entre R$ 0 e R$ 1.000.")
        targetGrossPerKm?.let {
            if (!it.isFinite() || it <= 0 || it > 100) add("Meta de R$/km deve estar entre R$ 0,01 e R$ 100 (ou em branco).")
        }
        if (!extraWaitMin.isFinite() || extraWaitMin < 0 || extraWaitMin > 180)
            add("Espera adicional deve estar entre 0 e 180 minutos.")
        if (!defaultRepositionKm.isFinite() || defaultRepositionKm < 0 || defaultRepositionKm > 100)
            add("Reposicionamento deve estar entre 0 e 100 km.")
        if (!defaultRepositionMin.isFinite() || defaultRepositionMin < 0 || defaultRepositionMin > 300)
            add("Tempo de reposicionamento deve estar entre 0 e 300 minutos.")
    }
}

/**
 * Dados de uma oferta prontos para cálculo. Campos nulos = desconhecidos.
 * Quando [totalDistanceKm] existe, os trechos NÃO são somados.
 */
data class OfferInput(
    val offerValue: Valued? = null,
    val totalDistanceKm: Valued? = null,
    val pickupKm: Valued? = null,
    val deliveryKm: Valued? = null,
    val repositionKm: Valued? = null,
    val displayedTimeMin: Valued? = null,
    val waitIncludedInDisplayedTime: Boolean = false,
    val extraWaitMin: Valued? = null,
    val repositionMin: Valued? = null,
    /** Só para elétricos: carga atual da bateria (%). Opcional. */
    val batteryNowPct: Valued? = null,
    /** Algum campo ainda precisa ser conferido pelo usuário (ambíguo ou incerto). */
    val pendingReview: Boolean = false,
)

data class CostBreakdown(
    /** Combustível ou eletricidade por km, conforme o veículo. */
    val energyPerKm: Double,
    val otherPerKm: Double,
    val motoPerKm: Double,
    val energyCost: Double,
    val otherCost: Double,
    val totalCost: Double,
)

enum class Verdict(val label: String) {
    COMPENSA("Compensa pelas suas metas"),
    ATENDE_PARCIAL("Atende o R$/km. Sem tempo para avaliar por hora"),
    ABAIXO_DA_META("Abaixo da sua meta"),
    PREJUIZO("Não cobre os custos estimados"),
    BATERIA_INSUFICIENTE("A bateria não garante essa entrega"),
    CONFIRA("Confira os dados"),
    INSUFICIENTE("Dados insuficientes"),
    CONFIG_INVALIDA("Revise suas configurações"),
}

data class CalcResult(
    val verdict: Verdict,
    val distanceKm: Double? = null,
    val distanceParts: List<String> = emptyList(),
    val distanceIsPartial: Boolean = false,
    val timeMin: Double? = null,
    val timeParts: List<String> = emptyList(),
    val cost: CostBreakdown? = null,
    val surplus: Double? = null,
    val grossPerKm: Double? = null,
    val surplusPerHour: Double? = null,
    val battery: BatteryResult? = null,
    val reasons: List<String> = emptyList(),
)

/** Indicadores de bateria (só veículo elétrico). */
data class BatteryResult(
    /** % da bateria cheia que a entrega consome. */
    val usedPct: Double,
    /** Sobra estimada por 1% de bateria gasto. */
    val surplusPerPct: Double?,
    /** Carga atual informada (%), se houver. */
    val nowPct: Double?,
    /** Carga prevista ao fim da entrega (%), se a atual foi informada. */
    val afterPct: Double?,
    /** Km que dá para rodar mantendo a reserva mínima, se a atual foi informada. */
    val availableKm: Double?,
)
