package com.example.nfc

/**
 * Funciones puras (sin dependencias de Android) para NDEF y para la emulación
 * de una NFC Forum Type 4 Tag. Se prueban con pruebas unitarias en la JVM.
 */
object NdefUtil {

    /** AID de la aplicación NDEF Type 4 (versión 2.0). */
    val AID_NDEF: ByteArray = hexABytes("D2760000850101")

    const val ID_CC = 0xE103
    const val ID_NDEF = 0xE104

    // Palabras de estado (SW1 SW2)
    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_NO_ENCONTRADO = byteArrayOf(0x6A, 0x82.toByte())
    val SW_INS_DESCONOCIDO = byteArrayOf(0x6D, 0x00)
    val SW_OFFSET_INVALIDO = byteArrayOf(0x6B, 0x00)
    val SW_LONGITUD_INCORRECTA = byteArrayOf(0x67, 0x00)

    /** Tabla de abreviaturas de prefijo del registro URI (NFC Forum RTD URI). */
    val PREFIJOS_URI = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://",
        "nfs://", "ftp://", "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:",
        "pop:", "sip:", "sips:", "tftp:", "btspp://", "btl2cap://", "btgoep://",
        "tcpobex://", "irdaobex://", "file://", "urn:epc:id:", "urn:epc:tag:",
        "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:"
    )

    // ---------- Hexadecimal ----------

    fun aHex(bytes: ByteArray, separador: String = " "): String =
        bytes.joinToString(separador) { "%02X".format(it.toInt() and 0xFF) }

    fun hexABytes(hex: String): ByteArray {
        val limpio = hex.replace(" ", "")
        require(limpio.length % 2 == 0) { "Hex con longitud impar" }
        return ByteArray(limpio.length / 2) { i ->
            limpio.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    // ---------- Registro URI ----------

    /** Devuelve el código de prefijo más largo que coincide y el resto de la URI. */
    fun abreviarUri(uri: String): Pair<Int, String> {
        var mejor = 0
        for (i in 1 until PREFIJOS_URI.size) {
            if (uri.startsWith(PREFIJOS_URI[i]) && PREFIJOS_URI[i].length > PREFIJOS_URI[mejor].length) {
                mejor = i
            }
        }
        return mejor to uri.substring(PREFIJOS_URI[mejor].length)
    }

    /** Decodifica el payload de un registro URI resolviendo el prefijo abreviado. */
    fun decodificarPayloadUri(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        val codigo = payload[0].toInt() and 0xFF
        val prefijo = PREFIJOS_URI.getOrElse(codigo) { "" }
        return prefijo + String(payload, 1, payload.size - 1, Charsets.UTF_8)
    }

    /** Decodifica el payload de un registro de texto: devuelve (idioma, texto). */
    fun decodificarPayloadTexto(payload: ByteArray): Pair<String, String> {
        if (payload.isEmpty()) return "" to ""
        val estado = payload[0].toInt() and 0xFF
        val esUtf16 = (estado and 0x80) != 0
        val largoIdioma = estado and 0x3F
        val idioma = String(payload, 1, largoIdioma, Charsets.US_ASCII)
        val inicio = 1 + largoIdioma
        val texto = String(
            payload, inicio, payload.size - inicio,
            if (esUtf16) Charsets.UTF_16 else Charsets.UTF_8
        )
        return idioma to texto
    }

    /** Construye un mensaje NDEF con un solo registro URI (bien conocido, tipo "U"). */
    fun construirMensajeNdefUri(uri: String): ByteArray {
        val (codigo, resto) = abreviarUri(uri)
        val payload = byteArrayOf(codigo.toByte()) + resto.toByteArray(Charsets.UTF_8)
        val tipo = byteArrayOf(0x55) // 'U'
        return if (payload.size <= 0xFF) {
            // MB=1 ME=1 SR=1 TNF=1 (bien conocido)
            byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte()) + tipo + payload
        } else {
            // Registro largo: longitud del payload en 4 bytes
            val n = payload.size
            byteArrayOf(
                0xC1.toByte(), 0x01,
                (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()
            ) + tipo + payload
        }
    }

    // ---------- Type 4 Tag ----------

    /** Archivo NDEF: 2 bytes NLEN (longitud del mensaje) + el mensaje. */
    fun construirArchivoNdef(mensaje: ByteArray): ByteArray {
        require(mensaje.size <= 0xFFFE) { "Mensaje NDEF demasiado grande" }
        return byteArrayOf((mensaje.size ushr 8).toByte(), mensaje.size.toByte()) + mensaje
    }

    /**
     * Capability Container de 15 bytes:
     * CCLEN=000F | versión 2.0 | MLe=003B | MLc=0034 |
     * TLV de control del archivo NDEF: 04 06 E104 [tamaño máx.] 00 (lectura libre) FF (solo lectura).
     */
    fun construirCC(tamanoMaxNdef: Int): ByteArray {
        require(tamanoMaxNdef in 0..0xFFFF)
        return byteArrayOf(
            0x00, 0x0F,
            0x20,
            0x00, 0x3B,
            0x00, 0x34,
            0x04, 0x06,
            0xE1.toByte(), 0x04,
            (tamanoMaxNdef ushr 8).toByte(), tamanoMaxNdef.toByte(),
            0x00,
            0xFF.toByte()
        )
    }

    /**
     * Respuesta a READ BINARY: hasta [le] bytes del [archivo] desde [offset] + 90 00.
     * Si el offset está fuera del archivo responde 6B 00.
     */
    fun respuestaLeerBinario(archivo: ByteArray, offset: Int, le: Int): ByteArray {
        if (offset < 0 || offset > archivo.size) return SW_OFFSET_INVALIDO
        val fin = minOf(archivo.size, offset + le)
        return archivo.copyOfRange(offset, fin) + SW_OK
    }
}

/**
 * Máquina de estados de una Type 4 Tag de solo lectura con un archivo NDEF.
 * Una instancia por sesión de lectura (se descarta al desactivarse el enlace).
 */
class EmuladorType4(val archivoNdef: ByteArray) {

    val cc: ByteArray = NdefUtil.construirCC(archivoNdef.size)

    /** Archivo actualmente seleccionado (null si ninguno). */
    private var seleccionado: ByteArray? = null

    fun procesar(apdu: ByteArray): ByteArray {
        if (apdu.size < 4) return NdefUtil.SW_LONGITUD_INCORRECTA
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        return when (ins) {
            0xA4 -> seleccionar(apdu, p1)
            0xB0 -> leerBinario(apdu, p1, p2)
            else -> NdefUtil.SW_INS_DESCONOCIDO
        }
    }

    private fun seleccionar(apdu: ByteArray, p1: Int): ByteArray {
        if (apdu.size < 5) return NdefUtil.SW_LONGITUD_INCORRECTA
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return NdefUtil.SW_LONGITUD_INCORRECTA
        val datos = apdu.copyOfRange(5, 5 + lc)
        return when (p1) {
            // SELECT por nombre (AID); puede traer Le 00 al final, que se ignora
            0x04 -> if (datos.contentEquals(NdefUtil.AID_NDEF)) {
                seleccionado = null
                NdefUtil.SW_OK
            } else NdefUtil.SW_NO_ENCONTRADO
            // SELECT por identificador de archivo
            0x00 -> {
                val id = if (lc == 2) ((datos[0].toInt() and 0xFF) shl 8) or (datos[1].toInt() and 0xFF) else -1
                when (id) {
                    NdefUtil.ID_CC -> { seleccionado = cc; NdefUtil.SW_OK }
                    NdefUtil.ID_NDEF -> { seleccionado = archivoNdef; NdefUtil.SW_OK }
                    else -> NdefUtil.SW_NO_ENCONTRADO
                }
            }
            else -> NdefUtil.SW_NO_ENCONTRADO
        }
    }

    private fun leerBinario(apdu: ByteArray, p1: Int, p2: Int): ByteArray {
        val archivo = seleccionado ?: return NdefUtil.SW_NO_ENCONTRADO
        val offset = (p1 shl 8) or p2
        // Le = 00 (o ausente) significa 256 bytes en APDU corta
        val le = if (apdu.size >= 5) (apdu[4].toInt() and 0xFF).let { if (it == 0) 256 else it } else 256
        return NdefUtil.respuestaLeerBinario(archivo, offset, le)
    }
}
