package com.example.nfc

import com.example.nfc.MontoUtil.aUnidades
import com.example.nfc.MontoUtil.enPalabras
import com.example.nfc.MontoUtil.formatear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MontoUtilTest {

    private fun palabras(dolares: String) = enPalabras(aUnidades(dolares, 2)!!, 2, "USD")

    @Test
    fun enPalabras_casosPedidos() {
        assertEquals("cero dólares", palabras("0"))
        assertEquals("un dólar", palabras("1"))
        assertEquals("un centavo", palabras("0.01"))
        assertEquals("veintiún dólares", palabras("21"))
        assertEquals("cuarenta y cuatro dólares", palabras("44"))
        assertEquals("doce dólares con cincuenta centavos", palabras("12.50"))
        assertEquals("ciento un dólares", palabras("101"))
        assertEquals("mil dólares", palabras("1000"))
        assertEquals("veintiún mil dólares", palabras("21000"))
        assertEquals("un millón de dólares", palabras("1000000"))
    }

    @Test
    fun enPalabras_otrosCasos() {
        assertEquals("cinco dólares", palabras("5"))
        assertEquals("cien dólares", palabras("100"))
        assertEquals("doscientos dólares", palabras("200"))
        assertEquals("treinta y un dólares", palabras("31"))
        assertEquals("dieciséis dólares", palabras("16"))
        assertEquals("un dólar con un centavo", palabras("1.01"))
        assertEquals("noventa y nueve centavos", palabras("0.99"))
        assertEquals("ciento un mil dólares", palabras("101000"))
        assertEquals("dos millones de dólares", palabras("2000000"))
        assertEquals("un millón doscientos mil dólares", palabras("1200000"))
        assertEquals("ciento cincuenta y cinco dólares con veinte centavos", palabras("155.20"))
    }

    @Test
    fun aUnidades_aritmeticaEntera() {
        assertEquals(4400L, aUnidades("44", 2))
        assertEquals(1250L, aUnidades("12.5", 2))
        assertEquals(1250L, aUnidades("12.50", 2))
        assertEquals(29L, aUnidades("0.29", 2))
        assertEquals(1200L, aUnidades("12.", 2))
        assertEquals(7L, aUnidades("7", 0))
        assertNull(aUnidades("1.234", 2))
        assertNull(aUnidades("", 2))
        assertNull(aUnidades("abc", 2))
        assertNull(aUnidades("-1", 2))
    }

    @Test
    fun formatear_conMonedaDeLaWallet() {
        assertEquals("$44.00 USD", formatear(4400, "USD", 2))
        assertEquals("$0.05 USD", formatear(5, "USD", 2))
        assertEquals("$12.50 USD", formatear(1250, "USD", 2))
        assertEquals("7 XYZ", formatear(7, "XYZ", 0))
    }

    @Test
    fun formatoDelPrototipo_enEspanolEIngles() {
        assertEquals("$10.000", MontoUtil.corto(10000, "COP", 0, ingles = false))
        assertEquals("$190.000 COP", MontoUtil.conCodigo(190000, "COP", 0, ingles = false))
        assertEquals("US$1.240,50", MontoUtil.corto(124050, "USD", 2, ingles = false))
        assertEquals("US$1,240.50", MontoUtil.corto(124050, "USD", 2, ingles = true))
        assertEquals("US$25.00 USD", MontoUtil.conCodigo(2500, "USD", 2, ingles = true))
        assertEquals("7 XYZ", MontoUtil.corto(7, "XYZ", 0, ingles = true))
    }

    @Test
    fun compacto_paraElEje() {
        assertEquals("250k", MontoUtil.compacto(250000, 0, false))
        assertEquals("2,5M", MontoUtil.compacto(2_500_000, 0, false))
        assertEquals("2.5M", MontoUtil.compacto(2_500_000, 0, true))
        assertEquals("50", MontoUtil.compacto(5000, 2, true))
    }
}
