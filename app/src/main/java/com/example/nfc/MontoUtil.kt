package com.example.nfc

/**
 * Montos en unidades mínimas (Long) con aritmética entera, formato "$44.00 USD"
 * y conversión a palabras en español para la voz. Sin dependencias de Android.
 */
object MontoUtil {

    private val SIMBOLOS = mapOf("USD" to "$", "CAD" to "$", "COP" to "$", "MXN" to "$", "EUR" to "€", "GBP" to "£")

    private fun potencia10(n: Int): Long {
        var r = 1L
        repeat(n) { r *= 10 }
        return r
    }

    /** "44" → 4400, "12.5" → 1250 (con scale 2). Devuelve null si el texto no es válido. */
    fun aUnidades(texto: String, scale: Int): Long? {
        val s = texto.trim().replace(',', '.')
        if (!Regex("""^\d{1,12}(\.\d*)?$""").matches(s)) return null
        val partes = s.split('.')
        val decimales = partes.getOrElse(1) { "" }
        if (decimales.length > scale) return null
        val fraccion = decimales.padEnd(scale, '0').ifEmpty { "0" }.toLong()
        return partes[0].toLong() * potencia10(scale) + fraccion
    }

    /** 4400, "USD", 2 → "$44.00 USD" */
    fun formatear(valor: Long, assetCode: String, scale: Int): String {
        val base = potencia10(scale)
        val abs = kotlin.math.abs(valor)
        val fraccion = if (scale > 0) "." + (abs % base).toString().padStart(scale, '0') else ""
        val signo = if (valor < 0) "-" else ""
        return "$signo${SIMBOLOS[assetCode] ?: ""}${abs / base}$fraccion $assetCode"
    }

    // ---------- Formato del prototipo ("$10.000", "US$10,00") ----------

    /** Símbolo que usa el prototipo: "US$" para dólares, "$" para pesos. */
    fun simbolo(assetCode: String): String = when (assetCode) {
        "USD" -> "US$"
        "COP", "MXN", "CAD", "ARS", "CLP" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        else -> ""
    }

    /** Número con separadores: en español "10.000,50"; en inglés "10,000.50". */
    fun numero(valor: Long, scale: Int, ingles: Boolean): String {
        val base = potencia10(scale)
        val abs = kotlin.math.abs(valor)
        val miles = if (ingles) ',' else '.'
        val decimal = if (ingles) '.' else ','
        val entero = (abs / base).toString().reversed().chunked(3).joinToString(miles.toString()).reversed()
        val fraccion = if (scale > 0) decimal + (abs % base).toString().padStart(scale, '0') else ""
        return (if (valor < 0) "-" else "") + entero + fraccion
    }

    /** "$10.000" o "US$10,00"; si la moneda no tiene símbolo, "10,00 XYZ". */
    fun corto(valor: Long, assetCode: String, scale: Int, ingles: Boolean): String {
        val s = simbolo(assetCode)
        return if (s.isEmpty()) "${numero(valor, scale, ingles)} $assetCode" else s + numero(valor, scale, ingles)
    }

    /** "$20.000 COP" (como en las listas del prototipo). */
    fun conCodigo(valor: Long, assetCode: String, scale: Int, ingles: Boolean): String {
        val s = simbolo(assetCode)
        return if (s.isEmpty()) corto(valor, assetCode, scale, ingles) else "${corto(valor, assetCode, scale, ingles)} $assetCode"
    }

    /** Etiqueta corta para ejes: 250000 → "250k", 8000000 → "8M" (sin decimales de la moneda). */
    fun compacto(valor: Long, scale: Int, ingles: Boolean): String {
        val unidades = valor / potencia10(scale)
        val decimal = if (ingles) "." else ","
        fun uno(n: Long, d: Long, sufijo: String): String {
            val entero = n / d
            val decima = (n % d) * 10 / d
            return if (decima == 0L || entero >= 10) "$entero$sufijo" else "$entero$decimal$decima$sufijo"
        }
        return when {
            unidades >= 1_000_000 -> uno(unidades, 1_000_000, "M")
            unidades >= 1_000 -> uno(unidades, 1_000, "k")
            else -> unidades.toString()
        }
    }

    // ---------- Palabras ----------

    // Formas apocopadas ("un", "veintiún"): siempre van antes de un sustantivo (dólares, mil, millones)
    private val UNIDADES = arrayOf(
        "cero", "un", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
        "diez", "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve",
        "veinte", "veintiún", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis",
        "veintisiete", "veintiocho", "veintinueve"
    )
    private val DECENAS = arrayOf("", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa")
    private val CENTENAS = arrayOf(
        "", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos",
        "seiscientos", "setecientos", "ochocientos", "novecientos"
    )

    private fun menorQueCien(n: Int): String =
        if (n < 30) UNIDADES[n]
        else DECENAS[n / 10] + if (n % 10 > 0) " y " + UNIDADES[n % 10] else ""

    private fun menorQueMil(n: Int): String {
        if (n == 100) return "cien"
        val partes = mutableListOf<String>()
        if (n >= 100) partes += CENTENAS[n / 100]
        if (n % 100 > 0) partes += menorQueCien(n % 100)
        return partes.joinToString(" ")
    }

    /** Número entero en palabras (forma apocopada): 21 → "veintiún", 21000 → "veintiún mil". */
    fun numeroEnPalabras(n: Long): String {
        if (n == 0L) return "cero"
        val partes = mutableListOf<String>()
        val millones = n / 1_000_000
        val miles = ((n % 1_000_000) / 1000).toInt()
        val unidades = (n % 1000).toInt()
        if (millones > 0) partes += if (millones == 1L) "un millón" else numeroEnPalabras(millones) + " millones"
        if (miles > 0) partes += if (miles == 1) "mil" else menorQueMil(miles) + " mil"
        if (unidades > 0) partes += menorQueMil(unidades)
        return partes.joinToString(" ")
    }

    /** 1250, 2, "USD" → "doce dólares con cincuenta centavos" */
    fun enPalabras(valor: Long, scale: Int, assetCode: String = "USD"): String {
        val base = potencia10(scale)
        val enteros = valor / base
        val fraccion = valor % base
        // Centavos = las dos primeras cifras decimales
        val centavos = if (scale >= 2) fraccion / potencia10(scale - 2) else fraccion * potencia10(2 - scale)
        val (singular, plural) = if (assetCode == "USD") "dólar" to "dólares" else assetCode to assetCode

        val textoEnteros = when {
            enteros == 1L -> "un $singular"
            enteros >= 1_000_000 && enteros % 1_000_000 == 0L -> "${numeroEnPalabras(enteros)} de $plural"
            else -> "${numeroEnPalabras(enteros)} $plural"
        }
        val textoCentavos = if (centavos == 1L) "un centavo" else "${numeroEnPalabras(centavos)} centavos"
        return when {
            centavos == 0L -> textoEnteros
            enteros == 0L -> textoCentavos
            else -> "$textoEnteros con $textoCentavos"
        }
    }
}
