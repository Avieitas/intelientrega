package br.com.intelientrega.domain

import java.math.BigDecimal
import java.util.Locale

private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

fun brl(v: Double): String = "R$ " + String.format(PT_BR, "%,.2f", v)
fun km(v: Double): String = if (v < 1.0) "${Math.round(v * 1000)} m" else String.format(PT_BR, "%.1f km", v)
fun pct(v: Double): String = "${Math.round(v)}%"
fun min(v: Double): String = "${Math.round(v)} min"

/** Número para preencher um campo editável: "7,2", "15,90", "25". */
fun plain(v: Double, decimals: Int? = null): String =
    if (decimals != null) String.format(PT_BR, "%.${decimals}f", v)
    else BigDecimal.valueOf(v).stripTrailingZeros().toPlainString().replace('.', ',')
