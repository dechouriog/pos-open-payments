package com.example.nfc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/**
 * Historial de la cuenta (pagos hechos y cobros recibidos). Lo usan el inicio de Pagar
 * (últimos pagos) y el de Mi negocio (gráfica y ventas recientes), siempre con los mismos datos.
 */
class PestanaHistorial(private val e: Entorno) {

    var datos by mutableStateOf<Historial?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun cargar() {
        val id = e.cuentaId() ?: return
        e.scope.launch {
            try {
                datos = ServidorApi.historial(id)
                error = null
                e.sinConexion(false)
            } catch (ex: ErrorServidor) {
                // Sin conexión ya lo avisa la barra general; aquí solo los demás errores
                error = if (ex.codigo == "SIN_CONEXION") null else e.tx.mensajeError(ex.codigo, ex.message)
                if (ex.codigo == "SIN_CONEXION") e.sinConexion(true)
            }
        }
    }

    /** Al cambiar de cuenta: no mostrar el historial de la anterior. */
    fun limpiar() {
        datos = null
        error = null
    }
}
