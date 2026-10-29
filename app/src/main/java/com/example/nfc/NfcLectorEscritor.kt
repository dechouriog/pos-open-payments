package com.example.nfc

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Resultado de leer una etiqueta. */
data class Lectura(
    val hora: String,
    val uid: String,
    val tecnologias: List<String>,
    val formatoNdef: String,
    val tieneMensajeNdef: Boolean,
    val tamanoMaximo: Int?,
    val escribible: Boolean?,
    val registros: List<String>
)

data class ResultadoEscritura(val exito: Boolean, val mensaje: String)

/** Lo que el datáfono necesita de una tarjeta o celular: UID y, si la trae, una wallet address https. */
data class TarjetaLeida(val uid: String, val wallet: String?, val admiteNdef: Boolean, val esCelular: Boolean = false)

/** Lectura y escritura de etiquetas NFC. Se llama desde el hilo del reader mode. */
object NfcLectorEscritor {

    private fun horaActual(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    /**
     * Si el tag es un celular con la Billetera (IsoDep + responde al SELECT del AID), devuelve
     * su identidad. Si no, null y se sigue exactamente con la lectura de tarjeta.
     */
    fun celular(tag: Tag): TarjetaLeida? {
        val isoDep = IsoDep.get(tag) ?: return null
        return try {
            isoDep.use {
                it.connect()
                it.timeout = 1500
                val id = ProtocoloHce.interpretarRespuesta(it.transceive(ProtocoloHce.comandoSelect())) ?: return null
                TarjetaLeida(id.uid, id.wallet, admiteNdef = false, esCelular = true)
            }
        } catch (e: IOException) {
            null
        }
    }

    fun tarjeta(tag: Tag): TarjetaLeida {
        val ndef = Ndef.get(tag)
        // El primer registro URI que empiece por https se toma como wallet address
        val wallet = ndef?.cachedNdefMessage?.records?.firstNotNullOfOrNull { r ->
            val tipo = r.type ?: ByteArray(0)
            when {
                r.tnf == NdefRecord.TNF_WELL_KNOWN && tipo.contentEquals(NdefRecord.RTD_URI) ->
                    NdefUtil.decodificarPayloadUri(r.payload ?: ByteArray(0))
                r.tnf == NdefRecord.TNF_ABSOLUTE_URI -> String(tipo, Charsets.UTF_8)
                else -> null
            }?.takeIf { it.startsWith("https://") }
        }
        val admiteNdef = (ndef != null && ndef.isWritable) || NdefFormatable.get(tag) != null
        return TarjetaLeida(NdefUtil.aHex(tag.id, ""), wallet, admiteNdef)
    }

    fun leer(tag: Tag): Lectura {
        val tecnologias = tag.techList.map { it.substringAfterLast('.') }
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            val formato = if (NdefFormatable.get(tag) != null) "No (formateable como NDEF)" else "No"
            return Lectura(horaActual(), NdefUtil.aHex(tag.id, ":"), tecnologias, formato,
                false, null, null, emptyList())
        }
        // Sin FLAG_READER_SKIP_NDEF_CHECK el sistema ya leyó el mensaje: usamos la copia en caché
        val mensaje = ndef.cachedNdefMessage
        val registros = mensaje?.records?.mapIndexed { i, r -> "#${i + 1} ${describirRegistro(r)}" } ?: emptyList()
        return Lectura(
            hora = horaActual(),
            uid = NdefUtil.aHex(tag.id, ":"),
            tecnologias = tecnologias,
            formatoNdef = "Sí (${ndef.type})",
            tieneMensajeNdef = mensaje != null && mensaje.records.isNotEmpty(),
            tamanoMaximo = ndef.maxSize,
            escribible = ndef.isWritable,
            registros = registros
        )
    }

    private fun describirRegistro(r: NdefRecord): String {
        val tipo = r.type ?: ByteArray(0)
        val payload = r.payload ?: ByteArray(0)
        return when {
            r.tnf == NdefRecord.TNF_WELL_KNOWN && tipo.contentEquals(NdefRecord.RTD_URI) ->
                "URI: ${NdefUtil.decodificarPayloadUri(payload)}"
            r.tnf == NdefRecord.TNF_WELL_KNOWN && tipo.contentEquals(NdefRecord.RTD_TEXT) -> {
                val (idioma, texto) = NdefUtil.decodificarPayloadTexto(payload)
                "Texto [$idioma]: $texto"
            }
            r.tnf == NdefRecord.TNF_ABSOLUTE_URI ->
                "URI absoluta: ${String(tipo, Charsets.UTF_8)}"
            r.tnf == NdefRecord.TNF_MIME_MEDIA ->
                "MIME ${String(tipo, Charsets.US_ASCII)}: ${NdefUtil.aHex(payload)}"
            r.tnf == NdefRecord.TNF_EXTERNAL_TYPE ->
                "Externo ${String(tipo, Charsets.UTF_8)}: ${NdefUtil.aHex(payload)}"
            else ->
                "TNF ${r.tnf}, tipo ${NdefUtil.aHex(tipo)}: ${NdefUtil.aHex(payload)}"
        }
    }

    fun escribirUrl(tag: Tag, url: String): ResultadoEscritura {
        val mensaje = try {
            NdefMessage(NdefRecord.createUri(url))
        } catch (e: Exception) {
            return ResultadoEscritura(false, "URL no válida: ${e.message}")
        }
        val necesario = mensaje.byteArrayLength
        return try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.use {
                    it.connect()
                    if (!it.isWritable) {
                        return ResultadoEscritura(false, "La etiqueta no es escribible (está bloqueada o es de solo lectura).")
                    }
                    if (it.maxSize < necesario) {
                        return ResultadoEscritura(false, "Espacio insuficiente: se necesitan $necesario bytes y la etiqueta tiene ${it.maxSize}.")
                    }
                    it.writeNdefMessage(mensaje)
                }
                ResultadoEscritura(true, "Escrito correctamente ($necesario bytes): $url")
            } else {
                val formateable = NdefFormatable.get(tag)
                    ?: return ResultadoEscritura(false, "La etiqueta no admite NDEF (no es escribible con esta app).")
                formateable.use {
                    it.connect()
                    it.format(mensaje)
                }
                ResultadoEscritura(true, "Etiqueta formateada y escrita ($necesario bytes): $url")
            }
        } catch (e: TagLostException) {
            ResultadoEscritura(false, "Etiqueta retirada demasiado rápido. Mantenla quieta y vuelve a intentarlo.")
        } catch (e: FormatException) {
            ResultadoEscritura(false, "Error de formato NDEF: ${e.message}")
        } catch (e: IOException) {
            ResultadoEscritura(false, "Error de comunicación: ${e.message ?: e.javaClass.simpleName}. Vuelve a acercar la etiqueta.")
        }
    }
}
