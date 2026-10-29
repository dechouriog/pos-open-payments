package com.example.nfc

import android.nfc.Tag
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class PasoCobro { INICIO, MARCAR, ACERQUE, CONFIRMAR, PAGANDO, PAGADO, ERROR, COMPROBANTE }

data class ErrorMostrado(val codigo: String, val detalle: String, val datos: JSONObject?)

/** Datos de un comprobante: de un cobro recién pagado o de una venta del historial. */
data class Comprobante(
    val valor: Long, val assetCode: String, val assetScale: Int,
    val de: String?, val en: String, val fechaMs: Long, val referencia: String, val medio: String?
)

// Errores en los que el cobro sigue vigente y se puede probar con otra tarjeta
private val ERRORES_DE_TARJETA = setOf("TARJETA_NO_REGISTRADA", "SIN_PERMISO", "TOPE_SUPERADO", "MEDIO_BLOQUEADO", "MISMA_CUENTA")

/** Modo "Mi negocio" (datáfono): el cobro va a la wallet de la cuenta de este celular. */
class ModoCobrar(private val e: Entorno, private val historial: PestanaHistorial) {

    // ---- Cobro ----
    var paso by mutableStateOf(PasoCobro.INICIO)
        private set
    private var config by mutableStateOf<ConfigCobro?>(null)
    var errorConfig by mutableStateOf<String?>(null)
        private set
    private var ocupado by mutableStateOf(false)
    private var entrada by mutableStateOf("")
    private var cobro by mutableStateOf<Cobro?>(null)
    private var limiteMs by mutableStateOf(0L)
    private var uidPrimera: String? = null
    private var momentoPrimera = 0L
    private var aviso by mutableStateOf<String?>(null)
    private var error by mutableStateOf<ErrorMostrado?>(null)
    private var pagadoEn = 0L
    private var comprobante by mutableStateOf<Comprobante?>(null)
    private var volverDeComprobante = PasoCobro.INICIO

    // ---- Gráfica ----
    private var periodo by mutableStateOf(Periodo.DIAS7)
    private var monedaElegida by mutableStateOf<String?>(null)
    private var seleccion by mutableIntStateOf(Periodo.DIAS7.barras - 1)

    /** Hay un cobro en curso: no se puede cambiar de modo. */
    val enCurso get() = paso != PasoCobro.INICIO

    // ---- Registrar tarjeta ----
    private var tarjetaRegistro by mutableStateOf<TarjetaLeida?>(null)
    private val cuentas = mutableStateListOf<Cuenta>()
    private var cuentaElegida by mutableStateOf<Cuenta?>(null)
    private var mensajeRegistro by mutableStateOf<Pair<Tono, String>?>(null)
    private var esperandoEscritura by mutableStateOf(false)

    // Wallet a escribir en la próxima tarjeta; la lee el hilo del reader mode
    @Volatile
    private var walletPorEscribir: String? = null
    @Volatile
    private var uidPorEscribir: String? = null

    // ---- Diagnóstico ----
    private val lecturas = mutableStateListOf<Lectura>()

    private val tx get() = e.tx

    // ---------------- NFC (hilo del reader mode) ----------------

    /** Tag leído en modo Mi negocio. `registrando` = está abierta la pantalla Registrar. */
    fun alLeerTag(tag: Tag, registrando: Boolean, postear: (() -> Unit) -> Unit) {
        // Primero se intenta leer un celular en modo Pagar (HCE); si no, la tarjeta como siempre
        val t = NfcLectorEscritor.celular(tag) ?: NfcLectorEscritor.tarjeta(tag)
        Log.d(TAG_LOG, "Leído como ${if (t.esCelular) "CELULAR" else "tarjeta"}: uid=${t.uid} wallet=${t.wallet} tecnologías=${tag.techList.joinToString { it.substringAfterLast('.') }}")
        val wallet = walletPorEscribir
        if (registrando && wallet != null) {
            if (t.uid != uidPorEscribir) {
                postear { mensajeRegistro = Tono.ERROR to tx.otraTarjetaMisma; e.vibrarError() }
                return
            }
            val r = NfcLectorEscritor.escribirUrl(tag, wallet)
            postear {
                mensajeRegistro = (if (r.exito) Tono.EXITO else Tono.ERROR) to r.mensaje
                if (r.exito) {
                    walletPorEscribir = null
                    esperandoEscritura = false
                    e.vibrarLargo()
                } else e.vibrarError()
            }
            return
        }
        postear {
            when {
                registrando -> {
                    tarjetaRegistro = t
                    mensajeRegistro = null
                    e.vibrarCorto()
                    avisarSiYaRegistrada(t)
                }
                paso == PasoCobro.ACERQUE -> if (!ocupado) primeraLectura(t)
                // Pausa corta para no tomar el mismo toque como confirmación: hay que retirar y volver a acercar
                paso == PasoCobro.CONFIRMAR -> if (!ocupado && System.currentTimeMillis() - momentoPrimera > 1500) confirmar(t)
            }
        }
    }

    /** Diagnóstico (Ajustes → Leer etiquetas), en cualquier modo. */
    fun alLeerDiagnostico(tag: Tag, postear: (() -> Unit) -> Unit) {
        val l = NfcLectorEscritor.leer(tag)
        postear {
            lecturas.add(0, l)
            while (lecturas.size > 10) lecturas.removeAt(lecturas.lastIndex)
            e.vibrarCorto()
        }
    }

    // ---------------- Flujo del cobro ----------------

    fun cargarConfig() {
        val id = e.cuentaId() ?: return
        e.scope.launch {
            try {
                config = ServidorApi.configCobro(id)
                errorConfig = null
                e.sinConexion(false)
            } catch (ex: ErrorServidor) {
                // Sin conexión ya lo avisa la barra general de la pantalla
                errorConfig = if (ex.codigo == "SIN_CONEXION") null else tx.mensajeError(ex.codigo, ex.message)
                if (ex.codigo == "SIN_CONEXION") e.sinConexion(true)
            }
        }
        historial.cargar()
    }

    private fun voz(valor: Long, c: Cobro? = cobro): String {
        val scale = c?.assetScale ?: config?.assetScale ?: 2
        val code = c?.assetCode ?: config?.assetCode ?: ""
        return tx.montoVoz(valor, scale, code)
    }

    private fun tocarTecla(c: Char) {
        val scale = config?.assetScale ?: return
        val nueva = when (c) {
            '<' -> entrada.dropLast(1)
            '.' -> if (entrada.contains('.') || scale == 0) entrada else entrada.ifEmpty { "0" } + "."
            'D' -> if (entrada.isEmpty() || entrada == "0") entrada else entrada + "00"
            else -> if (entrada == "0") c.toString() else entrada + c
        }
        // Máximo de decimales = assetScale, y un tope razonable de cifras enteras
        val decimales = nueva.substringAfter('.', "")
        if (decimales.length <= scale && nueva.substringBefore('.').length <= 9) entrada = nueva
    }

    fun irAMarcar() {
        nuevoCobro()
        paso = PasoCobro.MARCAR
        if (config == null) cargarConfig()
    }

    private fun cobrar() {
        val cfg = config ?: return cargarConfig()
        val cuentaId = e.cuentaId() ?: return
        val texto = entrada.trimEnd('.')
        val valor = MontoUtil.aUnidades(texto, cfg.assetScale) ?: return
        if (valor <= 0 || ocupado) return
        ocupado = true
        e.scope.launch {
            try {
                val c = ServidorApi.crearCobro(texto, cuentaId)
                cobro = c
                limiteMs = System.currentTimeMillis() + c.segundosRestantes * 1000L
                aviso = null
                paso = PasoCobro.ACERQUE
                e.hablar(tx.vozAcerque)
            } catch (ex: ErrorServidor) {
                mostrarError(ex.codigo, ex.message ?: "", ex.datos)
            } finally {
                ocupado = false
            }
        }
    }

    private fun primeraLectura(t: TarjetaLeida) {
        val c = cobro ?: return
        ocupado = true
        e.vibrarCorto()
        e.scope.launch {
            try {
                val r = ServidorApi.tarjeta(c.id, t.uid, t.wallet)
                cobro = r
                uidPrimera = t.uid
                momentoPrimera = System.currentTimeMillis()
                aviso = null
                paso = PasoCobro.CONFIRMAR
                leerDetalles(r)
            } catch (ex: ErrorServidor) {
                mostrarError(ex.codigo, ex.message ?: "", ex.datos)
            } finally {
                ocupado = false
            }
        }
    }

    /** Lee en voz alta cliente, monto y negocio (botón "Escuchar detalles" o tras el primer toque). */
    private fun leerDetalles(c: Cobro? = cobro) {
        if (c == null) return
        e.hablar(tx.vozPregunta(c.cliente ?: "", voz(c.montoValor, c), c.tienda, c.medioTipo == "celular"))
    }

    private fun confirmar(t: TarjetaLeida) {
        val c = cobro ?: return
        if (t.uid != uidPrimera) {
            // Misma regla que TARJETA_DISTINTA del servidor, revisada aquí sin llamar al servidor
            aviso = if (c.medioTipo == "celular") tx.otroMedioCelular else tx.otraTarjetaMisma
            e.hablar(tx.vozOtraTarjeta)
            e.vibrarError()
            return
        }
        ocupado = true
        // Antes de transferir: vibración doble y el monto en voz; se espera a que termine de hablar
        e.vibrar(longArrayOf(0, 250, 150, 250))
        e.scope.launch {
            try {
                e.hablarYEsperar(tx.vozTransfiriendo(voz(c.montoValor, c), c.tienda))
                cobro = ServidorApi.confirmar(c.id, t.uid)
                paso = PasoCobro.PAGANDO
                consultarHastaTerminar(c.id)
            } catch (ex: ErrorServidor) {
                // Sin respuesta del servidor no se sabe si el pago salió: se consulta el estado en vez de declarar un fallo
                if (ex.codigo == "SIN_CONEXION") mostrarError("RESULTADO_DESCONOCIDO", "", null)
                else mostrarError(ex.codigo, ex.message ?: "", ex.datos)
            } finally {
                ocupado = false
            }
        }
    }

    /** [④] Consulta el cobro cada segundo hasta que quede pagado o en error. */
    private fun consultarHastaTerminar(id: String) {
        e.scope.launch {
            var fallos = 0
            while (paso == PasoCobro.PAGANDO && cobro?.id == id) {
                delay(1000)
                try {
                    val c = ServidorApi.estado(id)
                    fallos = 0
                    cobro = c
                    when (c.estado) {
                        "pagado" -> {
                            pagadoEn = System.currentTimeMillis()
                            paso = PasoCobro.PAGADO
                            e.vibrarLargo()
                            e.hablar(tx.vozPagoRecibido(voz(c.montoValor, c)))
                            historial.cargar()
                            return@launch
                        }
                        "error" -> {
                            val err = c.error
                            mostrarError(err?.optString("codigo") ?: "ERROR", err?.optString("mensaje") ?: "", err)
                            return@launch
                        }
                    }
                } catch (ex: ErrorServidor) {
                    // Tres fallos seguidos: el resultado es desconocido (no se declara fallido)
                    if (++fallos >= 3) {
                        mostrarError("RESULTADO_DESCONOCIDO", ex.message ?: "", ex.datos)
                        return@launch
                    }
                }
            }
        }
    }

    /** Vuelve a consultar un cobro cuyo resultado no se conoce. */
    private fun consultarDeNuevo() {
        val c = cobro ?: return
        error = null
        paso = PasoCobro.PAGANDO
        consultarHastaTerminar(c.id)
    }

    private fun mostrarError(codigo: String, mensaje: String, datos: JSONObject?) {
        error = ErrorMostrado(codigo, mensaje, datos)
        paso = PasoCobro.ERROR
        e.vibrarError()
        e.hablar(tx.vozError(codigo, datos?.optString("medioTipo") == "celular", restanteVoz(datos)))
    }

    private fun restanteVoz(datos: JSONObject?): String? {
        val r = datos?.optJSONObject("restante") ?: return null
        return tx.montoVoz(r.getString("value").toLong(), r.getInt("assetScale"), r.getString("assetCode"))
    }

    private fun restanteTexto(datos: JSONObject?): String? {
        val r = datos?.optJSONObject("restante") ?: return null
        return MontoUtil.conCodigo(r.getString("value").toLong(), r.getString("assetCode"), r.getInt("assetScale"), tx.ingles)
    }

    private fun vencido() {
        if ((paso == PasoCobro.ACERQUE || paso == PasoCobro.CONFIRMAR) && !ocupado) {
            mostrarError("COBRO_VENCIDO", "", null)
        }
    }

    private fun cancelar() {
        cobro?.let { c -> e.scope.launch { runCatching { ServidorApi.cancelar(c.id) } } }
        e.callar()
        nuevoCobro()
    }

    /** Limpia el cobro y vuelve al inicio de Mi negocio. */
    fun nuevoCobro() {
        cobro = null
        entrada = ""
        error = null
        aviso = null
        uidPrimera = null
        comprobante = null
        paso = PasoCobro.INICIO
    }

    /** Al cambiar de cuenta: el negocio y la moneda pueden ser otros. */
    fun limpiar() {
        nuevoCobro()
        config = null
        errorConfig = null
        monedaElegida = null
    }

    /** Regresar (botón atrás del sistema): solo fuera de los pasos con el cliente. */
    fun atras(): Boolean = when (paso) {
        PasoCobro.MARCAR -> { nuevoCobro(); true }
        PasoCobro.COMPROBANTE -> { paso = volverDeComprobante; comprobante = null; true }
        PasoCobro.PAGADO, PasoCobro.ERROR -> { if (error?.codigo != "RESULTADO_DESCONOCIDO") nuevoCobro(); true }
        else -> false
    }

    private fun verComprobante(c: Comprobante, desde: PasoCobro) {
        comprobante = c
        volverDeComprobante = desde
        paso = PasoCobro.COMPROBANTE
    }

    private fun comprobanteDelCobro(): Comprobante? {
        val c = cobro ?: return null
        return Comprobante(c.montoValor, c.assetCode, c.assetScale, c.cliente, c.tienda, pagadoEn, c.id.uppercase(), tx.medioTipo(c.medioTipo))
    }

    // ---------------- Registrar tarjeta ----------------

    fun abrirRegistrar() {
        walletPorEscribir = null
        esperandoEscritura = false
        tarjetaRegistro = null
        cuentaElegida = null
        mensajeRegistro = null
        e.scope.launch {
            try {
                val lista = ServidorApi.cuentas()
                cuentas.clear()
                cuentas.addAll(lista)
            } catch (ex: ErrorServidor) {
                mensajeRegistro = Tono.ERROR to tx.mensajeError(ex.codigo, ex.message)
            }
        }
    }

    /** Si la tarjeta ya pertenece a una cuenta, lo dice y la deja elegida (se puede cambiar). */
    private fun avisarSiYaRegistrada(t: TarjetaLeida) {
        e.scope.launch {
            try {
                if (t.esCelular) {
                    // Los celulares no se registran aquí: se vinculan desde su propia app en modo Pagar
                    val d = ServidorApi.duenoTarjeta(t.uid, null)
                    if (tarjetaRegistro?.uid != t.uid) return@launch
                    val texto = if (d.registrada) tx.celularDe(d.clienteNombre ?: "") else tx.celularSinVincular
                    mensajeRegistro = Tono.LAVANDA to texto
                    e.hablar(texto)
                    return@launch
                }
                val d = ServidorApi.duenoTarjeta(t.uid, t.wallet)
                if (!d.registrada || tarjetaRegistro?.uid != t.uid) return@launch
                mensajeRegistro = Tono.LAVANDA to tx.tarjetaYaRegistrada(d.clienteNombre ?: "")
                cuentas.find { it.id == d.clienteId }?.let { cuentaElegida = it }
                e.hablar(tx.tarjetaYaRegistrada(d.clienteNombre ?: ""))
            } catch (ex: ErrorServidor) {
                mensajeRegistro = Tono.ERROR to tx.mensajeError(ex.codigo, ex.message)
            }
        }
    }

    private fun guardarTarjeta() {
        val t = tarjetaRegistro ?: return
        val c = cuentaElegida ?: return
        ocupado = true
        e.scope.launch {
            try {
                ServidorApi.registrarTarjeta(t.uid, c.id)
                mensajeRegistro = Tono.EXITO to tx.tarjetaGuardadaPara(c.nombre)
                e.vibrarLargo()
                e.hablar(tx.tarjetaGuardadaPara(c.nombre))
            } catch (ex: ErrorServidor) {
                mensajeRegistro = Tono.ERROR to tx.mensajeError(ex.codigo, ex.message)
                e.vibrarError()
            } finally {
                ocupado = false
            }
        }
    }

    // ---------------- Interfaz ----------------

    /** Etiqueta de la derecha del encabezado en cada paso. */
    fun etiquetaEncabezado(nombre: String): String = when (paso) {
        PasoCobro.INICIO -> nombre
        PasoCobro.MARCAR -> config?.negocio ?: tx.miNegocio
        else -> tx.miNegocio
    }

    @Composable
    fun ColumnScope.PantallaCobrar(nombre: String, cuenta: Cuenta, selector: @Composable () -> Unit, opciones: @Composable () -> Unit) {
        when (paso) {
            PasoCobro.INICIO -> PantallaInicioNegocio(nombre, cuenta, selector, opciones)
            PasoCobro.MARCAR -> PantallaMarcar()
            PasoCobro.ACERQUE -> PantallaAcerque()
            PasoCobro.CONFIRMAR -> PantallaConfirmar()
            PasoCobro.PAGANDO -> PantallaPagando()
            PasoCobro.PAGADO -> PantallaPagado()
            PasoCobro.ERROR -> PantallaError()
            PasoCobro.COMPROBANTE -> comprobante?.let { PantallaComprobante(it) }
        }
    }

    // ---- Inicio con la gráfica de ventas ----

    @Composable
    private fun ColumnScope.PantallaInicioNegocio(nombre: String, cuenta: Cuenta, selector: @Composable () -> Unit, opciones: @Composable () -> Unit) {
        val tx = LocalTextos.current
        val h = historial.datos
        val ventas = h?.let { Ventas.desdeHistorial(it.movimientos) } ?: emptyList()
        val monedas = Ventas.monedas(ventas).ifEmpty { listOfNotNull(config?.assetCode ?: cuenta.assetCode) }
        val moneda = monedaElegida?.takeIf { it in monedas } ?: monedas.firstOrNull()
        val scale = h?.movimientos?.firstOrNull { it.assetCode == moneda }?.assetScale ?: config?.assetScale ?: cuenta.assetScale ?: 2
        val ahora = System.currentTimeMillis()
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Titulo(tx.miNegocio)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Iconos.Tienda, null, tint = Paleta.TextoSuave, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${config?.negocio ?: cuenta.negocioTexto} · ${tx.fechaCorta(ahora, conAnio = true)}", fontSize = 12.sp, color = Paleta.TextoSuave)
                }
            }
            selector()
            errorConfig?.let { CajaInfo(it, Iconos.Alerta, Tono.ERROR) }
            historial.error?.let { CajaInfo(it, Iconos.Alerta, Tono.ERROR) }
            if (h == null) {
                Cargando()
            } else if (moneda == null) {
                CajaInfo(tx.sinVentas)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    monedas.forEach { m ->
                        Ficha(m, m == moneda, { monedaElegida = m; seleccion = periodo.barras - 1 }, Modifier.weight(1f), conCheck = true)
                    }
                    // Con una sola moneda la ficha conserva el ancho que tiene en el prototipo (media fila)
                    if (monedas.size == 1) Spacer(Modifier.weight(1f))
                }
                Segmentado(
                    listOf(Periodo.DIAS7 to tx.dias(7), Periodo.DIAS30 to tx.dias(30), Periodo.MESES12 to tx.meses(12)), periodo
                ) { periodo = it; seleccion = it.barras - 1 }
                val serie = Ventas.serie(ventas, moneda, periodo, ahora)
                val sel = seleccion.coerceIn(0, periodo.barras - 1)
                Text(tx.rango(serie.barras.first().inicio, serie.barras.last().fin - 1, periodo), fontSize = 12.sp, color = Paleta.TextoSuave)
                TarjetaVentas(serie, periodo, moneda, scale, sel) { seleccion = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Iconos.EscudoCheck, null, tint = Paleta.TextoSuave, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tx.soloConfirmados(moneda), fontSize = 11.sp, color = Paleta.TextoSuave)
                }
                if (serie.cantidad == 0) CajaInfo(tx.sinVentasPeriodo)

                // Ventas recientes: las mismas ventas confirmadas de la gráfica, en la moneda elegida
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tx.ventasRecientes, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Paleta.Texto)
                    Text(tx.ventasRecientesDetalle, fontSize = 12.sp, color = Paleta.TextoSuave)
                }
                val recientes = h.movimientos.filter { it.tipo == "cobro" && it.estado == "pagado" && it.assetCode == moneda }.take(3)
                if (recientes.isEmpty()) CajaInfo(tx.sinVentasRecientes)
                recientes.forEach { m ->
                    val fecha = Ventas.fechaIso(m.fecha)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilaMovimiento(
                            Iconos.FlechaEntrada, m.contraparte ?: tx.cliente,
                            fecha?.let { tx.fechaHora(it) } ?: "—",
                            m.montoValor?.let { MontoUtil.conCodigo(it, moneda, scale, tx.ingles) } ?: m.montoTexto,
                            "✓ ${tx.confirmado}"
                        )
                        BotonSecundario(tx.verComprobante, {
                            verComprobante(
                                Comprobante(
                                    m.montoValor ?: 0, moneda, scale, m.contraparte, config?.negocio ?: cuenta.negocioTexto,
                                    fecha ?: 0, m.id.uppercase(), m.medio ?: tx.tarjetaNfc
                                ), PasoCobro.INICIO
                            )
                        })
                    }
                    Separador()
                }
            }
            opciones()
            Pie(tx.pieNegocio)
        }
        BotonPrimario(tx.cobrar, ::irAMarcar, icono = Iconos.Contactless, enabled = !ocupado)
    }

    // ---- Ingresar monto ----

    @Composable
    private fun ColumnScope.PantallaMarcar() {
        val tx = LocalTextos.current
        val cfg = config
        val valor = cfg?.let { MontoUtil.aUnidades(entrada.trimEnd('.').ifEmpty { "0" }, it.assetScale) } ?: 0L
        val rapidos = listOf("5", "10", "20")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Ceja(tx.nuevoCobro)
                Titulo(tx.ingresarMonto)
                Subtitulo(tx.ingresarMontoDetalle)
            }
            if (cfg == null) {
                errorConfig?.let { CajaInfo(it, Iconos.Alerta, Tono.ERROR) } ?: Cargando()
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tx.moneda, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Paleta.Texto, modifier = Modifier.weight(1f))
                    // Solo la moneda de la wallet que cobra: el servidor no convierte divisas
                    Ficha(cfg.assetCode, true, {}, conCheck = true)
                }
                MontoGrande(MontoUtil.corto(valor, cfg.assetCode, cfg.assetScale, tx.ingles), tx.nombreMoneda(cfg.assetCode), tamano = 40.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rapidos.forEach { m ->
                        val unidades = MontoUtil.aUnidades(m, cfg.assetScale)!!
                        Ficha(MontoUtil.corto(unidades, cfg.assetCode, cfg.assetScale, tx.ingles).let { if (cfg.assetScale > 0) it.substringBeforeLast(if (tx.ingles) '.' else ',') else it },
                            valor == unidades, { entrada = m }, Modifier.weight(1f), oscura = false)
                    }
                }
                Teclado(::tocarTecla, conDecimal = cfg.assetScale > 0, separador = if (tx.ingles) '.' else ',')
                Text(tx.revisaMonto, fontSize = 12.sp, color = Paleta.TextoSuave)
            }
        }
        BotonPrimario(if (ocupado) tx.creandoCobro else tx.continuar, ::cobrar, enabled = cfg != null && valor > 0 && !ocupado)
        BotonSecundario(tx.cancelar, ::nuevoCobro)
    }

    // ---- Primer toque ----

    @Composable
    private fun ColumnScope.PantallaAcerque() {
        val tx = LocalTextos.current
        val c = cobro ?: return
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Ceja(tx.primerToque)
                Titulo(tx.acercaTarjeta)
                Subtitulo(tx.listaParaCobrar(c.tienda))
            }
            MontoGrande(MontoUtil.corto(c.montoValor, c.assetCode, c.assetScale, tx.ingles), tx.nombreMoneda(c.assetCode))
            IlustracionToque(tx.billetera, tx.tarjetaNfc, tx.nfcRegistrada, c.tienda, regresa = false)
            CajaInfo(if (ocupado) tx.leyendo else tx.soloRegistradas, Iconos.Contactless)
            CuentaRegresiva(limiteMs, ::vencido)
        }
        BotonSecundario(tx.cancelarCobro, ::cancelar)
    }

    // ---- Segundo toque ----

    @Composable
    private fun ColumnScope.PantallaConfirmar() {
        val tx = LocalTextos.current
        val c = cobro ?: return
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Ceja(tx.segundoToque)
                Titulo(if (c.medioTipo == "celular") tx.retiraCelular else tx.retiraTarjeta)
            }
            MontoGrande(MontoUtil.corto(c.montoValor, c.assetCode, c.assetScale, tx.ingles), tx.nombreMoneda(c.assetCode))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilaDato(tx.cliente, c.cliente ?: "—")
                FilaDato(tx.negocio, c.tienda)
                if (c.debitTexto != null && c.debitTexto != c.montoTexto) FilaDato(tx.seDebitara, c.debitTexto)
            }
            IlustracionToque(tx.billetera, c.cliente ?: tx.cliente, tx.nfcRegistrada, c.tienda, regresa = true)
            CajaInfo(if (ocupado) tx.confirmandoPago else tx.leyendoEnVozAlta, Iconos.Volumen, Tono.LAVANDA, onClick = { leerDetalles() })
            aviso?.let { CajaInfo(it, Iconos.Alerta, Tono.ERROR) }
            Text(tx.aunNoConfirmado, fontSize = 12.sp, color = Paleta.TextoSuave)
            CuentaRegresiva(limiteMs, ::vencido)
        }
        BotonSecundario(tx.cancelar, ::cancelar, enabled = !ocupado)
    }

    // ---- Procesando ----

    @Composable
    private fun ColumnScope.PantallaPagando() {
        val tx = LocalTextos.current
        val c = cobro
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(12.dp))
            IconoResultado(null, cargando = true)
            Titulo(tx.procesandoCobro, tamano = 22.sp, centrado = true)
            c?.let {
                Subtitulo(tx.confirmandoDe(it.cliente ?: tx.cliente, it.tienda), centrado = true)
                MontoGrande(MontoUtil.corto(it.montoValor, it.assetCode, it.assetScale, tx.ingles), tx.nombreMoneda(it.assetCode), tamano = 34.sp, centrado = true)
            }
            BarraProgreso()
            Text(tx.confirmandoOperacion, fontSize = 12.sp, color = Paleta.TextoSuave, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        CajaInfo(tx.esperaResultado, Iconos.EscudoCheck)
    }

    // ---- Pago recibido ----

    @Composable
    private fun ColumnScope.PantallaPagado() {
        val tx = LocalTextos.current
        val c = cobro ?: return
        val hoy = historial.datos?.let { h ->
            val ventas = Ventas.desdeHistorial(h.movimientos).filter { it.assetCode == c.assetCode }
            val serie = Ventas.serie(ventas, c.assetCode, Periodo.DIAS7, System.currentTimeMillis())
            serie.barras.last()
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(8.dp))
            IconoResultado(Iconos.Check)
            Titulo(tx.pagoRecibido, tamano = 22.sp, centrado = true)
            Subtitulo(tx.unaVentaMas(c.tienda), centrado = true)
            MontoGrande(MontoUtil.corto(c.montoValor, c.assetCode, c.assetScale, tx.ingles), tx.nombreMoneda(c.assetCode), tamano = 34.sp, centrado = true)
            TarjetaBlanca {
                FilaDato(tx.negocio, c.tienda)
                FilaDato(tx.cliente, c.cliente ?: "—")
                hoy?.let { FilaDato(tx.ventasDeHoy, MontoUtil.conCodigo(it.total, c.assetCode, c.assetScale, tx.ingles)) }
            }
            hoy?.let { CajaInfo("${tx.ventasHoy(it.cantidad)} · ${tx.fechaCorta(pagadoEn, conAnio = true)}, ${tx.hora(pagadoEn)}", Iconos.Check, Tono.EXITO) }
        }
        BotonPrimario(tx.nuevoCobro, ::irAMarcar)
        BotonSecundario(tx.verComprobante, { comprobanteDelCobro()?.let { verComprobante(it, PasoCobro.PAGADO) } })
    }

    // ---- Errores ----

    @Composable
    private fun ColumnScope.PantallaError() {
        val tx = LocalTextos.current
        val err = error ?: return
        val c = cobro
        val celular = err.datos?.optString("medioTipo") == "celular"
        val vigente = c != null && limiteMs - System.currentTimeMillis() > 5_000
        val puedeReintentar = err.codigo in ERRORES_DE_TARJETA && vigente
        val desconocido = err.codigo == "RESULTADO_DESCONOCIDO"
        val icono = when (err.codigo) {
            "TARJETA_NO_REGISTRADA", "MEDIO_BLOQUEADO" -> if (celular) Iconos.Celular else Iconos.Tarjeta
            "COBRO_VENCIDO" -> Iconos.Reloj
            "TOPE_SUPERADO", "SIN_PERMISO" -> Iconos.Escudo
            "RESULTADO_DESCONOCIDO" -> Iconos.Reloj
            else -> Iconos.Alerta
        }
        val propio = err.codigo in setOf("TARJETA_NO_REGISTRADA", "MEDIO_BLOQUEADO", "COBRO_VENCIDO", "TOPE_SUPERADO", "SIN_PERMISO", "RESULTADO_DESCONOCIDO")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(8.dp))
            IconoResultado(icono, error = !propio)
            Titulo(tx.tituloError(err.codigo, celular), tamano = 22.sp, centrado = true)
            Subtitulo(tx.detalleError(err.codigo, celular, restanteTexto(err.datos)) ?: tx.mensajeError(err.codigo, err.detalle), centrado = true)
            c?.let {
                MontoGrande(MontoUtil.corto(it.montoValor, it.assetCode, it.assetScale, tx.ingles), tx.nombreMoneda(it.assetCode), tamano = 34.sp, centrado = true)
                TarjetaBlanca { FilaDato(tx.negocio, it.tienda) }
            }
            CajaInfo(tx.notaError(err.codigo), if (desconocido) Iconos.Reloj else Iconos.Info)
        }
        when {
            desconocido -> {
                BotonPrimario(tx.consultarDeNuevo, ::consultarDeNuevo)
                BotonSecundario(tx.volverAlInicio, ::nuevoCobro)
            }
            err.codigo == "COBRO_VENCIDO" -> {
                BotonPrimario(tx.crearNuevoCobro, ::irAMarcar)
                BotonSecundario(tx.volverAlInicio, ::nuevoCobro)
            }
            puedeReintentar -> {
                BotonPrimario(if (err.codigo == "TOPE_SUPERADO" || err.codigo == "SIN_PERMISO") tx.usarOtroMedio else tx.usarOtraTarjeta, {
                    error = null
                    aviso = null
                    paso = PasoCobro.ACERQUE
                })
                BotonSecundario(tx.cancelar, ::cancelar)
            }
            else -> {
                // Volver a intentar: mismo monto, cobro nuevo
                BotonPrimario(tx.volverAIntentar, {
                    val monto = entrada
                    nuevoCobro()
                    entrada = monto
                    paso = PasoCobro.MARCAR
                })
                BotonSecundario(tx.cancelarCobro, ::cancelar)
            }
        }
    }

    // ---- Comprobante ----

    @Composable
    private fun ColumnScope.PantallaComprobante(c: Comprobante) {
        val tx = LocalTextos.current
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Titulo(tx.comprobante)
            TarjetaBlanca {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Iconos.CirculoCheck, null, tint = Paleta.Exito, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tx.confirmado, fontSize = 12.sp, color = Paleta.Exito, fontWeight = FontWeight.Medium)
                }
                Text(tx.pagoRecibido, fontSize = 12.sp, color = Paleta.TextoSuave)
                MontoGrande(MontoUtil.corto(c.valor, c.assetCode, c.assetScale, tx.ingles), tx.nombreMoneda(c.assetCode), tamano = 32.sp)
                Separador()
                FilaDato(tx.recibidoDe, c.de ?: "—")
                FilaDato(tx.recibidoEn, c.en)
                FilaDato(tx.fecha, if (c.fechaMs > 0) tx.fechaCorta(c.fechaMs, conAnio = true) else "—")
                FilaDato(tx.horaEtiqueta, if (c.fechaMs > 0) tx.hora(c.fechaMs) else "—")
                FilaDato(tx.referencia, c.referencia)
                FilaDato(tx.medio, c.medio ?: "—")
            }
            CajaInfo(tx.guardaReferencia, Iconos.Documento)
        }
        BotonPrimario(tx.nuevoCobro, ::irAMarcar)
        BotonSecundario(tx.volverAlInicio, ::nuevoCobro)
    }

    // ---- Registrar tarjeta de un cliente ----

    @Composable
    fun ColumnScope.PantallaRegistrar() {
        val tx = LocalTextos.current
        val t = tarjetaRegistro
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Ceja(tx.registrarTarjeta)
                Titulo(if (t == null) tx.acercaTarjeta else tx.eligeCuenta)
                Subtitulo(tx.registrarDetalle)
            }
            if (t == null) {
                IlustracionToque(tx.billetera, tx.tarjetaNfc, tx.sinRegistrar, tx.miNegocio, regresa = false)
            } else {
                TarjetaBlanca {
                    FilaDato(if (t.esCelular) tx.celular else tx.tarjetaNfc, t.uid)
                    FilaDato(tx.walletEnTarjeta, t.wallet ?: tx.ninguna)
                }
                if (cuentas.isEmpty()) CajaInfo(tx.sinCuentas)
                cuentas.forEach { c ->
                    val elegido = cuentaElegida?.id == c.id
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (elegido) Paleta.LimaFondo else Paleta.Superficie)
                            .border(1.dp, if (elegido) Paleta.Verde else Paleta.Borde, RoundedCornerShape(12.dp))
                            .clickable(role = Role.RadioButton) { cuentaElegida = c }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconoCuadro(Iconos.Persona)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.nombre, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Paleta.Texto)
                            Text(if (c.tienePermiso) tx.conPermiso else tx.sinPermisoTodavia, fontSize = 12.sp, color = if (c.tienePermiso) Paleta.Exito else Paleta.Error)
                        }
                        if (elegido) Icon(Iconos.Check, null, tint = Paleta.Verde, modifier = Modifier.size(18.dp))
                    }
                }
            }
            mensajeRegistro?.let { (tono, texto) -> CajaInfo(texto, if (tono == Tono.EXITO) Iconos.Check else Iconos.Info, tono) }
            if (esperandoEscritura) CajaInfo(tx.acercaParaEscribir, Iconos.Contactless, Tono.LAVANDA)
        }
        BotonPrimario(tx.guardar, ::guardarTarjeta, enabled = t != null && !t.esCelular && cuentaElegida != null && !ocupado)
        if (t != null && t.admiteNdef && !t.esCelular) {
            BotonSecundario(tx.escribirWallet, {
                val c = cuentaElegida ?: return@BotonSecundario
                uidPorEscribir = t.uid
                walletPorEscribir = c.walletAddress
                esperandoEscritura = true
                mensajeRegistro = null
            }, enabled = cuentaElegida != null && !esperandoEscritura)
        }
    }

    // ---- Diagnóstico ----

    @Composable
    fun ColumnScope.PantallaLeer() {
        val tx = LocalTextos.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Titulo(tx.diagnostico, tamano = 20.sp)
                Subtitulo(tx.diagnosticoDetalle(lecturas.size))
            }
            TextButton(onClick = { lecturas.clear() }) { Text(tx.limpiar, color = Paleta.Verde) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lecturas) { TarjetaLectura(it) }
        }
    }
}
