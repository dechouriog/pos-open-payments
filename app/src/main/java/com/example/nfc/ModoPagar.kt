package com.example.nfc

import android.nfc.Tag
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class TipoPago { ESPERANDO, PAGANDO, HECHO, ERROR }

/** Lo que se muestra en la pantalla "Pago en curso". `clave` cambia con cada estado nuevo. */
data class PagoEnCurso(val clave: String, val tipo: TipoPago, val cobro: Cobro?, val codigoError: String?, val mensajeError: String?, val datosError: JSONObject?) {
    val cobroId get() = cobro?.id
}

private const val VENTANA_AUTORIZACION_MS = 60_000L

/** Modo Pagar (billetera): el celular se hace pasar por tarjeta con HCE, previa autorización. */
class ModoPagar(private val e: Entorno) {

    var resumen by mutableStateOf<Resumen?>(null)
        private set
    private val medios = mutableStateListOf<Medio>()
    private var enlacePermiso by mutableStateOf<String?>(null)
    private var aviso by mutableStateOf<String?>(null)
    private var ocupado by mutableStateOf(false)

    // Agregar tarjeta
    private var tarjetaLeida by mutableStateOf<TarjetaLeida?>(null)
    private var duenoLeido by mutableStateOf<DuenoTarjeta?>(null)

    // Pago en curso
    var pago by mutableStateOf<PagoEnCurso?>(null)
        private set
    private var verComprobante by mutableStateOf(false)
    private var pagadoEn = 0L
    private var claveDescartada: String? = null
    private var sondeo: Job? = null

    /** Copia observable de Estado.autorizadoHasta para la pantalla. */
    private var autorizadoHasta by mutableLongStateOf(0L)

    private val tx get() = e.tx

    private fun lanzar(bloque: suspend () -> Unit) {
        e.scope.launch {
            ocupado = true
            try {
                bloque()
                e.sinConexion(false)
            } catch (ex: ErrorServidor) {
                if (ex.codigo == "SIN_CONEXION") e.sinConexion(true)
                if (ex.codigo == "NO_VINCULADO") e.revincular()
                // Sin conexión ya lo avisa la barra general de la pantalla
                if (ex.codigo != "SIN_CONEXION") {
                    aviso = tx.mensajeError(ex.codigo, ex.message)
                    e.vibrarError()
                    e.hablar(aviso ?: "")
                }
            } finally {
                ocupado = false
            }
        }
    }

    // ---------------- Autorización (huella o bloqueo de pantalla) ----------------

    fun autorizar(motivo: String = tx.autorizarPago) {
        e.autenticar(motivo) {
            Estado.autorizadoHasta = System.currentTimeMillis() + VENTANA_AUTORIZACION_MS
            autorizadoHasta = Estado.autorizadoHasta
            aviso = null
            e.vibrarCorto()
            e.hablar(tx.vozAutorizado)
        }
    }

    /** Alguien acercó el datáfono sin autorizar: se pide la huella y luego hay que acercarlo otra vez. */
    fun alToqueSinAutorizar() {
        if (pago != null) return
        aviso = tx.autoriceYAcerque
        e.hablar(tx.vozAutoriceYAcerque)
        autorizar(tx.autorizarPagoDatafono)
    }

    fun revocar() {
        Estado.autorizadoHasta = 0L
        autorizadoHasta = 0L
    }

    /** Al cambiar de cuenta: olvida el resumen, los medios y el pago en curso de la anterior. */
    fun limpiar() {
        resumen = null
        medios.clear()
        enlacePermiso = null
        aviso = null
        pago = null
        verComprobante = false
        claveDescartada = null
    }

    // ---------------- Datos ----------------

    fun cargarResumen() {
        val id = e.dispositivoId() ?: return
        lanzar { resumen = ServidorApi.resumen(id) }
    }

    fun abrirTarjetas() {
        aviso = null
        val id = e.dispositivoId() ?: return
        lanzar {
            val lista = ServidorApi.medios(id)
            medios.clear()
            medios.addAll(lista)
        }
    }

    fun abrirAgregar() {
        aviso = null
        tarjetaLeida = null
        duenoLeido = null
    }

    fun abrirPermiso() {
        aviso = null
        enlacePermiso = null
        cargarResumen()
    }

    private fun editarMedio(m: Medio, nombre: String? = null, bloqueada: Boolean? = null) {
        val id = e.dispositivoId() ?: return
        lanzar {
            ServidorApi.editarMedio(id, m.uid, nombre, bloqueada)
            if (bloqueada != null) e.hablar(if (bloqueada) tx.medioBloqueado(m.nombre) else tx.medioDesbloqueado(m.nombre))
            val lista = ServidorApi.medios(id)
            medios.clear()
            medios.addAll(lista)
            if (m.esEsteCelular) resumen = ServidorApi.resumen(id)
        }
    }

    private fun eliminarMedio(m: Medio) {
        val id = e.dispositivoId() ?: return
        lanzar {
            ServidorApi.eliminarMedio(id, m.uid)
            e.hablar(tx.medioEliminado(m.nombre))
            if (m.esEsteCelular) e.revincular()
            val lista = runCatching { ServidorApi.medios(id) }.getOrDefault(emptyList())
            medios.clear()
            medios.addAll(lista)
        }
    }

    /** Pide el permiso semanal; si `abrir`, abre el enlace en el navegador de este celular. */
    private fun solicitarPermiso(tope: String, abrir: Boolean) {
        val id = e.dispositivoId() ?: return
        lanzar {
            val enlace = ServidorApi.solicitarPermiso(id, tope.trim().ifEmpty { "200" })
            enlacePermiso = enlace
            if (abrir) {
                e.hablar(tx.vozAprobarPermiso)
                e.abrirEnlace(enlace)
            }
        }
    }

    // ---------------- Agregar tarjeta (único lugar con reader mode en modo Pagar) ----------------

    fun alLeerTarjeta(tag: Tag, postear: (() -> Unit) -> Unit) {
        val t = NfcLectorEscritor.tarjeta(tag)
        postear {
            tarjetaLeida = t
            duenoLeido = null
            e.vibrarCorto()
            lanzar {
                val d = ServidorApi.duenoTarjeta(t.uid, t.wallet)
                duenoLeido = d
                when {
                    !d.registrada -> e.hablar(tx.vozTarjetaLeida)
                    d.clienteId == e.cuentaId() -> e.hablar(tx.tarjetaYaSuya(null))
                    else -> {
                        e.hablar(tx.tarjetaYaRegistrada(d.clienteNombre ?: ""))
                        e.vibrarError()
                    }
                }
            }
        }
    }

    private fun guardarTarjeta(nombre: String, volver: () -> Unit) {
        val id = e.dispositivoId() ?: return
        val t = tarjetaLeida ?: return
        lanzar {
            val m = ServidorApi.agregarMedio(id, t.uid, t.wallet, nombre.trim())
            e.hablar(tx.tarjetaGuardada(m.nombre))
            e.vibrarLargo()
            volver()
        }
    }

    // ---------------- Pago en curso ----------------

    fun iniciarSondeo() {
        sondeo?.cancel()
        sondeo = e.scope.launch {
            while (true) {
                consultarPendiente()
                delay(1500)
            }
        }
    }

    fun detenerSondeo() {
        sondeo?.cancel()
    }

    suspend fun consultarPendiente() {
        val id = e.dispositivoId() ?: return
        try {
            val p = ServidorApi.cobroPendiente(id)
            e.sinConexion(false)
            actualizarPago(p)
        } catch (ex: ErrorServidor) {
            if (ex.codigo == "SIN_CONEXION") e.sinConexion(true)
            if (ex.codigo == "NO_VINCULADO") e.revincular()
        }
    }

    private fun voz(c: Cobro) = tx.montoVoz(c.montoValor, c.assetScale, c.assetCode)

    private fun restanteVoz(datos: JSONObject?): String? {
        val r = datos?.optJSONObject("restante") ?: return null
        return tx.montoVoz(r.getString("value").toLong(), r.getInt("assetScale"), r.getString("assetCode"))
    }

    private fun actualizarPago(p: Pendiente) {
        val c = p.cobro
        val nuevo: PagoEnCurso? = when {
            c != null -> when (c.estado) {
                "esperando_confirmacion" -> PagoEnCurso("${c.id}:espera", TipoPago.ESPERANDO, c, null, null, null)
                "procesando" -> PagoEnCurso("${c.id}:pagando", TipoPago.PAGANDO, c, null, null, null)
                "pagado" -> PagoEnCurso("${c.id}:pagado", TipoPago.HECHO, c, null, null, null)
                "error" -> {
                    val err = c.error
                    PagoEnCurso("${c.id}:error:${err?.optString("codigo")}", TipoPago.ERROR, c, err?.optString("codigo"), err?.optString("mensaje"), err)
                }
                else -> null
            }
            p.intento != null -> {
                val i = p.intento
                PagoEnCurso("intento:${i.optString("cobroId")}:${i.optString("codigo")}", TipoPago.ERROR, null, i.optString("codigo"), i.optString("mensaje"), i)
            }
            else -> null
        }
        if (nuevo == null) {
            // Si el cobro desapareció sin terminar (p. ej. cancelado hace rato), se oculta
            if (pago?.tipo == TipoPago.ESPERANDO || pago?.tipo == TipoPago.PAGANDO) pago = null
            return
        }
        if (nuevo.clave == pago?.clave || nuevo.clave == claveDescartada) return
        pago = nuevo
        verComprobante = false
        when (nuevo.tipo) {
            TipoPago.ESPERANDO -> {
                // La autorización cubre también el segundo toque
                if (Estado.autorizado()) {
                    Estado.autorizadoHasta = maxOf(Estado.autorizadoHasta, System.currentTimeMillis() + VENTANA_AUTORIZACION_MS)
                    autorizadoHasta = Estado.autorizadoHasta
                }
                e.vibrarCorto()
                e.hablar(tx.vozLeCobra(c!!.tienda, voz(c)))
            }
            TipoPago.PAGANDO -> e.hablar(tx.vozPagando)
            TipoPago.HECHO -> {
                revocar()
                pagadoEn = System.currentTimeMillis()
                e.vibrarLargo()
                e.hablar(tx.vozPagoHecho(voz(c!!)))
                cargarResumen()
            }
            TipoPago.ERROR -> {
                revocar()
                e.vibrarError()
                e.hablar(tx.vozErrorPagar(nuevo.codigoError ?: "", restanteVoz(nuevo.datosError)) ?: tx.mensajeError(nuevo.codigoError ?: "", nuevo.mensajeError))
            }
        }
    }

    private fun cerrarPago() {
        claveDescartada = pago?.clave
        pago = null
        verComprobante = false
        e.callar()
    }

    private fun cancelarPago(cobroId: String) {
        lanzar {
            ServidorApi.cancelar(cobroId)
            consultarPendiente()
        }
    }

    private fun escucharDetalles(c: Cobro) {
        e.hablar(tx.vozLeCobra(c.tienda, voz(c)))
    }

    // ---------------- Interfaz ----------------

    @Composable
    private fun AvisoPantalla() {
        aviso?.let { CajaInfo(it, Iconos.Alerta, Tono.ERROR) }
    }

    @Composable
    fun ColumnScope.PantallaInicio(
        nombre: String, historial: Historial?, puedePagarPorNfc: Boolean,
        selector: @Composable () -> Unit, irPermiso: () -> Unit, irMovimientos: () -> Unit, opciones: @Composable () -> Unit
    ) {
        val tx = LocalTextos.current
        val r = resumen
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Titulo(tx.hola(nombre))
            selector()
            AvisoPantalla()
            if (r == null) {
                Cargando()
            } else {
                val p = r.permiso
                if (p.estado == "activo") {
                    TarjetaSaldo(p)
                    AutorizarPago(puedePagarPorNfc, r.medioBloqueado)
                } else {
                    Column(
                        Modifier.fillMaxWidth().background(Paleta.Superficie, RoundedCornerShape(16.dp)).padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconoCuadro(Iconos.Escudo, Paleta.ErrorFondo, Paleta.Error)
                            Spacer(Modifier.width(10.dp))
                            Text(tx.estadoPermiso(p.estado), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Paleta.Texto)
                        }
                        Subtitulo(tx.sinPermisoDetalle)
                    }
                    BotonPrimario(tx.aprobarPermiso, { solicitarPermiso(p.topeDolares ?: "200", abrir = true) }, icono = Iconos.EscudoCheck, enabled = !ocupado)
                    Subtitulo(tx.aprobarPermisoDetalle, tamano = 12.sp)
                }
            }

            // Últimos pagos: los que hizo esta cuenta (no las ventas de su negocio)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tx.ultimosPagos, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Paleta.Texto, modifier = Modifier.weight(1f))
                TextButton(onClick = irMovimientos) { Text(tx.verTodos, color = Paleta.Verde, fontSize = 13.sp) }
            }
            val pagos = historial?.movimientos?.filter { it.tipo == "pago" }?.take(3)
            when {
                pagos == null -> Cargando()
                pagos.isEmpty() -> CajaInfo(tx.sinPagos)
                else -> Column {
                    pagos.forEachIndexed { i, m -> FilaPago(m); if (i < pagos.lastIndex) Separador() }
                }
            }
            opciones()
            Pie(tx.piePagar)
        }
    }

    @Composable
    private fun TarjetaSaldo(p: Permiso) {
        val tx = LocalTextos.current
        Column(
            Modifier.fillMaxWidth().background(Paleta.Verde, RoundedCornerShape(16.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Iconos.Billetera, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(tx.disponibleSemana, fontSize = 13.sp, color = Color.White)
            }
            val texto = if (p.restanteValor != null && p.assetCode != null && p.assetScale != null)
                MontoUtil.corto(p.restanteValor, p.assetCode, p.assetScale, tx.ingles) else p.restanteTexto ?: "—"
            Text(texto, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
            Text("${p.assetCode ?: ""} · ${tx.tuPermisoSemanal}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
            Text(tx.seRenueva(Ventas.fechaIso(p.renuevaEl)), fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
            if (p.fuente == "registro local") Text(tx.fuenteLocal, fontSize = 11.sp, color = Paleta.Lima)
        }
    }

    @Composable
    private fun AutorizarPago(puedePagarPorNfc: Boolean, bloqueado: Boolean) {
        val tx = LocalTextos.current
        var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(autorizadoHasta) {
            while (true) {
                ahora = System.currentTimeMillis()
                if (ahora >= autorizadoHasta) break
                delay(500)
            }
        }
        val segundos = ((autorizadoHasta - ahora + 999) / 1000).coerceAtLeast(0)
        when {
            !puedePagarPorNfc -> CajaInfo(tx.sinHce, Iconos.Info)
            bloqueado -> CajaInfo(tx.celularBloqueadoAviso, Iconos.Candado, Tono.ERROR)
            segundos > 0 -> {
                CajaInfo(tx.autorizadoAcerque(segundos), Iconos.Contactless, Tono.LAVANDA)
                BotonSecundario(tx.cancelarAutorizacion, ::revocar)
            }
            else -> BotonPrimario(tx.pagar, { autorizar() }, icono = Iconos.Contactless)
        }
    }

    @Composable
    private fun FilaPago(m: Movimiento) {
        val tx = LocalTextos.current
        val fecha = Ventas.fechaIso(m.fecha)
        val monto = if (m.montoValor != null && m.assetCode != null && m.assetScale != null)
            MontoUtil.conCodigo(m.montoValor, m.assetCode, m.assetScale, tx.ingles) else m.montoTexto
        FilaMovimiento(
            Iconos.Tienda, m.contraparte ?: "—", fecha?.let { tx.fechaHora(it) } ?: "—", monto,
            if (m.estado == "pagado") tx.confirmado else tx.estadoMovimiento(m.estado),
            if (m.estado == "pagado") Paleta.Exito else Paleta.TextoSuave
        )
    }

    /** Lista completa de pagos hechos por la cuenta. */
    @Composable
    fun ColumnScope.PantallaMovimientos(historial: Historial?, error: String?, actualizar: () -> Unit) {
        val tx = LocalTextos.current
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Titulo(tx.misPagos)
            Subtitulo(tx.misPagosDetalle)
            Spacer(Modifier.height(8.dp))
            error?.let { CajaInfo(it, Iconos.Alerta, Tono.ERROR) }
            val pagos = historial?.movimientos?.filter { it.tipo == "pago" }
            when {
                pagos == null -> Cargando()
                pagos.isEmpty() -> CajaInfo(tx.sinPagos)
                else -> pagos.forEachIndexed { i, m -> FilaPago(m); if (i < pagos.lastIndex) Separador() }
            }
        }
        BotonSecundario(tx.actualizar, actualizar)
    }

    @Composable
    fun ColumnScope.PantallaTarjetas(irAgregar: () -> Unit) {
        val tx = LocalTextos.current
        var renombrando by remember { mutableStateOf<Medio?>(null) }
        var eliminando by remember { mutableStateOf<Medio?>(null) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Titulo(tx.misTarjetas)
            Subtitulo(tx.misTarjetasDetalle)
            AvisoPantalla()
            if (medios.isEmpty() && !ocupado) CajaInfo(tx.sinMedios)
            medios.forEach { m ->
                TarjetaBlanca {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconoCuadro(if (m.tipo == "celular") Iconos.Celular else Iconos.Tarjeta,
                            if (m.bloqueada) Paleta.ErrorFondo else Paleta.Lavanda, if (m.bloqueada) Paleta.Error else Paleta.LavandaTexto)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.nombre + if (m.esEsteCelular) " · ${tx.esteCelular}" else "", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Paleta.Texto)
                            Text(tx.ultimoUso(Ventas.fechaIso(m.ultimoUso)), fontSize = 12.sp, color = Paleta.TextoSuave)
                        }
                        Text(if (m.bloqueada) tx.bloqueada else tx.activa, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = if (m.bloqueada) Paleta.Error else Paleta.Exito)
                    }
                    FilaBotones {
                        BotonSecundario(tx.renombrar, { renombrando = m }, Modifier.weight(1f))
                        BotonSecundario(if (m.bloqueada) tx.desbloquear else tx.bloquear, { editarMedio(m, bloqueada = !m.bloqueada) }, Modifier.weight(1f),
                            color = if (m.bloqueada) Paleta.Verde else Paleta.Error)
                    }
                    TextButton(onClick = { eliminando = m }, modifier = Modifier.fillMaxWidth()) {
                        Text(tx.eliminar, color = Paleta.Error, fontSize = 13.sp)
                    }
                }
            }
        }
        BotonPrimario(tx.agregarTarjeta, irAgregar, icono = Iconos.Mas)

        renombrando?.let { m ->
            var nombre by remember(m.uid) { mutableStateOf(m.nombre) }
            AlertDialog(
                onDismissRequest = { renombrando = null }, containerColor = Paleta.Superficie,
                title = { Text(tx.renombrar, fontSize = 18.sp) },
                text = { CampoTexto(nombre, { nombre = it }, tx.nombreTarjeta) },
                confirmButton = {
                    TextButton(onClick = { editarMedio(m, nombre = nombre.trim()); renombrando = null }, enabled = nombre.isNotBlank()) { Text(tx.guardar, color = Paleta.Verde) }
                },
                dismissButton = { TextButton(onClick = { renombrando = null }) { Text(tx.cancelar, color = Paleta.TextoSuave) } }
            )
        }
        eliminando?.let { m ->
            AlertDialog(
                onDismissRequest = { eliminando = null }, containerColor = Paleta.Superficie,
                title = { Text(tx.eliminarPregunta(m.nombre), fontSize = 18.sp) },
                text = { Text(if (m.esEsteCelular) tx.eliminarCelularDetalle else tx.eliminarDetalle, fontSize = 14.sp) },
                confirmButton = { TextButton(onClick = { eliminarMedio(m); eliminando = null }) { Text(tx.eliminar, color = Paleta.Error) } },
                dismissButton = { TextButton(onClick = { eliminando = null }) { Text(tx.cancelar, color = Paleta.TextoSuave) } }
            )
        }
    }

    @Composable
    fun ColumnScope.PantallaAgregar(volver: () -> Unit) {
        val tx = LocalTextos.current
        val t = tarjetaLeida
        val d = duenoLeido
        var nombre by remember(t?.uid, d) { mutableStateOf(d?.nombre ?: "") }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Ceja(tx.agregarTarjeta)
                Titulo(if (t == null) tx.acercaTarjeta else tx.nombraTarjeta)
                Subtitulo(tx.agregarDetalle)
            }
            AvisoPantalla()
            if (t == null) {
                IlustracionToque(tx.billetera, tx.tarjetaNfc, tx.sinRegistrar, tx.esteCelular, regresa = false)
            } else {
                TarjetaBlanca {
                    FilaDato(tx.tarjetaNfc, t.uid)
                    FilaDato(tx.walletEnTarjeta, t.wallet ?: tx.ninguna)
                }
                val ajena = d != null && d.registrada && d.clienteId != e.cuentaId()
                when {
                    d == null -> Cargando()
                    ajena -> CajaInfo(tx.tarjetaYaRegistrada(d.clienteNombre ?: ""), Iconos.Alerta, Tono.ERROR)
                    d.registrada -> CajaInfo(tx.tarjetaYaSuya(d.nombre), Iconos.Check, Tono.EXITO)
                }
                if (!ajena && d != null) CampoTexto(nombre, { nombre = it }, tx.nombreTarjeta)
                if (d != null) {
                    BotonPrimario(tx.guardar, { guardarTarjeta(nombre, volver) }, enabled = !ajena && !ocupado)
                    BotonSecundario(tx.leerOtra, { tarjetaLeida = null; duenoLeido = null })
                }
            }
        }
        BotonSecundario(tx.volver, volver)
    }

    @Composable
    fun ColumnScope.PantallaPermiso() {
        val tx = LocalTextos.current
        val p = resumen?.permiso
        var tope by remember(p?.topeDolares) { mutableStateOf(p?.topeDolares ?: "200") }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Titulo(tx.miPermiso)
            Subtitulo(tx.miPermisoDetalle)
            AvisoPantalla()
            if (p != null) {
                if (p.estado == "activo") {
                    TarjetaBlanca {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Iconos.CirculoCheck, null, tint = Paleta.Exito, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tx.permisoActivo, fontSize = 13.sp, color = Paleta.Exito, fontWeight = FontWeight.Medium)
                        }
                        FilaDato(tx.topeSemanal, p.topeTexto ?: "—")
                        FilaDato(tx.gastado, p.gastadoTexto ?: "—")
                        FilaDato(tx.teQueda, p.restanteTexto ?: "—")
                        FilaDato(tx.seRenuevaEtiqueta, Ventas.fechaIso(p.renuevaEl)?.let { tx.fechaCorta(it, conAnio = true) } ?: "—")
                    }
                } else {
                    CajaInfo(tx.estadoPermiso(p.estado), Iconos.Escudo, Tono.ERROR)
                }
            }
            CampoTexto(tope, { tope = it }, tx.topeEnDolares, KeyboardType.Decimal)
            BotonPrimario(if (p?.estado == "activo") tx.pedirNuevoPermiso else tx.aprobarPermiso, { solicitarPermiso(tope, abrir = true) }, enabled = !ocupado)
            BotonSecundario(tx.soloEnlace, { solicitarPermiso(tope, abrir = false) }, enabled = !ocupado)
            enlacePermiso?.let { enlace ->
                TarjetaBlanca {
                    Text(tx.enlaceAprobacion, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(enlace, fontSize = 12.sp, color = Paleta.TextoSuave)
                    FilaBotones {
                        BotonSecundario(tx.copiar, { e.copiar(enlace); e.hablar(tx.enlaceCopiado) }, Modifier.weight(1f))
                        BotonSecundario(tx.abrir, { e.abrirEnlace(enlace) }, Modifier.weight(1f))
                    }
                }
            }
            CajaInfo(tx.reglasPermiso, Iconos.Info)
        }
        BotonSecundario(tx.yaLoAprobe, ::cargarResumen)
    }

    /** Pantalla completa del pago que llega del datáfono (revisar, procesando, confirmado, no completado, comprobante). */
    @Composable
    fun OverlayPago(p: PagoEnCurso, nombre: String) {
        val tx = LocalTextos.current
        Column(
            Modifier.fillMaxSize().background(Paleta.Fondo).clickable(enabled = false) {}
                .statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Encabezado(tx.pagar)
            val c = p.cobro
            if (verComprobante && c != null) {
                ComprobantePago(c, nombre)
                return@Column
            }
            when (p.tipo) {
                TipoPago.ESPERANDO -> if (c != null) RevisarCompra(c)
                TipoPago.PAGANDO -> Procesando(c)
                TipoPago.HECHO -> if (c != null) Confirmado(c, nombre)
                TipoPago.ERROR -> NoCompletado(p)
            }
        }
    }

    @Composable
    private fun ColumnScope.RevisarCompra(c: Cobro) {
        val tx = LocalTextos.current
        val r = resumen?.permiso
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Ceja(tx.cobroPendiente)
                Titulo(tx.revisaCompra)
                Subtitulo(tx.revisaCompraDetalle)
            }
            TarjetaBlanca {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconoCuadro(Iconos.Tienda, Paleta.Lavanda, Paleta.LavandaTexto)
                    Spacer(Modifier.width(10.dp))
                    Text(c.tienda, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Paleta.Texto)
                }
                MontoGrande(MontoUtil.corto(c.montoValor, c.assetCode, c.assetScale, tx.ingles), tx.nombreMoneda(c.assetCode), tamano = 34.sp)
                if (c.debitTexto != null && c.debitTexto != c.montoTexto) FilaDato(tx.seDebitara, c.debitTexto)
                if (r?.restanteValor != null && r.assetCode == c.assetCode && r.assetScale != null) {
                    Separador()
                    FilaDato(tx.disponibleSemana, MontoUtil.conCodigo(r.restanteValor, c.assetCode, r.assetScale, tx.ingles))
                    if (c.debitTexto == null || c.debitTexto == c.montoTexto) {
                        FilaDato(tx.despuesDelPago, MontoUtil.conCodigo(r.restanteValor - c.montoValor, c.assetCode, r.assetScale, tx.ingles))
                    }
                }
            }
            BotonSecundario(tx.escucharDetalles, { escucharDetalles(c) }, icono = Iconos.Volumen)
            // La confirmación es el segundo toque del celular al datáfono (no un botón)
            CajaInfo(tx.acerqueOtraVez, Iconos.Contactless, Tono.LAVANDA)
        }
        BotonSecundario(tx.cancelar, { cancelarPago(c.id) }, enabled = !ocupado)
    }

    @Composable
    private fun ColumnScope.Procesando(c: Cobro?) {
        val tx = LocalTextos.current
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(12.dp))
            IconoResultado(null, cargando = true)
            Titulo(tx.procesandoPago, tamano = 22.sp, centrado = true)
            c?.let {
                Subtitulo(tx.verificandoPagoA(it.tienda), centrado = true)
                MontoGrande(MontoUtil.corto(it.montoValor, it.assetCode, it.assetScale, tx.ingles), tx.nombreMoneda(it.assetCode), tamano = 34.sp, centrado = true)
            }
            BarraProgreso()
            Text(tx.verificandoOperacion, fontSize = 12.sp, color = Paleta.TextoSuave, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        CajaInfo(tx.noRepitas, Iconos.EscudoCheck)
    }

    @Composable
    private fun ColumnScope.Confirmado(c: Cobro, nombre: String) {
        val tx = LocalTextos.current
        val r = resumen?.permiso
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(8.dp))
            IconoResultado(Iconos.Check)
            Titulo(tx.pagoConfirmado, tamano = 22.sp, centrado = true)
            Subtitulo(tx.listoCompraPagada(nombre), centrado = true)
            MontoGrande(MontoUtil.corto(c.montoValor, c.assetCode, c.assetScale, tx.ingles), tx.nombreMoneda(c.assetCode), tamano = 34.sp, centrado = true)
            TarjetaBlanca {
                FilaDato(tx.negocio, c.tienda)
                if (r?.restanteValor != null && r.assetCode != null && r.assetScale != null) {
                    FilaDato(tx.disponibleSemana, MontoUtil.conCodigo(r.restanteValor, r.assetCode, r.assetScale, tx.ingles))
                }
            }
            CajaInfo("${tx.fechaCorta(pagadoEn, conAnio = true)} · ${tx.hora(pagadoEn)} · ${c.id.uppercase()}", Iconos.Check, Tono.NEUTRO)
        }
        BotonPrimario(tx.verComprobante, { verComprobante = true })
        BotonSecundario(tx.volverAlInicio, ::cerrarPago)
    }

    @Composable
    private fun ColumnScope.NoCompletado(p: PagoEnCurso) {
        val tx = LocalTextos.current
        val c = p.cobro
        val restante = p.datosError?.optJSONObject("restante")?.let {
            MontoUtil.conCodigo(it.getString("value").toLong(), it.getString("assetCode"), it.getInt("assetScale"), tx.ingles)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(8.dp))
            IconoResultado(Iconos.Alerta, error = true)
            Titulo(tx.noPudimosCompletar, tamano = 22.sp, centrado = true)
            Subtitulo(tx.detalleErrorPagar(p.codigoError ?: "", restante) ?: tx.mensajeError(p.codigoError ?: "", p.mensajeError), centrado = true)
            c?.let {
                MontoGrande(MontoUtil.corto(it.montoValor, it.assetCode, it.assetScale, tx.ingles), tx.nombreMoneda(it.assetCode), tamano = 34.sp, centrado = true)
                TarjetaBlanca { FilaDato(tx.negocio, it.tienda) }
            }
            CajaInfo(tx.noSeCompletoCargo, Iconos.Info)
        }
        BotonPrimario(tx.volverAlInicio, ::cerrarPago)
    }

    @Composable
    private fun ColumnScope.ComprobantePago(c: Cobro, nombre: String) {
        val tx = LocalTextos.current
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Titulo(tx.comprobante)
            TarjetaBlanca {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Iconos.CirculoCheck, null, tint = Paleta.Exito, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tx.confirmado, fontSize = 12.sp, color = Paleta.Exito, fontWeight = FontWeight.Medium)
                }
                Text(tx.pagoRealizado, fontSize = 12.sp, color = Paleta.TextoSuave)
                MontoGrande(MontoUtil.corto(c.montoValor, c.assetCode, c.assetScale, tx.ingles), tx.nombreMoneda(c.assetCode), tamano = 32.sp)
                Separador()
                FilaDato(tx.pagadoPor, nombre)
                FilaDato(tx.pagadoA, c.tienda)
                FilaDato(tx.fecha, tx.fechaCorta(pagadoEn, conAnio = true))
                FilaDato(tx.horaEtiqueta, tx.hora(pagadoEn))
                FilaDato(tx.referencia, c.id.uppercase())
                FilaDato(tx.medio, tx.medioTipo(c.medioTipo) ?: "—")
            }
            CajaInfo(tx.guardaReferencia, Iconos.Documento)
        }
        BotonPrimario(tx.volverAlInicio, ::cerrarPago)
    }
}
