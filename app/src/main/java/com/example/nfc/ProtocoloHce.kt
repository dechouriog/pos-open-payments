package com.example.nfc

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Protocolo HCE entre un celular en modo Pagar y otro (o el mismo tipo de app) en modo Cobrar.
 * Funciones puras.
 *
 * AID propietario F0 44 41 54 41 46 4F 4E 4F = "F0" + "DATAFONO" en ASCII: el prefijo F indica
 * un AID propietario no registrado (ISO 7816-5), así que no choca con AIDs de tarjetas de pago
 * (A0…) ni con otras apps.
 *
 * Respuesta al SELECT: "v1|CEL:<dispositivoId>|<walletAddress>" en UTF-8 + 90 00.
 */
object ProtocoloHce {

    val AID: ByteArray = byteArrayOf(0xF0.toByte()) + "DATAFONO".toByteArray(Charsets.US_ASCII)

    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_NO_ENCONTRADO = byteArrayOf(0x6A, 0x82.toByte())
    val SW_INS_DESCONOCIDO = byteArrayOf(0x6D, 0x00)

    // ---------- Lado que cobra (lee el celular) ----------

    /** SELECT por AID con Le = 00. */
    fun comandoSelect(): ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, AID.size.toByte()) + AID + byteArrayOf(0x00)

    data class Identidad(val uid: String, val wallet: String?)

    /** Interpreta la respuesta del celular. null si no es un celular en modo Pagar autorizado. */
    fun interpretarRespuesta(respuesta: ByteArray?): Identidad? {
        if (respuesta == null || respuesta.size < 2) return null
        val sw1 = respuesta[respuesta.size - 2].toInt() and 0xFF
        val sw2 = respuesta[respuesta.size - 1].toInt() and 0xFF
        if (sw1 != 0x90 || sw2 != 0x00) return null
        val texto = String(respuesta, 0, respuesta.size - 2, Charsets.UTF_8)
        val partes = texto.split('|', limit = 3)
        if (partes.size < 2 || partes[0] != "v1") return null
        val uid = partes[1]
        if (!uid.startsWith("CEL:") || uid.length <= 4) return null
        val wallet = partes.getOrNull(2)?.takeIf { it.startsWith("https://") }
        return Identidad(uid, wallet)
    }

    // ---------- Lado que paga (servicio HCE) ----------

    /** ¿Es un SELECT por AID de la app? (con o sin Le) */
    fun esSelectApp(apdu: ByteArray): Boolean {
        if (apdu.size < 5 + AID.size) return false
        if (apdu[1] != 0xA4.toByte() || apdu[2] != 0x04.toByte()) return false
        val lc = apdu[4].toInt() and 0xFF
        return lc == AID.size && apdu.copyOfRange(5, 5 + lc).contentEquals(AID)
    }

    /**
     * Respuesta del servicio HCE. Solo entrega la identidad si el celular tiene cuenta,
     * está en modo Pagar y el pago está autorizado (huella o bloqueo de pantalla).
     * En cualquier otro caso responde 6A 82: el que cobra lo trata como tarjeta desconocida.
     */
    fun responder(apdu: ByteArray, dispositivoId: String?, wallet: String?, modo: Modo, autorizado: Boolean): ByteArray {
        if (!esSelectApp(apdu)) return SW_INS_DESCONOCIDO
        if (modo != Modo.PAGAR || !autorizado || dispositivoId.isNullOrBlank()) return SW_NO_ENCONTRADO
        return "v1|CEL:$dispositivoId|${wallet ?: ""}".toByteArray(Charsets.UTF_8) + SW_OK
    }

    // ---------- Otras utilidades puras ----------

    /** "2026-10-16T01:00:40.087Z" → "15/10 20:00" en la zona horaria indicada (por defecto la del celular). */
    fun fechaCorta(iso: String?, zona: TimeZone = TimeZone.getDefault()): String {
        if (iso.isNullOrBlank() || iso.length < 19) return "—"
        return try {
            val entrada = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val salida = SimpleDateFormat("dd/MM HH:mm", Locale.US).apply { timeZone = zona }
            salida.format(entrada.parse(iso.substring(0, 19))!!)
        } catch (e: Exception) {
            "—"
        }
    }
}
