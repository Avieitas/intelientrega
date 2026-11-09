package br.com.intelientrega.account

/**
 * Mesma normalização do servidor (sem espaços, maiúsculas). Serve só para a interface saber
 * se o texto digitado mudou; a validação de verdade é sempre feita no servidor.
 */
fun normalizeCouponInput(raw: String): String? = raw.replace(Regex("\\s"), "").uppercase().ifEmpty { null }
