package br.com.intelientrega.domain

import br.com.intelientrega.parsing.BrNumbers
import br.com.intelientrega.parsing.Candidate
import br.com.intelientrega.parsing.ParsedOffer
import br.com.intelientrega.parsing.Pick
import br.com.intelientrega.parsing.Platform

/** Campo editável no painel. Texto vazio = desconhecido. */
data class DraftField(
    val text: String = "",
    val source: FieldSource? = null,
    val needsReview: Boolean = false,
    /** Alternativas lidas na tela, para o usuário escolher quando houver ambiguidade. */
    val candidates: List<Candidate> = emptyList(),
) {
    val number: Double? get() = if (text.isBlank()) null else BrNumbers.parse(text)
    val isInvalid: Boolean get() = text.isNotBlank() && number == null

    fun edited(newText: String) = copy(
        text = newText,
        source = if (newText.isBlank()) null else FieldSource.CORRIGIDO,
        needsReview = false,
    )

    fun chose(c: Candidate, decimals: Int? = null) = copy(
        text = plain(c.value, decimals), source = FieldSource.CORRIGIDO, needsReview = false,
    )

    fun toValued(): Valued? = number?.let { Valued(it, source ?: FieldSource.CORRIGIDO) }
}

data class OfferDraft(
    val platform: Platform = Platform.GENERICO,
    val price: DraftField = DraftField(),
    val totalKm: DraftField = DraftField(),
    val pickupKm: DraftField = DraftField(),
    val deliveryKm: DraftField = DraftField(),
    val repositionKm: DraftField = DraftField(),
    val timeMin: DraftField = DraftField(),
    /** Elétrico: carga atual (%), informada pelo usuário e mantida entre ofertas da sessão. */
    val batteryPct: DraftField = DraftField(),
    val waitIncluded: Boolean = false,
    val issues: List<String> = emptyList(),
    val confirmed: Boolean = false,
) {
    private val fields get() = listOf(price, totalKm, pickupKm, deliveryKm, repositionKm, timeMin, batteryPct)
    val hasPendingReview: Boolean get() = !confirmed && (fields.any { it.needsReview } || fields.any { it.isInvalid })

    fun confirm() = copy(
        price = price.copy(needsReview = false), totalKm = totalKm.copy(needsReview = false),
        pickupKm = pickupKm.copy(needsReview = false), deliveryKm = deliveryKm.copy(needsReview = false),
        timeMin = timeMin.copy(needsReview = false), confirmed = true,
    )

    fun toInput(s: CostSettings): OfferInput = OfferInput(
        offerValue = price.toValued(),
        totalDistanceKm = totalKm.toValued(),
        pickupKm = pickupKm.toValued(),
        deliveryKm = deliveryKm.toValued(),
        repositionKm = repositionKm.toValued(),
        displayedTimeMin = timeMin.toValued(),
        waitIncludedInDisplayedTime = waitIncluded,
        extraWaitMin = s.extraWaitMin.takeIf { it > 0 }?.let { Valued(it, FieldSource.CONFIGURADO) },
        repositionMin = s.defaultRepositionMin.takeIf { it > 0 }?.let { Valued(it, FieldSource.CONFIGURADO) },
        batteryNowPct = if (s.isElectric) batteryPct.toValued() else null,
        pendingReview = hasPendingReview || fields.any { it.isInvalid },
    )

    companion object {
        /** [previous]: rascunho anterior da sessão, de onde a carga da bateria é herdada. */
        fun from(p: ParsedOffer, s: CostSettings, previous: OfferDraft? = null): OfferDraft {
            fun field(pick: Pick?, all: List<Candidate>, decimals: Int? = null, ambiguous: Boolean = false) = DraftField(
                text = pick?.let { plain(it.candidate.value, decimals) } ?: "",
                source = pick?.let { FieldSource.LIDO },
                needsReview = pick?.needsReview ?: ambiguous,
                candidates = if (all.distinctBy { it.value }.size > 1) all.distinctBy { it.value } else emptyList(),
            )
            val distAmbiguous = p.distanceCandidates.distinctBy { it.value }.size > 1 &&
                p.totalDistance == null && p.pickupDistance == null && p.deliveryDistance == null
            return OfferDraft(
                platform = p.platform,
                price = field(p.price, p.priceCandidates, 2, ambiguous = p.priceCandidates.size > 1),
                totalKm = field(p.totalDistance, p.distanceCandidates, ambiguous = distAmbiguous),
                pickupKm = field(p.pickupDistance, emptyList()),
                deliveryKm = field(p.deliveryDistance, emptyList()),
                repositionKm = if (s.defaultRepositionKm > 0)
                    DraftField(plain(s.defaultRepositionKm), FieldSource.CONFIGURADO) else DraftField(),
                timeMin = field(p.time, p.timeCandidates, ambiguous = p.timeCandidates.distinctBy { it.value }.size > 1),
                batteryPct = previous?.batteryPct?.copy(needsReview = false) ?: DraftField(),
                waitIncluded = s.waitIncludedByDefault,
                issues = p.issues,
            )
        }
    }
}
