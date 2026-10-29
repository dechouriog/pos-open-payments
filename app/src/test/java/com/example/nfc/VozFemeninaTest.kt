package com.example.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VozFemeninaTest {

    @Test
    fun reconoceNombresDeMotores() {
        assertEquals(true, VozFemenina.esFemenina("es-us-x-esf-local"))
        assertEquals(false, VozFemenina.esFemenina("es-us-x-esd-local"))
        assertEquals(true, VozFemenina.esFemenina("es-MX-SMTf00"))
        assertEquals(false, VozFemenina.esFemenina("es-MX-SMTm00"))
        assertEquals(true, VozFemenina.esFemenina("Spanish Female"))
        assertEquals(false, VozFemenina.esFemenina("English male"))
        assertNull(VozFemenina.esFemenina("es-es-language"))
    }

    @Test
    fun prefiereLocalYElPaisPedido() {
        val voces = listOf(
            VozFemenina.Candidata("es-us-x-esd-local", "es-US", false, 400),
            VozFemenina.Candidata("es-us-x-esf-network", "es-US", true, 500),
            VozFemenina.Candidata("es-es-x-eea-local", "es-ES", false, 400),
            VozFemenina.Candidata("es-us-x-esc-local", "es-US", false, 300)
        )
        assertEquals("es-us-x-esc-local", VozFemenina.elegir(voces, listOf("es-CO", "es-US", "es-ES")))
    }

    @Test
    fun sinFemeninasDevuelveNull() {
        assertNull(VozFemenina.elegir(listOf(VozFemenina.Candidata("en-us-x-iom-local", "en-US", false, 400)), listOf("en-US")))
    }
}
