package br.com.intelientrega.parsing

/**
 * Parser genérico. NÃO se baseia em layout real da 99 nem do iFood: usa só palavras comuns
 * do português ("coleta", "entrega", "total"...) perto dos números. Essas palavras são
 * hipóteses e precisam ser validadas com capturas reais.
 *
 * Política de ambiguidade:
 * - um único valor (ou vários iguais) → usado;
 * - vários valores diferentes → só escolhe se UM deles tiver papel claro, e mesmo assim pede conferência;
 * - sem papel claro → não escolhe, o usuário seleciona.
 */
open class GenericOfferParser(override val platform: Platform = Platform.GENERICO) : OfferParser {

    protected open val totalWords = listOf("total", "percurso", "trajeto completo", "rota completa")
    protected open val pickupWords = listOf("coleta", "coletar", "retirada", "retirar", "loja", "restaurante", "estabelecimento")
    protected open val deliveryWords = listOf("entrega", "entregar", "cliente", "destino")
    protected open val extraWords = listOf("gorjeta", "bonus", "adicional", "promocao", "incentivo", "dinamica")

    override fun parse(ocr: OcrText): ParsedOffer {
        val lines = ocr.lines.map { it.text }
        val tokens = BrTokenizer.tokenize(lines)
        val linesWithType = tokens.groupBy { it.type }.mapValues { e -> e.value.map { it.lineIndex }.toSet() }
        val cands = tokens.map { t ->
            Candidate(t.type, t.value, t.raw, lines[t.lineIndex], roleFor(t, lines, linesWithType[t.type].orEmpty()), t.isRange)
        }
        val prices = cands.filter { it.type == TokenType.MONEY }
        val dists = cands.filter { it.type == TokenType.DISTANCE }
        val times = cands.filter { it.type == TokenType.TIME }
        val issues = mutableListOf<String>()

        val price = pickPrice(prices, issues)
        val (total, pickup, delivery) = pickDistances(dists, issues)
        val time = pickTime(times, issues)

        if (ocr.isBlank) issues += "Nenhum texto foi lido. O app aberto pode bloquear capturas (a imagem sai preta)."
        else if (cands.isEmpty()) issues += "Nenhum valor, distância ou tempo reconhecido nesta tela."

        return ParsedOffer(platform, !ocr.isBlank, prices, dists, times, price, total, pickup, delivery, time, issues)
    }

    /** Papel pela própria linha; se não houver, pela linha anterior (rótulo acima do número). */
    protected open fun roleFor(t: Token, lines: List<String>, sameTypeLines: Set<Int>): Role {
        roleIn(normalize(lines[t.lineIndex]), t.type)?.let { return it }
        val prev = t.lineIndex - 1
        if (prev >= 0 && prev !in sameTypeLines) roleIn(normalize(lines[prev]), t.type)?.let { return it }
        return Role.UNKNOWN
    }

    private fun roleIn(text: String, type: TokenType): Role? {
        fun has(words: List<String>) = words.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }
        return when (type) {
            TokenType.MONEY -> when {
                has(extraWords) -> Role.EXTRA
                has(totalWords) -> Role.TOTAL
                else -> null
            }
            TokenType.DISTANCE, TokenType.TIME -> when {
                has(totalWords) -> Role.TOTAL
                has(pickupWords) -> Role.PICKUP
                has(deliveryWords) -> Role.DELIVERY
                else -> null
            }
        }
    }

    protected open fun pickPrice(c: List<Candidate>, issues: MutableList<String>): Pick? {
        if (c.isEmpty()) return null
        if (c.any { it.role == Role.EXTRA })
            issues += "Há valores de bônus/adicional. Confira se o valor escolhido já é o total."
        val distinct = c.distinctBy { it.value }
        if (distinct.size == 1) return Pick(distinct[0], needsReview = c.any { it.role == Role.EXTRA })
        issues += "Encontrei ${distinct.size} valores em R$. Escolha o valor da oferta."
        val totals = distinct.filter { it.role == Role.TOTAL }
        return if (totals.size == 1) Pick(totals[0], needsReview = true) else null
    }

    protected open fun pickDistances(c: List<Candidate>, issues: MutableList<String>): Triple<Pick?, Pick?, Pick?> {
        if (c.isEmpty()) return Triple(null, null, null)
        val totals = c.filter { it.role == Role.TOTAL }.distinctBy { it.value }
        val pickups = c.filter { it.role == Role.PICKUP }.distinctBy { it.value }
        val deliveries = c.filter { it.role == Role.DELIVERY }.distinctBy { it.value }
        val distinct = c.distinctBy { it.value }

        if (totals.size == 1) {
            // A total já inclui os trechos: guardamos só a total.
            val review = distinct.size > 1 && (pickups.isEmpty() && deliveries.isEmpty())
            if (review) issues += "Várias distâncias na tela. Confira se a marcada como total está certa."
            return Triple(Pick(totals[0], review), null, null)
        }
        if (totals.isEmpty() && pickups.size == 1 && deliveries.size == 1) {
            val unknown = c.count { it.role == Role.UNKNOWN } > 0
            if (unknown) issues += "Há outras distâncias sem rótulo. Confira os trechos."
            return Triple(null, Pick(pickups[0], unknown), Pick(deliveries[0], unknown))
        }
        if (distinct.size == 1) {
            val only = distinct[0]
            return when (only.role) {
                Role.PICKUP -> Triple(null, Pick(only, false), null)
                Role.DELIVERY -> Triple(null, null, Pick(only, false))
                else -> {
                    issues += "Não sei se ${only.raw} é a distância total ou só um trecho. Confira."
                    Triple(Pick(only, true), null, null)
                }
            }
        }
        issues += "Encontrei ${distinct.size} distâncias diferentes. Informe a total ou os trechos."
        return Triple(null, null, null)
    }

    protected open fun pickTime(c: List<Candidate>, issues: MutableList<String>): Pick? {
        if (c.isEmpty()) return null
        val distinct = c.distinctBy { it.value }
        val chosen = when {
            distinct.size == 1 -> Pick(distinct[0], needsReview = false)
            else -> {
                issues += "Encontrei ${distinct.size} tempos diferentes. Escolha o tempo total da oferta."
                distinct.filter { it.role == Role.TOTAL }.singleOrNull()?.let { Pick(it, true) }
            }
        } ?: return null
        if (chosen.candidate.isRange) {
            issues += "O tempo ${chosen.candidate.raw} é uma faixa. Usei o maior valor; confira."
            return chosen.copy(needsReview = true)
        }
        return chosen
    }
}

/**
 * Ponto de extensão para a 99. Hoje é IDÊNTICO ao genérico: nenhum layout real foi fornecido,
 * e não presumimos rótulos nem posições. Ajuste as listas de palavras e as regras de escolha
 * depois de coletar capturas reais (com consentimento) e escrever testes com elas.
 */
class NoventaNoveOfferParser : GenericOfferParser(Platform.NOVENTA_NOVE)

/** Ponto de extensão para o iFood. Mesma observação do parser da 99. */
class IfoodOfferParser : GenericOfferParser(Platform.IFOOD)
