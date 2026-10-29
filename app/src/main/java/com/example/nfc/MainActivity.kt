package com.example.nfc

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

enum class Seccion { INICIO, COBRAR, HISTORIAL, TARJETAS, REGISTRAR, AJUSTES, PERMISO, AGREGAR, LEER, CUENTAS, NUEVA_CUENTA }

/** Pantalla principal de cada modo. */
private fun inicioDe(modo: Modo) = if (modo == Modo.PAGAR) Seccion.INICIO else Seccion.COBRAR

/**
 * Una sola app con dos modos: Pagar (el celular es la tarjeta, HCE activo) y
 * Mi negocio (datáfono, reader mode activo). Cada cuenta puede hacer las dos cosas.
 */
class MainActivity : ComponentActivity(), Entorno, NfcAdapter.ReaderCallback, TextToSpeech.OnInitListener {

    private var adaptador: NfcAdapter? = null
    private var cardEmulation: CardEmulation? = null
    private var tieneHce = false
    private var tts: TextToSpeech? = null
    private var ttsListo = false
    private var reanudada = false
    private val componenteHce by lazy { ComponentName(this, PagoHceService::class.java) }

    // ---- Estado general ----
    private var cuenta by mutableStateOf<Cuenta?>(null)
    private var sinCuenta by mutableStateOf(false)
    private var modo by mutableStateOf(Modo.PAGAR)
    private var seccion by mutableStateOf(Seccion.INICIO)
    private var nfcEncendido by mutableStateOf(true)
    private var hayConexion by mutableStateOf(true)
    private var avisoGeneral by mutableStateOf<String?>(null)
    private var ocupado by mutableStateOf(false)
    private var vozActiva by mutableStateOf(true)
    private var idioma by mutableStateOf(Idioma.ES)

    // Cambio de cuenta: cuentas del servidor y, sin cuenta en este celular, si se está eligiendo una existente
    private val cuentasDisponibles = androidx.compose.runtime.mutableStateListOf<Cuenta>()
    private var cargandoCuentas by mutableStateOf(false)
    private var eligiendoCuenta by mutableStateOf(false)

    // Copias para el hilo del reader mode
    @Volatile private var seccionNfc = Seccion.INICIO
    @Volatile private var modoNfc = Modo.PAGAR

    private val historial = PestanaHistorial(this)
    private val cobrador = ModoCobrar(this, historial)
    private val pagador = ModoPagar(this)

    // Confirmación con el bloqueo de pantalla en Android < 9 (sin BiometricPrompt)
    private var alAutenticar: (() -> Unit)? = null
    private val lanzadorCredencial = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) alAutenticar?.invoke() else hablar(tx.noAutorizado)
        alAutenticar = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adaptador = NfcAdapter.getDefaultAdapter(this)
        tieneHce = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
        if (adaptador != null && tieneHce) cardEmulation = CardEmulation.getInstance(adaptador)
        idioma = Estado.idioma(this)
        tts = TextToSpeech(this, this)
        // Un datáfono (o un celular listo para pagar) debe quedarse con la pantalla encendida
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vozActiva = Estado.vozActiva(this)
        modo = Estado.modo(this)
        modoNfc = modo
        seccion = inicioDe(modo)
        seccionNfc = seccion

        if (Estado.tieneCuenta(this)) cargarCuenta() else sinCuenta = true

        // El servicio HCE avisa los toques: con autorización se adelanta la consulta del cobro; sin ella se pide la huella
        lifecycleScope.launch {
            Estado.ultimoToque.drop(1).collect {
                delay(800)
                if (modo == Modo.PAGAR) pagador.consultarPendiente()
            }
        }
        lifecycleScope.launch {
            Estado.toqueSinAutorizar.drop(1).collect {
                if (reanudada && modo == Modo.PAGAR && cuenta != null) pagador.alToqueSinAutorizar()
            }
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        setContent {
            CompositionLocalProvider(LocalTextos provides Textos(idioma)) {
                TemaPagoAlToque {
                    Scaffold(Modifier.fillMaxSize(), containerColor = Paleta.Fondo) { padding ->
                        Box(Modifier.padding(padding).fillMaxSize()) {
                            Column(
                                Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (cuenta == null) {
                                    Encabezado(null, alVolver = volverDisponible())
                                    Avisos()
                                    when {
                                        !sinCuenta -> Cargando()
                                        eligiendoCuenta -> PantallaCuentas()
                                        else -> PantallaCrearCuenta()
                                    }
                                } else {
                                    Encabezado(etiquetaEncabezado(), alVolver = volverDisponible())
                                    Avisos()
                                    Contenido()
                                }
                            }
                            if (modo == Modo.PAGAR) pagador.pago?.let { pagador.OverlayPago(it, cuenta?.nombre ?: "") }
                        }
                        BackHandler(enabled = volverDisponible() != null) { volverDisponible()?.invoke() }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reanudada = true
        nfcEncendido = adaptador?.isEnabled == true
        aplicarModoNfc()
        if (cuenta != null) {
            irA(seccion) // refresca los datos de la pantalla (p. ej. al volver de aprobar el permiso)
            if (modo == Modo.PAGAR) pagador.iniciarSondeo()
        }
    }

    override fun onPause() {
        reanudada = false
        pagador.detenerSondeo()
        adaptador?.disableReaderMode(this)
        cardEmulation?.unsetPreferredService(this)
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------------- Modo y NFC ----------------

    /**
     * Mi negocio: reader mode activo y sin HCE preferido (el servicio además responde 6A 82 fuera de Pagar).
     * Pagar: HCE preferido y reader mode apagado, salvo en "Agregar tarjeta" y en el diagnóstico.
     */
    private fun aplicarModoNfc() {
        modoNfc = modo
        seccionNfc = seccion
        val a = adaptador ?: return
        if (!reanudada) return
        val lector = modo == Modo.COBRAR || seccion == Seccion.AGREGAR || seccion == Seccion.LEER
        if (lector) {
            cardEmulation?.unsetPreferredService(this)
            if (a.isEnabled) {
                a.enableReaderMode(
                    this, this,
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V,
                    null
                )
            }
        } else {
            a.disableReaderMode(this)
            val ok = cardEmulation?.setPreferredService(this, componenteHce) ?: false
            Log.d(TAG_LOG, "Modo Pagar: setPreferredService = $ok")
        }
    }

    private fun cambiarModo(nuevo: Modo) {
        if (nuevo == modo || cobrador.enCurso) return
        modo = nuevo
        Estado.setModo(this, nuevo)
        if (nuevo == Modo.COBRAR) {
            pagador.revocar()
            pagador.detenerSondeo()
        } else if (reanudada) {
            pagador.iniciarSondeo()
        }
        irA(inicioDe(nuevo))
        hablar(if (nuevo == Modo.PAGAR) tx.vozModoPagar else tx.vozModoNegocio)
    }

    private fun irA(s: Seccion) {
        seccion = s
        avisoGeneral = null
        when (s) {
            Seccion.INICIO -> { pagador.cargarResumen(); historial.cargar() }
            Seccion.COBRAR -> cobrador.cargarConfig()
            Seccion.HISTORIAL -> historial.cargar()
            Seccion.TARJETAS -> pagador.abrirTarjetas()
            Seccion.REGISTRAR -> cobrador.abrirRegistrar()
            Seccion.PERMISO -> pagador.abrirPermiso()
            Seccion.AGREGAR -> pagador.abrirAgregar()
            Seccion.CUENTAS -> cargarCuentas()
            Seccion.AJUSTES, Seccion.LEER, Seccion.NUEVA_CUENTA -> {}
        }
        aplicarModoNfc()
    }

    /** Acción de la flecha "volver" (y del botón atrás) en la pantalla actual, o null si no hay a dónde volver. */
    private fun volverDisponible(): (() -> Unit)? = when {
        cuenta == null -> if (eligiendoCuenta) { { eligiendoCuenta = false } } else null
        seccion == Seccion.AGREGAR -> { { irA(Seccion.TARJETAS) } }
        seccion == Seccion.NUEVA_CUENTA -> { { irA(Seccion.CUENTAS) } }
        seccion == Seccion.LEER -> { { irA(Seccion.AJUSTES) } }
        seccion != inicioDe(modo) -> { { irA(inicioDe(modo)) } }
        seccion == Seccion.COBRAR && cobrador.paso in setOf(PasoCobro.MARCAR, PasoCobro.COMPROBANTE) -> { { cobrador.atras() } }
        else -> null
    }

    private fun etiquetaEncabezado(): String {
        val c = cuenta ?: return ""
        return when (seccion) {
            Seccion.INICIO -> c.nombre
            Seccion.COBRAR -> cobrador.etiquetaEncabezado(c.nombre)
            Seccion.REGISTRAR -> tx.miNegocio
            Seccion.AJUSTES, Seccion.LEER -> tx.ajustes
            Seccion.CUENTAS, Seccion.NUEVA_CUENTA -> c.nombre
            else -> tx.pagar
        }
    }

    // Se ejecuta en un hilo del sistema NFC
    override fun onTagDiscovered(tag: Tag) {
        val postear: (() -> Unit) -> Unit = { f -> runOnUiThread(f) }
        when {
            seccionNfc == Seccion.LEER -> cobrador.alLeerDiagnostico(tag, postear)
            seccionNfc == Seccion.AGREGAR -> pagador.alLeerTarjeta(tag, postear)
            modoNfc == Modo.COBRAR -> cobrador.alLeerTag(tag, registrando = seccionNfc == Seccion.REGISTRAR, postear)
        }
    }

    // ---------------- Cuenta ----------------

    private fun cargarCuenta() {
        val id = Estado.cuentaId(this) ?: return
        lifecycleScope.launch {
            try {
                val c = ServidorApi.cuenta(id)
                Estado.setCuentaNombre(this@MainActivity, c.nombre)
                cuenta = c
                hayConexion = true
                revincular()
                irA(seccion)
                if (modo == Modo.PAGAR && reanudada) pagador.iniciarSondeo()
            } catch (ex: ErrorServidor) {
                if (ex.codigo == "CUENTA_NO_ENCONTRADA") {
                    Estado.cerrarCuenta(this@MainActivity)
                    sinCuenta = true
                } else {
                    hayConexion = ex.codigo != "SIN_CONEXION"
                    avisoGeneral = tx.mensajeError(ex.codigo, ex.message)
                }
            }
        }
    }

    private fun crearCuenta(nombre: String, wallet: String, negocio: String) {
        ocupado = true
        lifecycleScope.launch {
            try {
                // Negocio vacío = no tocarlo (si la cuenta ya existía conserva el suyo)
                val c = ServidorApi.crearCuenta(nombre.trim(), wallet.trim(), negocio.trim().ifBlank { null })
                val dispositivo = Estado.idParaVincular(this@MainActivity)
                ServidorApi.vincular(dispositivo, c.id, nombreCelular())
                Estado.guardarCuenta(this@MainActivity, c.id, c.nombre, c.walletAddress)
                limpiarEstadoCuenta()
                cuenta = c
                sinCuenta = false
                eligiendoCuenta = false
                hayConexion = true
                hablar(if (c.nueva) tx.vozCuentaCreada(c.nombre) else tx.vozBienvenido(c.nombre))
                vibrarLargo()
                irA(inicioDe(modo))
                if (modo == Modo.PAGAR) pagador.iniciarSondeo()
            } catch (ex: ErrorServidor) {
                avisoGeneral = tx.mensajeError(ex.codigo, ex.message)
                hayConexion = ex.codigo != "SIN_CONEXION"
                vibrarError()
                hablar(avisoGeneral ?: "")
            } finally {
                ocupado = false
            }
        }
    }

    private fun guardarAjustes(nombre: String, negocio: String) {
        val id = cuentaId() ?: return
        ocupado = true
        lifecycleScope.launch {
            try {
                val c = ServidorApi.editarCuenta(id, nombre.trim().ifEmpty { null }, negocio.trim())
                cuenta = c
                Estado.setCuentaNombre(this@MainActivity, c.nombre)
                avisoGeneral = "✓ " + tx.guardadoCobraComo(c.negocioTexto)
                hablar(tx.guardado)
            } catch (ex: ErrorServidor) {
                avisoGeneral = tx.mensajeError(ex.codigo, ex.message)
            } finally {
                ocupado = false
            }
        }
    }

    private fun cerrarCuentaEnCelular() {
        lifecycleScope.launch {
            dispositivoId()?.let { runCatching { ServidorApi.desvincular(it) } }
            pagador.revocar()
            pagador.detenerSondeo()
            Estado.cerrarCuenta(this@MainActivity)
            cuenta = null
            sinCuenta = true
            hablar(tx.cuentaCerrada)
        }
    }

    private fun cargarCuentas() {
        cargandoCuentas = true
        lifecycleScope.launch {
            try {
                val lista = ServidorApi.cuentas()
                cuentasDisponibles.clear()
                cuentasDisponibles.addAll(lista)
                hayConexion = true
                avisoGeneral = null
            } catch (ex: ErrorServidor) {
                hayConexion = ex.codigo != "SIN_CONEXION"
                if (ex.codigo != "SIN_CONEXION") avisoGeneral = tx.mensajeError(ex.codigo, ex.message)
            } finally {
                cargandoCuentas = false
            }
        }
    }

    /** Olvida los datos de la cuenta anterior (permiso, cobro en curso, historial) antes de mostrar otra. */
    private fun limpiarEstadoCuenta() {
        pagador.revocar()
        pagador.limpiar()
        cobrador.limpiar()
        historial.limpiar()
    }

    /**
     * Vincula este celular a otra cuenta existente. El servidor mueve el medio "CEL:<id>" a esa cuenta,
     * así que desde ahora el celular paga y cobra con ella. Prototipo sin inicio de sesión.
     */
    private fun cambiarCuenta(nueva: Cuenta) {
        if (ocupado) return
        if (nueva.id == cuenta?.id) { irA(inicioDe(modo)); return }
        ocupado = true
        lifecycleScope.launch {
            try {
                val dispositivo = Estado.idParaVincular(this@MainActivity)
                ServidorApi.vincular(dispositivo, nueva.id, nombreCelular())
                Estado.guardarCuenta(this@MainActivity, nueva.id, nueva.nombre, nueva.walletAddress)
                limpiarEstadoCuenta()
                cuenta = nueva
                sinCuenta = false
                eligiendoCuenta = false
                hayConexion = true
                hablar(tx.vozCuentaDe(nueva.nombre))
                vibrarCorto()
                irA(inicioDe(modo))
                if (modo == Modo.PAGAR && reanudada) pagador.iniciarSondeo()
            } catch (ex: ErrorServidor) {
                hayConexion = ex.codigo != "SIN_CONEXION"
                avisoGeneral = tx.mensajeError(ex.codigo, ex.message)
                vibrarError()
            } finally {
                ocupado = false
            }
        }
    }

    private fun cambiarIdioma(nuevo: Idioma) {
        if (nuevo == idioma) return
        idioma = nuevo
        Estado.setIdioma(this, nuevo)
        configurarVoz()
        hablar(tx.vozIdioma)
    }

    private fun nombreCelular() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    // ---------------- Entorno ----------------

    override val scope: CoroutineScope get() = lifecycleScope
    override val tx: Textos get() = Textos(idioma)
    override fun cuentaId(): String? = Estado.cuentaId(this)
    override fun dispositivoId(): String? = Estado.dispositivoId(this)
    override fun sinConexion(sin: Boolean) { hayConexion = !sin }

    override fun revincular() {
        val id = cuentaId() ?: return
        val dispositivo = Estado.idParaVincular(this)
        lifecycleScope.launch { runCatching { ServidorApi.vincular(dispositivo, id, nombreCelular()) } }
    }

    override fun abrirEnlace(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    override fun copiar(texto: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Link", texto))
    }

    private fun bloqueoConfigurado() = getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Huella o bloqueo de pantalla (BIOMETRIC_STRONG o DEVICE_CREDENTIAL). Sin dependencias: API del sistema. */
    override fun autenticar(titulo: String, alExito: () -> Unit) {
        if (!bloqueoConfigurado()) {
            avisoGeneral = tx.configureBloqueo
            hablar(tx.configureBloqueo)
            vibrarError()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val b = BiometricPrompt.Builder(this)
                .setTitle(titulo)
                .setSubtitle(tx.useHuella)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                b.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            } else {
                @Suppress("DEPRECATION")
                b.setDeviceCredentialAllowed(true)
            }
            b.build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    Log.d(TAG_LOG, "Autenticación correcta")
                    alExito()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    Log.d(TAG_LOG, "Autenticación cancelada o fallida ($errorCode): $errString")
                    hablar(tx.noAutorizado)
                }
            })
        } else {
            val km = getSystemService(KeyguardManager::class.java)
            @Suppress("DEPRECATION")
            val intent = km?.createConfirmDeviceCredentialIntent(titulo, tx.useBloqueo) ?: return
            alAutenticar = alExito
            lanzadorCredencial.launch(intent)
        }
    }

    // ---------------- Voz ----------------

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val t = tts ?: return
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { esperasVoz.remove(utteranceId)?.invoke() }
            @Deprecated("Obsoleto en la API de Android")
            override fun onError(utteranceId: String?) { esperasVoz.remove(utteranceId)?.invoke() }
            override fun onError(utteranceId: String?, errorCode: Int) { esperasVoz.remove(utteranceId)?.invoke() }
        })
        configurarVoz()
    }

    /** Idioma de la voz según la app y, si el motor la tiene instalada, una voz femenina. */
    private fun configurarVoz() {
        val t = tts ?: return
        val (lengua, paises) = if (idioma == Idioma.EN) "en" to listOf("en-US", "en-GB") else "es" to listOf("es-CO", "es-US", "es-MX", "es-ES")
        ttsListo = paises.any { t.setLanguage(Locale.forLanguageTag(it)) >= TextToSpeech.LANG_COUNTRY_AVAILABLE } ||
            t.setLanguage(Locale.forLanguageTag(lengua)) >= TextToSpeech.LANG_AVAILABLE
        val voces = runCatching { t.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == lengua && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
        Log.d(TAG_LOG, "Voces $lengua: ${voces.joinToString { "${it.name}${if (it.isNetworkConnectionRequired) "(red)" else ""}" }}")
        val femenina = VozFemenina.elegir(voces.map { VozFemenina.Candidata(it.name, it.locale.toLanguageTag(), it.isNetworkConnectionRequired, it.quality) }, paises)
        voces.firstOrNull { it.name == femenina }?.let { v: Voice -> t.voice = v }
        Log.d(TAG_LOG, "TextToSpeech listo=$ttsListo voz=${t.voice?.name} (${t.voice?.locale})")
    }

    override fun hablar(texto: String) {
        if (ttsListo && vozActiva && texto.isNotBlank()) tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "app")
    }

    override fun callar() {
        tts?.stop()
    }

    // Avisos pendientes de "terminó de hablar", por id de enunciado (llegan en otro hilo)
    private val esperasVoz = ConcurrentHashMap<String, () -> Unit>()

    /** Habla y espera a que termine (máximo 8 s, por si el motor de voz no responde). */
    override suspend fun hablarYEsperar(texto: String) {
        val t = tts ?: return
        if (!ttsListo || !vozActiva) return
        withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                val id = "espera-${System.nanoTime()}"
                esperasVoz[id] = { if (cont.isActive) cont.resume(Unit) }
                cont.invokeOnCancellation { esperasVoz.remove(id) }
                t.speak(texto, TextToSpeech.QUEUE_FLUSH, null, id)
            }
        }
    }

    override fun vibrar(patron: LongArray) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(patron, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(patron, -1)
        }
    }

    // ---------------- Interfaz ----------------

    @Composable
    private fun Avisos() {
        val tx = LocalTextos.current
        if (adaptador == null) CajaInfo(tx.sinNfc, Iconos.Alerta, Tono.ERROR)
        else if (!nfcEncendido) CajaInfo(tx.nfcApagado, Iconos.Contactless, Tono.ERROR) { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
        if (!hayConexion) {
            CajaInfo(tx.sinConexionReintentar, Iconos.Alerta, Tono.ERROR) {
                if (cuenta == null && !sinCuenta) cargarCuenta() else irA(seccion)
            }
        }
    }

    @Composable
    private fun Selector() = SelectorModo(modo, ::cambiarModo)

    @Composable
    private fun OpcionesPagar() {
        val tx = LocalTextos.current
        Column {
            Text(tx.masOpciones, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Paleta.Texto)
            FilaOpcion(Iconos.Tarjeta, tx.misTarjetas, tx.misTarjetasCorto) { irA(Seccion.TARJETAS) }
            FilaOpcion(Iconos.EscudoCheck, tx.miPermiso, tx.miPermisoCorto) { irA(Seccion.PERMISO) }
            FilaOpcion(Iconos.Persona, tx.cambiarCuenta, tx.cuentaActual(cuenta?.nombre ?: "")) { irA(Seccion.CUENTAS) }
            FilaOpcion(Iconos.Ajustes, tx.ajustes, tx.ajustesCorto) { irA(Seccion.AJUSTES) }
        }
    }

    @Composable
    private fun OpcionesNegocio() {
        val tx = LocalTextos.current
        Column {
            Text(tx.masOpciones, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Paleta.Texto)
            FilaOpcion(Iconos.Tarjeta, tx.registrarTarjeta, tx.registrarCorto) { irA(Seccion.REGISTRAR) }
            FilaOpcion(Iconos.Persona, tx.cambiarCuenta, tx.cuentaActual(cuenta?.nombre ?: "")) { irA(Seccion.CUENTAS) }
            FilaOpcion(Iconos.Ajustes, tx.ajustes, tx.ajustesCorto) { irA(Seccion.AJUSTES) }
        }
    }

    @Composable
    private fun ColumnScope.Contenido() {
        val c = cuenta ?: return
        when (seccion) {
            Seccion.INICIO -> with(pagador) {
                PantallaInicio(
                    c.nombre, historial.datos, adaptador != null && tieneHce,
                    selector = { Selector() }, irPermiso = { irA(Seccion.PERMISO) }, irMovimientos = { irA(Seccion.HISTORIAL) },
                    opciones = { OpcionesPagar() }
                )
            }
            Seccion.HISTORIAL -> with(pagador) { PantallaMovimientos(historial.datos, historial.error) { historial.cargar() } }
            Seccion.PERMISO -> with(pagador) { PantallaPermiso() }
            Seccion.TARJETAS -> with(pagador) { PantallaTarjetas(irAgregar = { irA(Seccion.AGREGAR) }) }
            Seccion.AGREGAR -> with(pagador) { PantallaAgregar(volver = { irA(Seccion.TARJETAS) }) }
            Seccion.COBRAR -> with(cobrador) { PantallaCobrar(c.nombre, c, selector = { Selector() }, opciones = { OpcionesNegocio() }) }
            Seccion.REGISTRAR -> with(cobrador) { PantallaRegistrar() }
            Seccion.LEER -> with(cobrador) { PantallaLeer() }
            Seccion.AJUSTES -> PantallaAjustes()
            Seccion.CUENTAS -> PantallaCuentas()
            Seccion.NUEVA_CUENTA -> PantallaCrearCuenta()
        }
    }

    @Composable
    private fun ColumnScope.PantallaCrearCuenta() {
        val tx = LocalTextos.current
        var nombre by remember { mutableStateOf("") }
        var wallet by remember { mutableStateOf("https://ilp.interledger-test.dev/") }
        var negocio by remember { mutableStateOf("") }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Ceja(tx.bienvenida)
            Titulo(tx.crearCuenta)
            Subtitulo(tx.crearCuentaDetalle)
            avisoGeneral?.let { CajaInfo(it, Iconos.Alerta, Tono.ERROR) }
            CampoTexto(nombre, { nombre = it }, tx.tuNombre)
            CampoTexto(wallet, { wallet = it }, tx.walletAddress, KeyboardType.Uri)
            CampoTexto(negocio, { negocio = it }, tx.nombreNegocioOpcional)
            if (cuenta == null) Segmentado(listOf(Idioma.ES to "Español", Idioma.EN to "English"), idioma, ::cambiarIdioma)
            CajaInfo(tx.recuperarCuenta)
            if (cuenta == null) {
                BotonSecundario(tx.usarCuentaExistente, { eligiendoCuenta = true; cargarCuentas() }, icono = Iconos.Persona)
            }
        }
        BotonPrimario(
            if (ocupado) tx.revisandoWallet else tx.crearCuenta, { crearCuenta(nombre, wallet, negocio) },
            enabled = !ocupado && nombre.isNotBlank() && wallet.trim().length > "https://".length
        )
    }

    /** Cuentas del servidor para usar en este celular (y acceso a crear o recuperar otra). */
    @Composable
    private fun ColumnScope.PantallaCuentas() {
        val tx = LocalTextos.current
        val actual = cuenta?.id
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Titulo(tx.cambiarCuenta)
            Subtitulo(tx.cuentasDetalle)
            avisoGeneral?.let { CajaInfo(it, Iconos.Alerta, Tono.ERROR) }
            if (cargandoCuentas && cuentasDisponibles.isEmpty()) Cargando()
            if (!cargandoCuentas && cuentasDisponibles.isEmpty() && hayConexion) CajaInfo(tx.sinCuentas)
            cuentasDisponibles.forEach { c ->
                val elegida = c.id == actual
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (elegida) Paleta.LimaFondo else Paleta.Superficie)
                        .border(1.dp, if (elegida) Paleta.Verde else Paleta.Borde, RoundedCornerShape(12.dp))
                        .clickable(enabled = !ocupado, role = Role.RadioButton) { cambiarCuenta(c) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconoCuadro(Iconos.Persona, if (elegida) Paleta.Lima else Paleta.Neutro)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.nombre, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Paleta.Texto)
                        Text("${c.negocioTexto} · ${c.walletAddress.removePrefix("https://")}", fontSize = 12.sp, color = Paleta.TextoSuave, maxLines = 1)
                        Text(if (c.tienePermiso) tx.conPermiso else tx.sinPermisoTodavia, fontSize = 11.sp,
                            color = if (c.tienePermiso) Paleta.Exito else Paleta.TextoSuave)
                    }
                    if (elegida) Text(tx.enUso, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Paleta.Verde)
                    else Icon(Iconos.Derecha, null, tint = Paleta.TextoSuave, modifier = Modifier.size(18.dp))
                }
            }
            CajaInfo(tx.cambiarCuentaAviso)
        }
        BotonSecundario(tx.agregarOtraCuenta, {
            if (cuenta == null) eligiendoCuenta = false else irA(Seccion.NUEVA_CUENTA)
        }, icono = Iconos.Mas)
    }

    @Composable
    private fun ColumnScope.PantallaAjustes() {
        val tx = LocalTextos.current
        val c = cuenta ?: return
        var nombre by remember(c.id) { mutableStateOf(c.nombre) }
        var negocio by remember(c.id) { mutableStateOf(c.negocio ?: "") }
        var confirmar by remember { mutableStateOf(false) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Titulo(tx.ajustes)
            avisoGeneral?.let { CajaInfo(it, if (it.startsWith("✓")) Iconos.Check else Iconos.Alerta, if (it.startsWith("✓")) Tono.EXITO else Tono.ERROR) }
            TarjetaBlanca {
                Text(tx.idiomaEtiqueta, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Paleta.Texto)
                Segmentado(listOf(Idioma.ES to "Español", Idioma.EN to "English"), idioma, ::cambiarIdioma)
                Separador()
                Interruptor(tx.voz, tx.vozDetalle, vozActiva) {
                    vozActiva = it
                    Estado.setVozActiva(this@MainActivity, it)
                    if (it) hablar(tx.vozActivada)
                }
            }
            TarjetaBlanca {
                FilaDato(tx.wallet, c.walletAddress.removePrefix("https://"))
                FilaDato(tx.moneda, c.assetCode ?: "—")
                FilaDato(tx.esteCelular, nombreCelular())
            }
            CampoTexto(nombre, { nombre = it }, tx.tuNombre)
            CampoTexto(negocio, { negocio = it }, tx.nombreNegocioVacio(c.negocioTexto.takeIf { c.negocio == null }))
            BotonPrimario(tx.guardar, { guardarAjustes(nombre, negocio) }, enabled = !ocupado)
            if (bloqueoConfigurado()) CajaInfo(tx.bloqueoOk, Iconos.Candado, Tono.EXITO)
            else CajaInfo(tx.configureBloqueo, Iconos.Candado, Tono.ERROR)
            CajaInfo(tx.prototipoSinLogin)
            BotonSecundario(tx.cambiarCuenta, { irA(Seccion.CUENTAS) }, icono = Iconos.Persona)
            BotonSecundario(tx.leerEtiquetas, { irA(Seccion.LEER) }, icono = Iconos.Contactless)
        }
        BotonSecundario(tx.cerrarCuenta, { confirmar = true }, color = Paleta.Error)
        if (confirmar) {
            AlertDialog(
                onDismissRequest = { confirmar = false }, containerColor = Paleta.Superficie,
                title = { Text(tx.cerrarCuentaPregunta, fontSize = 18.sp) },
                text = { Text(tx.cerrarCuentaDetalle, fontSize = 14.sp) },
                confirmButton = { TextButton(onClick = { confirmar = false; cerrarCuentaEnCelular() }) { Text(tx.cerrar, color = Paleta.Error) } },
                dismissButton = { TextButton(onClick = { confirmar = false }) { Text(tx.cancelar, color = Paleta.TextoSuave) } }
            )
        }
    }
}
