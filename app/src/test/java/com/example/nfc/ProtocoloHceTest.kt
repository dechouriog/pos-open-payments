package com.example.nfc

import com.example.nfc.NdefUtil.aHex
import com.example.nfc.NdefUtil.hexABytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ProtocoloHceTest {

    private val select = hexABytes("00 A4 04 00 09 F0 44 41 54 41 46 4F 4E 4F 00")
    private val dispositivo = "abc-123"
    private val wallet = "https://ilp.interledger-test.dev/rosa"

    private fun respuesta(texto: String, sw: String = "9000") = texto.toByteArray(Charsets.UTF_8) + hexABytes(sw)

    // ---------- Lado que cobra ----------

    @Test
    fun aidYComandoSelect() {
        assertEquals("F0 44 41 54 41 46 4F 4E 4F", aHex(ProtocoloHce.AID))
        assertEquals("00 A4 04 00 09 F0 44 41 54 41 46 4F 4E 4F 00", aHex(ProtocoloHce.comandoSelect()))
    }

    @Test
    fun interpretarRespuestaValida() {
        val id = ProtocoloHce.interpretarRespuesta(respuesta("v1|CEL:abc-123|$wallet"))
        assertEquals("CEL:abc-123", id?.uid)
        assertEquals(wallet, id?.wallet)
        assertNull(ProtocoloHce.interpretarRespuesta(respuesta("v1|CEL:abc|ftp://x"))?.wallet)
    }

    @Test
    fun interpretarRespuestasInvalidas() {
        assertNull(ProtocoloHce.interpretarRespuesta(hexABytes("6A82")))
        assertNull(ProtocoloHce.interpretarRespuesta(respuesta("v1|CEL:abc|https://x", "6A82")))
        assertNull(ProtocoloHce.interpretarRespuesta(respuesta("v2|CEL:abc|https://x")))
        assertNull(ProtocoloHce.interpretarRespuesta(respuesta("v1|10305DE4|https://x")))
        assertNull(ProtocoloHce.interpretarRespuesta(respuesta("v1|CEL:")))
        assertNull(ProtocoloHce.interpretarRespuesta(null))
    }

    // ---------- Lado que paga: cambio de modo ----------

    @Test
    fun reconoceSelectConYSinLe() {
        assertTrue(ProtocoloHce.esSelectApp(select))
        assertTrue(ProtocoloHce.esSelectApp(select.copyOfRange(0, select.size - 1)))
        assertFalse(ProtocoloHce.esSelectApp(hexABytes("00 A4 04 00 07 D2 76 00 00 85 01 01 00")))
    }

    @Test
    fun modoPagarAutorizado_respondeIdentidad() {
        val r = ProtocoloHce.responder(select, dispositivo, wallet, Modo.PAGAR, autorizado = true)
        assertEquals("v1|CEL:abc-123|$wallet", String(r, 0, r.size - 2, Charsets.UTF_8))
        assertEquals("90 00", aHex(r.copyOfRange(r.size - 2, r.size)))
        // Lo que responde el que paga lo entiende el que cobra
        assertEquals("CEL:abc-123", ProtocoloHce.interpretarRespuesta(r)?.uid)
    }

    @Test
    fun modoCobrar_noRespondeAunqueEsteAutorizado() {
        assertEquals("6A 82", aHex(ProtocoloHce.responder(select, dispositivo, wallet, Modo.COBRAR, autorizado = true)))
        assertEquals("6A 82", aHex(ProtocoloHce.responder(select, dispositivo, wallet, Modo.COBRAR, autorizado = false)))
    }

    @Test
    fun modoPagarSinAutorizar_noResponde() {
        assertEquals("6A 82", aHex(ProtocoloHce.responder(select, dispositivo, wallet, Modo.PAGAR, autorizado = false)))
    }

    @Test
    fun sinCuenta_noResponde() {
        assertEquals("6A 82", aHex(ProtocoloHce.responder(select, null, wallet, Modo.PAGAR, autorizado = true)))
        assertEquals("6A 82", aHex(ProtocoloHce.responder(select, "", wallet, Modo.PAGAR, autorizado = true)))
    }

    @Test
    fun comandoDesconocido() {
        assertEquals("6D 00", aHex(ProtocoloHce.responder(hexABytes("00 B0 00 00 0F"), dispositivo, wallet, Modo.PAGAR, autorizado = true)))
    }

    @Test
    fun fechaCortaEnZonaLocal() {
        val bogota = TimeZone.getTimeZone("America/Bogota")
        assertEquals("15/10 20:00", ProtocoloHce.fechaCorta("2026-10-16T01:00:40.087Z", bogota))
        assertEquals("—", ProtocoloHce.fechaCorta(null, bogota))
        assertEquals("—", ProtocoloHce.fechaCorta("basura", bogota))
    }
}
