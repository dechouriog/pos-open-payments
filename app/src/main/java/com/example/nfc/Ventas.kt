package com.example.nfc

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Período de la gráfica de ventas: barras por día (7 o 30) o por mes (12). */
enum class Periodo(val barras: Int, val porMes: Boolean) { DIAS7(7, false), DIAS30(30, false), MESES12(12, true) }

/** Una venta confirmada (cobro pagado) en unidades mínimas. */
data class Venta(val fechaMs: Long, val valor: Long, val assetCode: String)

/** Una barra: intervalo [inicio, fin) con su total y número de cobros. */
data class Barra(val inicio: Long, val fin: Long, val total: Long, val cantidad: Int)

data class SerieVentas(val barras: List<Barra>, val total: Long, val cantidad: Int) {
    val maximo: Long get() = barras.maxOfOrNull { it.total } ?: 0L
}

/**
 * Agrupa las ventas confirmadas para la gráfica de "Mi negocio". Funciones puras:
 * totales, barras y lista salen del mismo conjunto de datos.
 */
object Ventas {

    /** "2026-10-16T01:00:40.087Z" → milisegundos (UTC). null si no se puede leer. */
    fun fechaIso(iso: String?): Long? {
        if (iso.isNullOrBlank() || iso.length < 19) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(iso.substring(0, 19))?.time
        } catch (e: Exception) {
            null
        }
    }

    /** Ventas confirmadas (cobros pagados) del historial de la cuenta. */
    fun desdeHistorial(movimientos: List<Movimiento>): List<Venta> = movimientos.mapNotNull { m ->
        if (m.tipo != "cobro" || m.estado != "pagado") return@mapNotNull null
        val fecha = fechaIso(m.fecha) ?: return@mapNotNull null
        val valor = m.montoValor ?: return@mapNotNull null
        val code = m.assetCode ?: return@mapNotNull null
        Venta(fecha, valor, code)
    }

    /** Monedas con ventas, en orden de aparición (el prototipo muestra un botón por moneda). */
    fun monedas(ventas: List<Venta>): List<String> = ventas.map { it.assetCode }.distinct()

    /** Serie del período que termina hoy (la última barra es hoy o el mes en curso). */
    fun serie(ventas: List<Venta>, assetCode: String, periodo: Periodo, ahora: Long, zona: TimeZone = TimeZone.getDefault()): SerieVentas {
        val unidad = if (periodo.porMes) Calendar.MONTH else Calendar.DAY_OF_MONTH
        val cal = Calendar.getInstance(zona).apply {
            timeInMillis = ahora
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (periodo.porMes) set(Calendar.DAY_OF_MONTH, 1)
        }
        cal.add(unidad, 1)
        val limites = ArrayList<Long>()
        limites.add(cal.timeInMillis)
        repeat(periodo.barras) {
            cal.add(unidad, -1)
            limites.add(0, cal.timeInMillis)
        }
        val propias = ventas.filter { it.assetCode == assetCode }
        val barras = (0 until periodo.barras).map { i ->
            val inicio = limites[i]
            val fin = limites[i + 1]
            val dentro = propias.filter { it.fechaMs in inicio until fin }
            Barra(inicio, fin, dentro.sumOf { it.valor }, dentro.size)
        }
        return SerieVentas(barras, barras.sumOf { it.total }, barras.sumOf { it.cantidad })
    }

    /** Escala del eje: un número "redondo" (1, 2, 2.5 o 5 por potencia de 10) mayor o igual al máximo. */
    fun topeEje(maximo: Long): Long {
        if (maximo <= 0) return 0
        var potencia = 1L
        while (potencia * 10 <= maximo) potencia *= 10
        for (f in listOf(10L, 20L, 25L, 50L, 100L)) {
            val v = potencia * f / 10
            if (v >= maximo) return v
        }
        return potencia * 10
    }
}
