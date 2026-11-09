package br.com.intelientrega.parsing

/** Texto bruto do OCR, já sem imagem. Nada aqui depende de Android ou ML Kit. */
data class OcrLine(val text: String, val top: Int = 0, val left: Int = 0)

data class OcrText(val lines: List<OcrLine>) {
    val isBlank: Boolean get() = lines.all { it.text.isBlank() }

    companion object {
        fun fromPlainText(text: String) = OcrText(text.lines().filter { it.isNotBlank() }.map { OcrLine(it.trim()) })
    }
}

enum class TokenType { MONEY, DISTANCE, TIME }

data class Token(
    val type: TokenType,
    /** R$ para MONEY, km para DISTANCE, minutos para TIME. */
    val value: Double,
    val raw: String,
    val lineIndex: Int,
    /** Ex.: "20-30 min". [value] guarda o limite superior (cenário mais conservador). */
    val isRange: Boolean = false,
)

object BrNumbers {
    /**
     * Interpreta números brasileiros e erros comuns de OCR:
     * "15,90", "1.234,50", "15.90" (vírgula lida como ponto), "1.234", "15".
     */
    fun parse(raw: String): Double? {
        val s = raw.trim().filter { it.isDigit() || it == ',' || it == '.' }
        if (s.isEmpty() || !s.first().isDigit() || !s.last().isDigit()) return null
        val normalized = when {
            s.count { it == ',' } > 1 -> return null
            s.contains(',') -> {
                val intPart = s.substringBefore(',')
                if (intPart.contains('.') && !intPart.split('.').drop(1).all { it.length == 3 }) return null
                intPart.replace(".", "") + "." + s.substringAfter(',')
            }
            s.contains('.') -> {
                val parts = s.split('.')
                when {
                    parts.size == 2 && parts[1].length in 1..2 -> s
                    parts.drop(1).all { it.length == 3 } -> s.replace(".", "")
                    else -> return null
                }
            }
            else -> s
        }
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** Distância: ponto e vírgula são sempre decimais ("7.2 km", "7,2 km"). */
    fun parseDecimal(raw: String): Double? {
        val s = raw.trim().replace(',', '.')
        if (s.count { it == '.' } > 1) return null
        return s.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}

/**
 * Extrai valores monetários, distâncias e tempos em formatos brasileiros.
 * Não atribui significado (coleta, total etc.): isso é papel dos parsers.
 */
object BrTokenizer {
    private val I = setOf(RegexOption.IGNORE_CASE)

    // OCR costuma ler "R$" como "RS" ou "R S".
    private val MONEY = Regex("""R\s?[${'$'}S]\s?(\d[\d.,]*\d|\d)""")
    private val DIST = Regex("""(\d+(?:[.,]\d+)?)\s?(km|m)(?![\p{L}/²])""", I)
    private val TIME_RANGE = Regex("""(\d{1,3})\s?(?:-|–|a|até)\s?(\d{1,3})\s?min(?:utos?)?(?!\p{L})""", I)
    private val TIME_HM = Regex("""(\d{1,2})\s?h\s?(\d{1,2})(?:\s?min(?:utos?)?)?(?!\p{L})""", I)
    private val TIME_MIN = Regex("""(\d{1,3})\s?min(?:utos?|s)?(?!\p{L})""", I)
    private val TIME_H = Regex("""(\d{1,2})\s?(?:h|horas?)(?!\p{L})""", I)

    fun tokenize(lines: List<String>): List<Token> = lines.flatMapIndexed { idx, line -> tokenizeLine(line, idx) }

    fun tokenizeLine(line: String, idx: Int): List<Token> {
        val taken = BooleanArray(line.length)
        val out = mutableListOf<Pair<Int, Token>>()

        fun claim(r: IntRange): Boolean {
            if (r.any { taken[it] }) return false
            r.forEach { taken[it] = true }
            return true
        }

        MONEY.findAll(line).forEach { m ->
            val v = BrNumbers.parse(m.groupValues[1]) ?: return@forEach
            if (claim(m.range)) out += m.range.first to Token(TokenType.MONEY, v, m.value, idx)
        }
        TIME_RANGE.findAll(line).forEach { m ->
            val a = m.groupValues[1].toDouble()
            val b = m.groupValues[2].toDouble()
            if (b >= a && claim(m.range)) out += m.range.first to Token(TokenType.TIME, b, m.value, idx, isRange = true)
        }
        TIME_HM.findAll(line).forEach { m ->
            val mins = m.groupValues[2].toInt()
            if (mins < 60 && claim(m.range))
                out += m.range.first to Token(TokenType.TIME, m.groupValues[1].toDouble() * 60 + mins, m.value, idx)
        }
        TIME_MIN.findAll(line).forEach { m ->
            if (claim(m.range)) out += m.range.first to Token(TokenType.TIME, m.groupValues[1].toDouble(), m.value, idx)
        }
        TIME_H.findAll(line).forEach { m ->
            if (claim(m.range)) out += m.range.first to Token(TokenType.TIME, m.groupValues[1].toDouble() * 60, m.value, idx)
        }
        DIST.findAll(line).forEach { m ->
            val n = BrNumbers.parseDecimal(m.groupValues[1]) ?: return@forEach
            val kmValue = if (m.groupValues[2].equals("km", ignoreCase = true)) n else n / 1000.0
            if (claim(m.range)) out += m.range.first to Token(TokenType.DISTANCE, kmValue, m.value, idx)
        }
        return out.sortedBy { it.first }.map { it.second }
    }
}
