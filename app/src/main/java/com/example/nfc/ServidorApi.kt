package com.example.nfc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Error devuelto por el servidor ({ error: { codigo, mensaje, ... } }) o falta de conexión. */
class ErrorServidor(val codigo: String, mensaje: String, val datos: JSONObject? = null) : Exception(mensaje)

data class Cuenta(
    val id: String,
    val nombre: String,
    val walletAddress: String,
    val negocio: String?,
    val negocioTexto: String,
    val assetCode: String?,
    val assetScale: Int?,
    val tienePermiso: Boolean,
    val nueva: Boolean = false
)

data class ConfigCobro(val negocio: String, val assetCode: String, val assetScale: Int)

data class Cobro(
    val id: String,
    val estado: String,
    val montoValor: Long,
    val assetCode: String,
    val assetScale: Int,
    val montoTexto: String,
    val debitTexto: String?,
    val cliente: String?,
    val tienda: String,
    val segundosRestantes: Int,
    val error: JSONObject?,
    val medioTipo: String? = null
)

data class Permiso(
    val estado: String, // activo | pendiente | sin_permiso | no_valido
    val topeTexto: String?,
    val gastadoTexto: String?,
    val restanteTexto: String?,
    val renuevaEl: String?,
    val fuente: String?,
    val alcance: String?,
    val topeDolares: String?,
    /** Restante del intervalo en unidades mínimas, con la moneda del tope (null sin permiso activo). */
    val restanteValor: Long? = null,
    val assetCode: String? = null,
    val assetScale: Int? = null
)

data class Resumen(val medioNombre: String, val medioBloqueado: Boolean, val permiso: Permiso)

data class Medio(
    val uid: String,
    val tipo: String, // tarjeta | celular
    val nombre: String,
    val bloqueada: Boolean,
    val ultimoUso: String?,
    val esEsteCelular: Boolean
)

data class DuenoTarjeta(val registrada: Boolean, val clienteId: String?, val clienteNombre: String?, val nombre: String?)

data class Pendiente(val cobro: Cobro?, val intento: JSONObject?)

data class Movimiento(
    val id: String,
    val tipo: String, // cobro | pago
    val fecha: String?,
    val montoTexto: String,
    val estado: String,
    val estadoTexto: String,
    val contraparte: String?,
    val medio: String?,
    val montoValor: Long? = null,
    val assetCode: String? = null,
    val assetScale: Int? = null
)

data class Historial(val cobradoHoy: String, val pagadoHoy: String, val movimientos: List<Movimiento>)

/** Llamadas HTTP al servidor local (por USB con adb reverse tcp:3000 tcp:3000). */
object ServidorApi {
    private const val BASE = "http://localhost:3000"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun JSONObject.texto(clave: String): String? = optString(clave).takeIf { it.isNotEmpty() && it != "null" }

    // ---------- Cuentas ----------

    private fun cuentaDesde(j: JSONObject) = Cuenta(
        id = j.getString("id"),
        nombre = j.getString("nombre"),
        walletAddress = j.getString("walletAddress"),
        negocio = j.texto("negocio"),
        negocioTexto = j.texto("negocioTexto") ?: j.getString("nombre"),
        assetCode = j.texto("assetCode"),
        assetScale = if (j.isNull("assetScale")) null else j.optInt("assetScale"),
        tienePermiso = j.optBoolean("tienePermiso"),
        nueva = j.optBoolean("nueva")
    )

    suspend fun crearCuenta(nombre: String, wallet: String, negocio: String?): Cuenta {
        val cuerpo = JSONObject().put("nombre", nombre).put("walletAddress", wallet)
        if (negocio != null) cuerpo.put("negocio", negocio)
        return cuentaDesde(JSONObject(pedir("POST", "/api/cuentas", cuerpo)))
    }

    suspend fun cuenta(id: String): Cuenta = cuentaDesde(JSONObject(pedir("GET", "/api/cuentas/${enc(id)}", null)))

    suspend fun editarCuenta(id: String, nombre: String?, negocio: String?): Cuenta {
        val cuerpo = JSONObject()
        if (nombre != null) cuerpo.put("nombre", nombre)
        if (negocio != null) cuerpo.put("negocio", negocio)
        return cuentaDesde(JSONObject(pedir("PATCH", "/api/cuentas/${enc(id)}", cuerpo)))
    }

    suspend fun cuentas(): List<Cuenta> {
        val a = JSONArray(pedir("GET", "/api/clientes", null))
        return (0 until a.length()).map { cuentaDesde(a.getJSONObject(it)) }
    }

    suspend fun historial(cuentaId: String): Historial {
        val j = JSONObject(pedir("GET", "/api/cuentas/${enc(cuentaId)}/historial", null))
        val a = j.getJSONArray("movimientos")
        val lista = (0 until a.length()).map {
            val m = a.getJSONObject(it)
            Movimiento(
                id = m.getString("id"), tipo = m.getString("tipo"), fecha = m.texto("fecha"),
                montoTexto = m.getString("montoTexto"), estado = m.getString("estado"),
                estadoTexto = m.getString("estadoTexto"), contraparte = m.texto("contraparte"), medio = m.texto("medio"),
                montoValor = m.optJSONObject("monto")?.texto("value")?.toLongOrNull(),
                assetCode = m.optJSONObject("monto")?.texto("assetCode"),
                assetScale = m.optJSONObject("monto")?.takeIf { it.has("assetScale") }?.optInt("assetScale")
            )
        }
        val hoy = j.getJSONObject("hoy")
        return Historial(hoy.getString("cobradoTexto"), hoy.getString("pagadoTexto"), lista)
    }

    // ---------- Cobrar ----------

    suspend fun configCobro(cuentaId: String): ConfigCobro {
        val j = JSONObject(pedir("GET", "/api/config?cuenta=${enc(cuentaId)}", null))
        return ConfigCobro(j.getString("tienda"), j.getString("assetCode"), j.getInt("assetScale"))
    }

    suspend fun registrarTarjeta(uid: String, cuentaId: String) {
        pedir("POST", "/api/tarjetas", JSONObject().put("uid", uid).put("cuentaId", cuentaId))
    }

    suspend fun duenoTarjeta(uid: String, wallet: String?): DuenoTarjeta {
        val consulta = if (wallet != null) "?wallet=${enc(wallet)}" else ""
        val j = JSONObject(pedir("GET", "/api/tarjetas/${enc(uid)}$consulta", null))
        val c = j.optJSONObject("cliente")
        return DuenoTarjeta(j.optBoolean("registrada"), c?.optString("id"), c?.optString("nombre"), j.texto("nombre"))
    }

    private fun cobroDesde(texto: String) = cobroDesde(JSONObject(texto))

    private fun cobroDesde(j: JSONObject): Cobro {
        val m = j.getJSONObject("monto")
        return Cobro(
            id = j.getString("id"),
            estado = j.getString("estado"),
            montoValor = m.getString("value").toLong(),
            assetCode = m.getString("assetCode"),
            assetScale = m.getInt("assetScale"),
            montoTexto = j.getString("montoTexto"),
            debitTexto = j.texto("debitTexto"),
            cliente = j.texto("cliente"),
            tienda = j.getString("tienda"),
            segundosRestantes = j.optInt("segundosRestantes", 0),
            error = j.optJSONObject("error"),
            medioTipo = j.texto("medioTipo")
        )
    }

    suspend fun crearCobro(monto: String, cuentaId: String): Cobro =
        cobroDesde(pedir("POST", "/api/cobros", JSONObject().put("monto", monto).put("cuentaId", cuentaId)))

    suspend fun tarjeta(id: String, uid: String, walletAddress: String?): Cobro {
        val cuerpo = JSONObject().put("uid", uid)
        if (walletAddress != null) cuerpo.put("walletAddress", walletAddress)
        return cobroDesde(pedir("POST", "/api/cobros/$id/tarjeta", cuerpo))
    }

    suspend fun confirmar(id: String, uid: String): Cobro =
        cobroDesde(pedir("POST", "/api/cobros/$id/confirmar", JSONObject().put("uid", uid)))

    suspend fun estado(id: String): Cobro = cobroDesde(pedir("GET", "/api/cobros/$id", null))

    suspend fun cancelar(id: String) {
        pedir("POST", "/api/cobros/${enc(id)}/cancelar", JSONObject())
    }

    // ---------- Pagar con el celular ----------

    suspend fun vincular(dispositivoId: String, cuentaId: String, nombreDispositivo: String) {
        pedir("POST", "/api/billetera/vincular", JSONObject()
            .put("dispositivoId", dispositivoId).put("cuentaId", cuentaId).put("nombreDispositivo", nombreDispositivo))
    }

    suspend fun desvincular(dispositivoId: String) {
        pedir("DELETE", "/api/billetera/dispositivos/${enc(dispositivoId)}", null)
    }

    suspend fun resumen(dispositivoId: String): Resumen {
        val j = JSONObject(pedir("GET", "/api/billetera/resumen?dispositivo=${enc(dispositivoId)}", null))
        val m = j.getJSONObject("medio")
        val p = j.getJSONObject("permiso")
        val tope = p.optJSONObject("tope")
        return Resumen(
            medioNombre = m.getString("nombre"),
            medioBloqueado = m.optBoolean("bloqueada"),
            permiso = Permiso(
                estado = p.getString("estado"),
                topeTexto = p.texto("topeTexto"),
                gastadoTexto = p.texto("gastadoTexto"),
                restanteTexto = p.texto("restanteTexto"),
                renuevaEl = p.texto("renuevaEl"),
                fuente = p.texto("fuente"),
                alcance = p.texto("alcance"),
                topeDolares = p.texto("topeDolares"),
                restanteValor = p.texto("restante")?.toLongOrNull(),
                assetCode = tope?.texto("assetCode") ?: j.texto("assetCode"),
                assetScale = tope?.takeIf { it.has("assetScale") }?.optInt("assetScale") ?: j.optInt("assetScale", 2)
            )
        )
    }

    private fun medioDesde(m: JSONObject) = Medio(
        uid = m.getString("uid"), tipo = m.getString("tipo"), nombre = m.getString("nombre"),
        bloqueada = m.optBoolean("bloqueada"), ultimoUso = m.texto("ultimoUso"), esEsteCelular = m.optBoolean("esEsteCelular")
    )

    suspend fun medios(dispositivoId: String): List<Medio> {
        val a = JSONObject(pedir("GET", "/api/billetera/medios?dispositivo=${enc(dispositivoId)}", null)).getJSONArray("medios")
        return (0 until a.length()).map { medioDesde(a.getJSONObject(it)) }
    }

    suspend fun agregarMedio(dispositivoId: String, uid: String, wallet: String?, nombre: String): Medio {
        val cuerpo = JSONObject().put("uid", uid).put("nombre", nombre)
        if (wallet != null) cuerpo.put("walletAddress", wallet)
        return medioDesde(JSONObject(pedir("POST", "/api/billetera/medios?dispositivo=${enc(dispositivoId)}", cuerpo)).getJSONObject("medio"))
    }

    suspend fun editarMedio(dispositivoId: String, uid: String, nombre: String? = null, bloqueada: Boolean? = null): Medio {
        val cuerpo = JSONObject()
        if (nombre != null) cuerpo.put("nombre", nombre)
        if (bloqueada != null) cuerpo.put("bloqueada", bloqueada)
        return medioDesde(JSONObject(pedir("PATCH", "/api/billetera/medios/${enc(uid)}?dispositivo=${enc(dispositivoId)}", cuerpo)).getJSONObject("medio"))
    }

    suspend fun eliminarMedio(dispositivoId: String, uid: String) {
        pedir("DELETE", "/api/billetera/medios/${enc(uid)}?dispositivo=${enc(dispositivoId)}", null)
    }

    suspend fun cobroPendiente(dispositivoId: String): Pendiente {
        val j = JSONObject(pedir("GET", "/api/billetera/cobro-pendiente?dispositivo=${enc(dispositivoId)}", null))
        return Pendiente(j.optJSONObject("cobro")?.let { cobroDesde(it) }, j.optJSONObject("intento"))
    }

    suspend fun solicitarPermiso(dispositivoId: String, tope: String): String =
        JSONObject(pedir("POST", "/api/billetera/permiso/solicitar?dispositivo=${enc(dispositivoId)}", JSONObject().put("tope", tope)))
            .getString("enlace")

    // ---------- HTTP ----------

    private suspend fun pedir(metodo: String, ruta: String, cuerpo: JSONObject?): String = withContext(Dispatchers.IO) {
        val con = URL(BASE + ruta).openConnection() as HttpURLConnection
        try {
            // HttpURLConnection no admite PATCH: se envía POST con X-HTTP-Method-Override
            if (metodo == "PATCH") {
                con.requestMethod = "POST"
                con.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                con.requestMethod = metodo
            }
            con.connectTimeout = 5_000
            con.readTimeout = 30_000 // crear el quote puede tardar unos segundos
            if (cuerpo != null) {
                con.doOutput = true
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                con.outputStream.use { it.write(cuerpo.toString().toByteArray(Charsets.UTF_8)) }
            }
            val codigo = con.responseCode
            val flujo = if (codigo >= 400) con.errorStream else con.inputStream
            val texto = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (codigo >= 400) {
                val err = runCatching { JSONObject(texto).getJSONObject("error") }.getOrNull()
                throw ErrorServidor(
                    err?.optString("codigo")?.ifEmpty { null } ?: "HTTP_$codigo",
                    err?.optString("mensaje")?.ifEmpty { null } ?: "Error del servidor ($codigo)",
                    err
                )
            }
            texto
        } catch (e: IOException) {
            throw ErrorServidor("SIN_CONEXION", "Sin conexión con el servidor. Revise que esté corriendo y el adb reverse de este celular.")
        } finally {
            con.disconnect()
        }
    }
}
