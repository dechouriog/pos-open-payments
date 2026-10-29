package com.example.nfc

import kotlinx.coroutines.CoroutineScope

/** Lo que la Activity ofrece a los dos modos: voz, vibración, cuenta, NFC, idioma y autenticación. */
interface Entorno {
    val scope: CoroutineScope
    /** Textos del idioma elegido (también para la voz). */
    val tx: Textos
    fun cuentaId(): String?
    fun dispositivoId(): String?
    fun hablar(texto: String)
    suspend fun hablarYEsperar(texto: String)
    fun callar()
    fun vibrar(patron: LongArray)
    fun vibrarCorto() = vibrar(longArrayOf(0, 80))
    fun vibrarLargo() = vibrar(longArrayOf(0, 700))
    fun vibrarError() = vibrar(longArrayOf(0, 120, 120, 120, 120, 120))
    fun abrirEnlace(url: String)
    fun copiar(texto: String)
    /** Pide huella o bloqueo de pantalla; llama alExito solo si se autentica. */
    fun autenticar(titulo: String, alExito: () -> Unit)
    fun sinConexion(sin: Boolean)
    /** El servidor ya no conoce este celular: se vuelve a vincular a la cuenta. */
    fun revincular()
}
