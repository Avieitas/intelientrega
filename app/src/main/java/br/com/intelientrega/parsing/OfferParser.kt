package br.com.intelientrega.parsing

import java.text.Normalizer

enum class Platform(val label: String) { GENERICO("Genérico"), NOVENTA_NOVE("99"), IFOOD("iFood") }

/** Papel provável de um número na oferta, deduzido por palavras próximas. */
enum class Role { TOTAL, PICKUP, DELIVERY, EXTRA, UNKNOWN }

data class Candidate(
    val type: TokenType,
    val value: Double,
    val raw: String,
    val lineText: String,
    val role: Role,
    val isRange: Boolean = false,
)

/** Um valor escolhido pelo parser. [needsReview] = escolha incerta, o usuário deve conferir. */
data class Pick(val candidate: Candidate, val needsReview: Boolean)

data class ParsedOffer(
    val platform: Platform,
    val textFound: Boolean,
    val priceCandidates: List<Candidate>,
    val distanceCandidates: List<Candidate>,
    val timeCandidates: List<Candidate>,
    val price: Pick?,
    val totalDistance: Pick?,
    val pickupDistance: Pick?,
    val deliveryDistance: Pick?,
    val time: Pick?,
    val issues: List<String>,
)

/** Contrato substituível: cada plataforma pode ter sua própria interpretação do mesmo texto OCR. */
interface OfferParser {
    val platform: Platform
    fun parse(ocr: OcrText): ParsedOffer
}

object OfferParsers {
    private val parsers: Map<Platform, OfferParser> = mapOf(
        Platform.GENERICO to GenericOfferParser(),
        Platform.NOVENTA_NOVE to NoventaNoveOfferParser(),
        Platform.IFOOD to IfoodOfferParser(),
    )

    fun forPlatform(p: Platform): OfferParser = parsers.getValue(p)
}

internal fun normalize(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
