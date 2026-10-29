package com.example.nfc

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

const val TAG_LOG = "PAGOS"

enum class Modo { PAGAR, COBRAR }

/**
 * Estado del celular guardado en SharedPreferences (lo leen la Activity y el servicio HCE).
 * Prototipo: no hay login; la cuenta queda guardada en este celular.
 */
object Estado {
    private const val ARCHIVO = "cuenta"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    fun cuentaId(ctx: Context): String? = prefs(ctx).getString("cuentaId", null)
    fun cuentaNombre(ctx: Context): String? = prefs(ctx).getString("cuentaNombre", null)
    fun wallet(ctx: Context): String? = prefs(ctx).getString("wallet", null)
    fun dispositivoId(ctx: Context): String? = prefs(ctx).getString("dispositivoId", null)
    fun tieneCuenta(ctx: Context): Boolean = cuentaId(ctx) != null && wallet(ctx) != null && dispositivoId(ctx) != null

    fun modo(ctx: Context): Modo = if (prefs(ctx).getString("modo", "PAGAR") == "COBRAR") Modo.COBRAR else Modo.PAGAR
    fun setModo(ctx: Context, modo: Modo) = prefs(ctx).edit().putString("modo", modo.name).apply()

    /** Idioma de la app: el elegido en Ajustes o, la primera vez, el del celular. */
    fun idioma(ctx: Context): Idioma = when (prefs(ctx).getString("idioma", null)) {
        "EN" -> Idioma.EN
        "ES" -> Idioma.ES
        else -> if (java.util.Locale.getDefault().language == "en") Idioma.EN else Idioma.ES
    }
    fun setIdioma(ctx: Context, idioma: Idioma) = prefs(ctx).edit().putString("idioma", idioma.name).apply()

    fun vozActiva(ctx: Context): Boolean = prefs(ctx).getBoolean("voz", true)
    fun setVozActiva(ctx: Context, activa: Boolean) = prefs(ctx).edit().putBoolean("voz", activa).apply()

    /** Identificador del celular como medio de pago (se conserva si ya existía). */
    fun idParaVincular(ctx: Context): String =
        dispositivoId(ctx) ?: UUID.randomUUID().toString().also { prefs(ctx).edit().putString("dispositivoId", it).apply() }

    fun guardarCuenta(ctx: Context, cuentaId: String, nombre: String, wallet: String) =
        prefs(ctx).edit().putString("cuentaId", cuentaId).putString("cuentaNombre", nombre).putString("wallet", wallet).apply()

    fun setCuentaNombre(ctx: Context, nombre: String) = prefs(ctx).edit().putString("cuentaNombre", nombre).apply()

    fun cerrarCuenta(ctx: Context) =
        prefs(ctx).edit().remove("cuentaId").remove("cuentaNombre").remove("wallet").remove("dispositivoId").apply()

    // ---- Autorización de pago (solo en memoria: si el proceso muere, hay que autorizar otra vez) ----

    /** Hasta cuándo (ms) el servicio HCE puede entregar la identidad al datáfono. */
    @Volatile
    var autorizadoHasta: Long = 0L

    fun autorizado(ahora: Long = System.currentTimeMillis()) = ahora < autorizadoHasta

    /** Momento del último toque del datáfono con el pago autorizado (para adelantar la consulta del cobro). */
    val ultimoToque = MutableStateFlow(0L)

    /** Momento del último toque SIN autorización (la pantalla pide la huella). */
    val toqueSinAutorizar = MutableStateFlow(0L)
}
