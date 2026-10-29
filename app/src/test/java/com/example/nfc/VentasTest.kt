package com.example.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class VentasTest {
    private val zona = TimeZone.getTimeZone("America/Bogota")

    private fun ms(anio: Int, mes: Int, dia: Int, hora: Int = 12) =
        Calendar.getInstance(zona).apply { clear(); set(anio, mes - 1, dia, hora, 0) }.timeInMillis

    private fun mov(tipo: String, estado: String, iso: String, valor: Long, code: String = "USD") =
        Movimiento("x", tipo, iso, "", estado, "", null, null, valor, code, 2)

    @Test
    fun soloCobrosPagadosConFechaYMonto() {
        val v = Ventas.desdeHistorial(listOf(
            mov("cobro", "pagado", "2026-10-16T15:00:00.000Z", 1000),
            mov("cobro", "error", "2026-10-16T15:00:00.000Z", 500),
            mov("pago", "pagado", "2026-10-16T15:00:00.000Z", 700)
        ))
        assertEquals(1, v.size)
        assertEquals(1000L, v[0].valor)
    }

    @Test
    fun sieteDiasTerminanHoyYSumanTotales() {
        val ahora = ms(2026, 10, 16, 18)
        val ventas = listOf(
            Venta(ms(2026, 10, 16, 9), 1000, "USD"),
            Venta(ms(2026, 10, 16, 10), 500, "USD"),
            Venta(ms(2026, 10, 10, 9), 200, "USD"),
            Venta(ms(2026, 10, 9, 9), 999, "USD"),   // fuera del período
            Venta(ms(2026, 10, 16, 9), 777, "COP")   // otra moneda: no se mezcla
        )
        val s = Ventas.serie(ventas, "USD", Periodo.DIAS7, ahora, zona)
        assertEquals(7, s.barras.size)
        assertEquals(1500L, s.barras.last().total)
        assertEquals(2, s.barras.last().cantidad)
        assertEquals(200L, s.barras.first().total)
        assertEquals(1700L, s.total)
        assertEquals(3, s.cantidad)
    }

    @Test
    fun doceMesesAgrupaPorMes() {
        val ahora = ms(2026, 10, 16)
        val ventas = listOf(Venta(ms(2026, 10, 1), 100, "USD"), Venta(ms(2025, 11, 30), 50, "USD"), Venta(ms(2025, 10, 31), 9, "USD"))
        val s = Ventas.serie(ventas, "USD", Periodo.MESES12, ahora, zona)
        assertEquals(12, s.barras.size)
        assertEquals(50L, s.barras.first().total)
        assertEquals(100L, s.barras.last().total)
        assertEquals(150L, s.total)
    }

    @Test
    fun topeDelEjeEsRedondo() {
        assertEquals(0L, Ventas.topeEje(0))
        assertEquals(200_000L, Ventas.topeEje(180_000))
        assertEquals(250_000L, Ventas.topeEje(210_000))
        assertEquals(5_000L, Ventas.topeEje(4_400))
        assertEquals(1_000L, Ventas.topeEje(1_000))
    }

    @Test
    fun fechaIsoInvalidaEsNull() {
        assertNull(Ventas.fechaIso("ayer"))
    }
}
