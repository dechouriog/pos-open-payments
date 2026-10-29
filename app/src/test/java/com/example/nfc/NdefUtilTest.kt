package com.example.nfc

import com.example.nfc.NdefUtil.aHex
import com.example.nfc.NdefUtil.hexABytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NdefUtilTest {

    // Mensaje esperado para https://interledger.org: D1 01 10 55 04 + "interledger.org"
    private val mensajeInterledger = hexABytes("D1011055 04") + "interledger.org".toByteArray()

    @Test
    fun mensajeUri_usaPrefijoHttps() {
        assertArrayEquals(mensajeInterledger, NdefUtil.construirMensajeNdefUri("https://interledger.org"))
    }

    @Test
    fun mensajeUri_eligePrefijoMasLargo() {
        assertEquals(2 to "example.com", NdefUtil.abreviarUri("https://www.example.com"))
        assertEquals(0 to "geo:1,2", NdefUtil.abreviarUri("geo:1,2"))
    }

    @Test
    fun mensajeUri_largoUsaRegistroLargo() {
        val url = "https://" + "a".repeat(300)
        val m = NdefUtil.construirMensajeNdefUri(url)
        assertEquals(0xC1, m[0].toInt() and 0xFF)
        assertEquals("00 00 01 2D", aHex(m.copyOfRange(2, 6))) // 1 + 300 = 301
        assertEquals(url, NdefUtil.decodificarPayloadUri(m.copyOfRange(7, m.size)))
    }

    @Test
    fun decodificarUri_resuelvePrefijo() {
        assertEquals("https://interledger.org", NdefUtil.decodificarPayloadUri(mensajeInterledger.copyOfRange(4, mensajeInterledger.size)))
    }

    @Test
    fun decodificarTexto_conIdioma() {
        val payload = byteArrayOf(0x02) + "es".toByteArray() + "Hola".toByteArray()
        assertEquals("es" to "Hola", NdefUtil.decodificarPayloadTexto(payload))
    }

    @Test
    fun cc_tieneQuinceBytesYFormatoCorrecto() {
        val cc = NdefUtil.construirCC(0x0016)
        assertEquals(15, cc.size)
        assertEquals("00 0F 20 00 3B 00 34 04 06 E1 04 00 16 00 FF", aHex(cc))
    }

    @Test
    fun cc_tamanoGrande() {
        assertEquals("12 34", aHex(NdefUtil.construirCC(0x1234).copyOfRange(11, 13)))
    }

    @Test
    fun archivoNdef_llevaNlen() {
        val archivo = NdefUtil.construirArchivoNdef(mensajeInterledger)
        assertEquals(mensajeInterledger.size + 2, archivo.size)
        assertEquals("00 14", aHex(archivo.copyOfRange(0, 2))) // 20 bytes
        assertArrayEquals(mensajeInterledger, archivo.copyOfRange(2, archivo.size))
    }

    @Test
    fun leerBinario_distintosOffsetsYLongitudes() {
        val archivo = hexABytes("00 05 01 02 03 04 05")
        assertEquals("00 05 90 00", aHex(NdefUtil.respuestaLeerBinario(archivo, 0, 2)))
        assertEquals("01 02 03 90 00", aHex(NdefUtil.respuestaLeerBinario(archivo, 2, 3)))
        // Pide más de lo que queda: devuelve solo lo disponible
        assertEquals("04 05 90 00", aHex(NdefUtil.respuestaLeerBinario(archivo, 5, 59)))
        // Justo al final: sin datos
        assertEquals("90 00", aHex(NdefUtil.respuestaLeerBinario(archivo, 7, 10)))
        // Fuera del archivo
        assertEquals("6B 00", aHex(NdefUtil.respuestaLeerBinario(archivo, 8, 1)))
    }

    @Test
    fun emulador_secuenciaCompletaDeLectura() {
        val archivo = NdefUtil.construirArchivoNdef(mensajeInterledger)
        val em = EmuladorType4(archivo)
        fun cmd(hex: String) = aHex(em.procesar(hexABytes(hex)))

        // READ BINARY sin archivo seleccionado
        assertEquals("6A 82", cmd("00 B0 00 00 0F"))
        assertEquals("90 00", cmd("00 A4 04 00 07 D2760000850101 00"))
        assertEquals("90 00", cmd("00 A4 04 00 07 D2760000850101"))
        assertEquals("6A 82", cmd("00 A4 04 00 07 D2760000850100"))
        assertEquals("90 00", cmd("00 A4 00 0C 02 E103"))
        assertEquals("00 0F 20 00 3B 00 34 04 06 E1 04 00 16 00 FF 90 00", cmd("00 B0 00 00 0F"))
        assertEquals("90 00", cmd("00 A4 00 0C 02 E104"))
        assertEquals("00 14 90 00", cmd("00 B0 00 00 02"))
        assertEquals(aHex(mensajeInterledger + NdefUtil.SW_OK), cmd("00 B0 00 02 14"))
        assertEquals("6A 82", cmd("00 A4 00 0C 02 E105"))
        assertEquals("6D 00", cmd("00 CA 00 00 00"))
    }
}
