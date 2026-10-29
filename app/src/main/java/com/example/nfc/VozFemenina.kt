package com.example.nfc

/**
 * Elige una voz femenina del motor de voz. Android no expone el género de las voces,
 * así que se reconoce por el nombre:
 * - Samsung: "...-SMTf00" (f = femenina, m = masculina).
 * - Google: "es-us-x-esf-local", "en-us-x-tpf-network" (código de 3 letras tras "-x-").
 * - Otros motores: nombres con "female"/"mujer".
 * Funciones puras (se prueban sin Android).
 */
object VozFemenina {

    data class Candidata(val nombre: String, val idioma: String, val requiereRed: Boolean, val calidad: Int)

    // Voces de Google TTS conocidas como femeninas y masculinas
    private val GOOGLE_FEMENINAS = setOf(
        "esc", "esf", "eea", "eec", "sfb",              // español (EE. UU., España)
        "tpf", "sfg", "iob", "iog", "tpc", "cxx",        // inglés EE. UU.
        "gba", "gbc", "gbg", "fis", "aua", "auc"         // inglés Reino Unido y Australia
    )
    private val GOOGLE_MASCULINAS = setOf("esd", "eed", "eef", "iol", "iom", "tpd", "rjs", "gbb", "gbd", "aub", "aud")

    /** true = femenina, false = masculina, null = no se sabe. */
    fun esFemenina(nombre: String): Boolean? {
        val n = nombre.lowercase()
        if ("female" in n || "mujer" in n || "femenin" in n) return true
        if ("male" in n || "hombre" in n || "masculin" in n) return false
        Regex("smt([fm])\\d").find(n)?.let { return it.groupValues[1] == "f" }
        Regex("-x-([a-z]{3})").find(n)?.let { m ->
            val codigo = m.groupValues[1]
            if (codigo in GOOGLE_FEMENINAS) return true
            if (codigo in GOOGLE_MASCULINAS) return false
        }
        return null
    }

    /**
     * Nombre de la mejor voz femenina: primero las que no requieren red, luego el país preferido
     * (en el orden dado, p. ej. es-CO antes que es-US) y por último la calidad. null si no hay ninguna.
     */
    fun elegir(voces: List<Candidata>, paisesPreferidos: List<String>): String? {
        val orden = paisesPreferidos.map { it.lowercase() }
        return voces.filter { esFemenina(it.nombre) == true }
            .sortedWith(
                compareBy<Candidata> { it.requiereRed }
                    .thenBy { v -> orden.indexOf(v.idioma.lowercase()).let { if (it < 0) orden.size else it } }
                    .thenByDescending { it.calidad }
            )
            .firstOrNull()?.nombre
    }
}
