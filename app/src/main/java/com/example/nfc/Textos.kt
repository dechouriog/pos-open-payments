package com.example.nfc

import java.util.Calendar

enum class Idioma { ES, EN }

/**
 * Textos de la app en español e inglés (pantalla y voz). Se elige el idioma en Ajustes;
 * la primera vez se usa el del celular. Las fechas usan la zona horaria del celular.
 */
class Textos(val idioma: Idioma) {
    val ingles get() = idioma == Idioma.EN
    private fun t(es: String, en: String) = if (ingles) en else es

    // ---------------- Generales ----------------
    val pagar get() = t("Pagar", "Pay")
    val miNegocio get() = t("Mi negocio", "My business")
    val cobrar get() = t("Cobrar", "Charge")
    val volver get() = t("Volver", "Back")
    val cancelar get() = t("Cancelar", "Cancel")
    val guardar get() = t("Guardar", "Save")
    val continuar get() = t("Continuar", "Continue")
    val cerrar get() = t("Cerrar", "Close")
    val copiar get() = t("Copiar", "Copy")
    val abrir get() = t("Abrir", "Open")
    val actualizar get() = t("Actualizar", "Refresh")
    val limpiar get() = t("Limpiar", "Clear")
    val borrar get() = t("Borrar", "Delete")
    val anterior get() = t("Anterior", "Previous")
    val siguiente get() = t("Siguiente", "Next")
    val ajustes get() = t("Ajustes", "Settings")
    val ajustesCorto get() = t("Idioma, voz y tu cuenta", "Language, voice and your account")
    val masOpciones get() = t("Más opciones", "More options")
    val idiomaEtiqueta get() = t("Idioma", "Language")
    val voz get() = t("Voz", "Voice")
    val vozDetalle get() = t("Lee en voz alta montos y resultados", "Reads amounts and results aloud")
    val vozActivada get() = t("Voz activada", "Voice on")
    val vozIdioma get() = t("Idioma: español", "Language: English")
    val confirmado get() = t("Confirmado", "Confirmed")
    val cliente get() = t("Cliente", "Customer")
    val negocio get() = t("Negocio", "Business")
    val moneda get() = t("Moneda", "Currency")
    val wallet get() = t("Wallet", "Wallet")
    val medio get() = t("Medio", "Method")
    val fecha get() = t("Fecha", "Date")
    val horaEtiqueta get() = t("Hora", "Time")
    val referencia get() = t("Referencia", "Reference")
    val comprobante get() = t("Comprobante", "Receipt")
    val verComprobante get() = t("Ver comprobante", "View receipt")
    val volverAlInicio get() = t("Volver al inicio", "Back to home")
    val guardaReferencia get() = t("Guarda esta referencia si necesitas consultar tu pago.", "Keep this reference if you need to look up your payment.")
    val billetera get() = t("Billetera", "Wallet")
    val tarjetaNfc get() = t("Tarjeta NFC", "NFC card")
    val celular get() = t("Celular", "Phone")
    val nfcRegistrada get() = t("NFC registrada", "Registered NFC")
    val sinRegistrar get() = t("Sin registrar", "Not registered")
    val ninguna get() = t("ninguna", "none")
    val esteCelular get() = t("Este celular", "This phone")
    val walletEnTarjeta get() = t("Wallet en la tarjeta", "Wallet on the card")
    val walletAddress get() = t("Wallet address del test wallet", "Test wallet address")

    fun hola(nombre: String) = t("Hola, $nombre", "Hi, $nombre")
    fun venceEn(tiempo: String) = t("El cobro vence en $tiempo", "Charge expires in $tiempo")
    fun dias(n: Int) = t("$n días", "$n days")
    fun meses(n: Int) = t("$n meses", "$n months")
    fun cobros(n: Int) = if (n == 1) t("1 cobro", "1 payment") else t("$n cobros", "$n payments")
    fun cobrosConfirmados(n: Int) = if (n == 1) t("1 cobro confirmado", "1 confirmed payment") else t("$n cobros confirmados", "$n confirmed payments")
    fun ventasHoy(n: Int) = if (n == 1) t("1 venta hoy", "1 sale today") else t("$n ventas hoy", "$n sales today")

    /** "USD · Dólares estadounidenses". */
    fun nombreMoneda(code: String): String {
        val nombre = when (code) {
            "USD" -> t("Dólares estadounidenses", "US dollars")
            "COP" -> t("Pesos colombianos", "Colombian pesos")
            "MXN" -> t("Pesos mexicanos", "Mexican pesos")
            "EUR" -> t("Euros", "Euros")
            "CAD" -> t("Dólares canadienses", "Canadian dollars")
            "GBP" -> t("Libras esterlinas", "Pounds sterling")
            else -> null
        }
        return if (nombre == null) code else "$code · $nombre"
    }

    /** Monto para la voz: en español con palabras; en inglés con el formato que leen bien los motores ("$10.50"). */
    fun montoVoz(valor: Long, scale: Int, code: String): String =
        if (ingles) {
            if (code == "USD") "$" + MontoUtil.numero(valor, scale, true) else "${MontoUtil.numero(valor, scale, true)} $code"
        } else MontoUtil.enPalabras(valor, scale, code)

    fun medioTipo(tipo: String?): String? = when (tipo) {
        "celular" -> t("Celular", "Phone")
        "tarjeta" -> tarjetaNfc
        null -> null
        else -> tipo
    }

    // ---------------- Fechas ----------------
    private val mesesCortos get() = if (ingles) listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    else listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
    private val mesesLargos get() = if (ingles) listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    else listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")

    private fun cal(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }
    private fun Calendar.dia() = get(Calendar.DAY_OF_MONTH)
    private fun Calendar.mes() = get(Calendar.MONTH)
    private fun Calendar.anio() = get(Calendar.YEAR)

    /** "16 oct" / "16 oct 2026" (en inglés "Oct 16" / "Oct 16, 2026"). */
    fun fechaCorta(ms: Long, conAnio: Boolean = false): String {
        val c = cal(ms)
        return if (ingles) "${mesesCortos[c.mes()]} ${c.dia()}" + (if (conAnio) ", ${c.anio()}" else "")
        else "${c.dia()} ${mesesCortos[c.mes()]}" + (if (conAnio) " ${c.anio()}" else "")
    }

    fun hora(ms: Long): String {
        val c = cal(ms)
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** "Hoy · 10:42" o "15 oct · 17:30". */
    fun fechaHora(ms: Long): String {
        val hoy = cal(System.currentTimeMillis())
        val c = cal(ms)
        val dia = if (c.anio() == hoy.anio() && c.get(Calendar.DAY_OF_YEAR) == hoy.get(Calendar.DAY_OF_YEAR)) t("Hoy", "Today") else fechaCorta(ms)
        return "$dia · ${hora(ms)}"
    }

    /** "16 de octubre de 2026" / "October 16, 2026". */
    fun fechaLarga(ms: Long): String {
        val c = cal(ms)
        return if (ingles) "${mesesLargos[c.mes()]} ${c.dia()}, ${c.anio()}" else "${c.dia()} de ${mesesLargos[c.mes()]} de ${c.anio()}"
    }

    /** "Octubre de 2026" / "October 2026". */
    fun mesLargo(ms: Long): String {
        val c = cal(ms)
        val m = mesesLargos[c.mes()].replaceFirstChar { it.uppercase() }
        return if (ingles) "$m ${c.anio()}" else "$m de ${c.anio()}"
    }

    /** Eje de meses: "nov 25" en los extremos, "feb" en medio. */
    fun mesCorto(ms: Long, conAnio: Boolean): String {
        val c = cal(ms)
        return mesesCortos[c.mes()] + if (conAnio) " " + (c.anio() % 100).toString().padStart(2, '0') else ""
    }

    /** Eje de días: "10 oct" en los extremos, "12" en medio. */
    fun diaEje(ms: Long, conMes: Boolean): String {
        val c = cal(ms)
        if (!conMes) return c.dia().toString()
        return if (ingles) "${mesesCortos[c.mes()]} ${c.dia()}" else "${c.dia()} ${mesesCortos[c.mes()]}"
    }

    /** "10–16 oct 2026 · 7 días" / "nov 2025–oct 2026 · 12 meses". */
    fun rango(inicio: Long, fin: Long, periodo: Periodo): String {
        val a = cal(inicio)
        val b = cal(fin)
        val cuantos = if (periodo.porMes) meses(periodo.barras) else dias(periodo.barras)
        val texto = if (periodo.porMes) {
            "${mesesCortos[a.mes()]} ${a.anio()}–${mesesCortos[b.mes()]} ${b.anio()}"
        } else if (ingles) {
            if (a.mes() == b.mes()) "${mesesCortos[a.mes()]} ${a.dia()}–${b.dia()}, ${b.anio()}"
            else "${mesesCortos[a.mes()]} ${a.dia()}–${mesesCortos[b.mes()]} ${b.dia()}, ${b.anio()}"
        } else {
            if (a.mes() == b.mes()) "${a.dia()}–${b.dia()} ${mesesCortos[b.mes()]} ${b.anio()}"
            else "${a.dia()} ${mesesCortos[a.mes()]}–${b.dia()} ${mesesCortos[b.mes()]} ${b.anio()}"
        }
        return "$texto · $cuantos"
    }

    fun posicion(i: Int, n: Int, periodo: Periodo) =
        if (periodo.porMes) t("$i de $n meses", "$i of $n months") else t("$i de $n días", "$i of $n days")

    fun ultimoUso(ms: Long?) = t("Último uso: ", "Last used: ") + (ms?.let { fechaHora(it) } ?: t("nunca", "never"))
    fun seRenueva(ms: Long?) = t("Se renueva el ", "Renews on ") + (ms?.let { fechaCorta(it, conAnio = true) } ?: "—")

    // ---------------- Errores ----------------

    /** Mensaje de un error del servidor en el idioma elegido (el servidor responde en español). */
    fun mensajeError(codigo: String, mensaje: String?): String = when (codigo) {
        "SIN_CONEXION" -> t("Sin conexión con el servidor. Revisa que esté corriendo y el adb reverse de este celular.",
            "No connection to the server. Check that it is running and this phone's adb reverse.")
        "TARJETA_NO_REGISTRADA" -> t("Esta tarjeta no está registrada.", "This card is not registered.")
        "SIN_PERMISO" -> t("No tiene permiso de pago activo.", "No active payment permission.")
        "TOPE_SUPERADO" -> t("Supera el tope semanal.", "Exceeds the weekly limit.")
        "COBRO_VENCIDO" -> t("El cobro venció.", "The charge expired.")
        "TARJETA_DISTINTA" -> otraTarjetaMisma
        "MEDIO_BLOQUEADO" -> t("Este medio de pago está bloqueado.", "This payment method is blocked.")
        "MISMA_CUENTA" -> t("No se puede cobrar a la propia cuenta.", "You can't charge your own account.")
        "CANCELADO" -> t("Cobro cancelado.", "Charge cancelled.")
        "PAGO_FALLIDO" -> t("El pago falló.", "The payment failed.")
        "CUENTA_NO_ENCONTRADA" -> t("No se encontró la cuenta.", "Account not found.")
        "WALLET_INVALIDA" -> t("No se encontró esa wallet address.", "That wallet address was not found.")
        "MONTO_INVALIDO" -> t("El monto debe ser mayor que cero.", "The amount must be greater than zero.")
        "NO_VINCULADO" -> t("Este celular no está vinculado. Reintentando…", "This phone is not linked. Retrying…")
        else -> if (!ingles && !mensaje.isNullOrBlank()) mensaje else t("No se pudo completar la operación.", "The operation could not be completed.")
    }

    /** Título de la pantalla de error del cobro (Mi negocio). */
    fun tituloError(codigo: String, celular: Boolean) = when (codigo) {
        "TARJETA_NO_REGISTRADA" -> if (celular) t("Celular no vinculado", "Phone not linked") else t("Tarjeta no registrada", "Card not registered")
        "MEDIO_BLOQUEADO" -> if (celular) t("Celular bloqueado", "Phone blocked") else t("Tarjeta bloqueada", "Card blocked")
        "COBRO_VENCIDO" -> t("Este cobro venció", "This charge expired")
        "TOPE_SUPERADO" -> t("Límite de gasto alcanzado", "Spending limit reached")
        "SIN_PERMISO" -> t("Sin permiso de pago", "No payment permission")
        "MISMA_CUENTA" -> t("Es tu propia cuenta", "It's your own account")
        "RESULTADO_DESCONOCIDO" -> t("Aún no sabemos el resultado", "We don't know the result yet")
        else -> t("El cobro no se completó", "The charge was not completed")
    }

    fun detalleError(codigo: String, celular: Boolean, restante: String?): String? = when (codigo) {
        "TARJETA_NO_REGISTRADA" -> if (celular) t("Este celular aún no está vinculado a una billetera.", "This phone is not linked to a wallet yet.")
        else t("Esta tarjeta aún no está vinculada a una billetera.", "This card is not linked to a wallet yet.")
        "MEDIO_BLOQUEADO" -> t("Su dueño lo bloqueó desde la app.", "Its owner blocked it from the app.")
        "COBRO_VENCIDO" -> t("Se terminó el tiempo para acercar la tarjeta. Puedes crear un nuevo cobro.", "Time ran out to tap the card. You can create a new charge.")
        "TOPE_SUPERADO" -> t("La billetera del cliente alcanzó su límite de gasto. Reintentar no cambia este límite.",
            "The customer's wallet reached its spending limit. Retrying won't change this limit.") +
            (restante?.let { t(" Quedan $it.", " $it left.") } ?: "")
        "SIN_PERMISO" -> t("El cliente no tiene un permiso de pago activo.", "The customer has no active payment permission.")
        "MISMA_CUENTA" -> t("No se puede cobrar a la cuenta de este celular.", "You can't charge this phone's own account.")
        "RESULTADO_DESCONOCIDO" -> t("Se perdió la conexión mientras se confirmaba. El pago pudo haberse hecho: consulta antes de cobrar otra vez.",
            "The connection was lost while confirming. The payment may have gone through: check before charging again.")
        "PAGO_FALLIDO", "ERROR_OPEN_PAYMENTS" -> t("Tuvimos un problema al confirmar la operación. No se realizó ningún cargo.",
            "We had a problem confirming the operation. No charge was made.")
        else -> null
    }

    fun notaError(codigo: String) = when (codigo) {
        "TARJETA_NO_REGISTRADA" -> t("No se realizó ningún cargo. Usa una tarjeta NFC registrada para continuar.", "No charge was made. Use a registered NFC card to continue.")
        "COBRO_VENCIDO" -> t("No se realizó ningún cargo a la billetera del cliente.", "No charge was made to the customer's wallet.")
        "TOPE_SUPERADO", "SIN_PERMISO" -> t("No se realizó ningún cargo. Acuerda otro medio de pago con el cliente.", "No charge was made. Agree on another payment method with the customer.")
        "RESULTADO_DESCONOCIDO" -> t("No crees otro cobro hasta conocer el resultado.", "Don't create another charge until you know the result.")
        else -> t("Puedes revisar el monto e iniciar el cobro de nuevo.", "You can check the amount and start the charge again.")
    }

    fun detalleErrorPagar(codigo: String, restante: String?): String? = when (codigo) {
        "TOPE_SUPERADO" -> t("La compra supera tu tope semanal.", "The purchase exceeds your weekly limit.") + (restante?.let { t(" Te quedan $it.", " You have $it left.") } ?: "")
        "SIN_PERMISO" -> t("No tienes un permiso de pago activo. Apruébalo en Mi permiso semanal.", "You have no active payment permission. Approve it in My weekly permission.")
        "MEDIO_BLOQUEADO" -> t("Este celular está bloqueado. Desbloquéalo en Mis tarjetas.", "This phone is blocked. Unblock it in My cards.")
        "TARJETA_NO_REGISTRADA" -> t("Este celular no está vinculado a tu cuenta.", "This phone is not linked to your account.")
        "COBRO_VENCIDO" -> t("El cobro venció antes de confirmarlo.", "The charge expired before it was confirmed.")
        "CANCELADO" -> t("El cobro fue cancelado.", "The charge was cancelled.")
        "MISMA_CUENTA" -> t("No puedes pagarte a ti mismo.", "You can't pay yourself.")
        else -> t("Algo falló al confirmar la compra. Puedes volver a intentarlo con tranquilidad.", "Something failed while confirming the purchase. You can safely try again.")
    }

    // ---------------- Inicio Pagar ----------------
    val disponibleSemana get() = t("Disponible esta semana", "Available this week")
    val tuPermisoSemanal get() = t("Tu permiso semanal", "Your weekly permission")
    val fuenteLocal get() = t("La wallet no respondió: calculado con el registro local.", "The wallet didn't respond: calculated from local records.")
    val ultimosPagos get() = t("Últimos pagos", "Recent payments")
    val verTodos get() = t("Ver todos", "See all")
    val sinPagos get() = t("Todavía no has hecho pagos.", "You haven't made any payments yet.")
    val piePagar get() = t("Tu dinero, a la mano.", "Your money, at hand.")
    val misPagos get() = t("Mis pagos", "My payments")
    val misPagosDetalle get() = t("Pagos que hiciste con tus tarjetas y este celular.", "Payments you made with your cards and this phone.")
    val sinPermisoDetalle get() = t("Sin permiso no puedes pagar con tarjeta ni con el celular.", "Without permission you can't pay with a card or this phone.")
    val aprobarPermiso get() = t("Aprobar mi permiso semanal", "Approve my weekly permission")
    val aprobarPermisoDetalle get() = t("Se abre el test wallet. Inicia sesión con la cuenta dueña de tu wallet, acepta y vuelve a esta app.",
        "The test wallet opens. Sign in with the account that owns your wallet, accept and come back to this app.")
    val sinHce get() = t("Este celular no puede pagar acercándolo (sin NFC o sin HCE). Puedes pagar con tus tarjetas.",
        "This phone can't pay by tapping (no NFC or HCE). You can pay with your cards.")
    val celularBloqueadoAviso get() = t("Este celular está bloqueado en Mis tarjetas: desbloquéalo para pagar con él.",
        "This phone is blocked in My cards: unblock it to pay with it.")
    val cancelarAutorizacion get() = t("Cancelar autorización", "Cancel authorization")
    fun autorizadoAcerque(segundos: Long) = t("Pago autorizado. Acerca el celular al datáfono · $segundos s", "Payment authorized. Tap your phone on the terminal · $segundos s")
    val autorizarPago get() = t("Autorizar pago", "Authorize payment")
    val autorizarPagoDatafono get() = t("Autorizar pago al datáfono", "Authorize payment to the terminal")
    val autoriceYAcerque get() = t("Autoriza el pago y vuelve a acercar el celular.", "Authorize the payment and tap your phone again.")
    val useHuella get() = t("Usa tu huella o el bloqueo de pantalla", "Use your fingerprint or screen lock")
    val useBloqueo get() = t("Usa tu bloqueo de pantalla", "Use your screen lock")
    val noAutorizado get() = t("No se autorizó el pago", "Payment not authorized")
    val configureBloqueo get() = t("Configura un bloqueo de pantalla (PIN, patrón o huella) para autorizar pagos.", "Set up a screen lock (PIN, pattern or fingerprint) to authorize payments.")
    val bloqueoOk get() = t("Los pagos con este celular se autorizan con tu huella o el bloqueo de pantalla.", "Payments with this phone are authorized with your fingerprint or screen lock.")
    fun estadoPermiso(estado: String) = when (estado) {
        "pendiente" -> t("Permiso pendiente de aprobar", "Permission waiting for approval")
        "no_valido" -> t("Tu permiso ya no es válido", "Your permission is no longer valid")
        "activo" -> t("Permiso activo", "Active permission")
        else -> t("Todavía no tienes permiso de pago", "You don't have a payment permission yet")
    }
    fun estadoMovimiento(estado: String) = when (estado) {
        "pagado" -> confirmado
        "procesando" -> t("Procesando", "Processing")
        "error" -> t("No completado", "Not completed")
        else -> t("En curso", "In progress")
    }

    // ---------------- Pago en curso (Pagar) ----------------
    val cobroPendiente get() = t("Cobro pendiente", "Pending charge")
    val revisaCompra get() = t("Revisa tu compra", "Review your purchase")
    val revisaCompraDetalle get() = t("Confirma el negocio y el monto antes de pagar.", "Check the business and the amount before paying.")
    val despuesDelPago get() = t("Disponible después del pago", "Available after payment")
    val seDebitara get() = t("Se debitará", "Will be debited")
    val escucharDetalles get() = t("Escuchar detalles", "Listen to details")
    val acerqueOtraVez get() = t("Para confirmar, acerca el celular al datáfono otra vez.", "To confirm, tap your phone on the terminal again.")
    val procesandoPago get() = t("Estamos procesando tu pago…", "We're processing your payment…")
    fun verificandoPagoA(tienda: String) = t("Espera un momento. Estamos verificando el pago a $tienda.", "Please wait. We're verifying the payment to $tienda.")
    val verificandoOperacion get() = t("Verificando la operación", "Verifying the operation")
    val noRepitas get() = t("No repitas el pago mientras confirmamos el resultado.", "Don't repeat the payment while we confirm the result.")
    val pagoConfirmado get() = t("Pago confirmado", "Payment confirmed")
    fun listoCompraPagada(nombre: String) = t("Listo, $nombre. Tu compra quedó pagada.", "All set, $nombre. Your purchase is paid.")
    val noPudimosCompletar get() = t("No pudimos completar el pago", "We couldn't complete the payment")
    val noSeCompletoCargo get() = t("No se completó ningún cargo con este cobro.", "No charge was completed for this purchase.")
    val pagoRealizado get() = t("Pago realizado", "Payment made")
    val pagadoPor get() = t("Pagado por", "Paid by")
    val pagadoA get() = t("Pagado a", "Paid to")

    // ---------------- Mis tarjetas ----------------
    val misTarjetas get() = t("Mis tarjetas", "My cards")
    val misTarjetasCorto get() = t("Tarjetas NFC y este celular", "NFC cards and this phone")
    val misTarjetasDetalle get() = t("Medios con los que pagas. Puedes bloquearlos si los pierdes.", "Methods you pay with. You can block them if you lose them.")
    val sinMedios get() = t("No tienes medios de pago.", "You have no payment methods.")
    val activa get() = t("Activa", "Active")
    val bloqueada get() = t("Bloqueada", "Blocked")
    val renombrar get() = t("Renombrar", "Rename")
    val bloquear get() = t("Bloquear", "Block")
    val desbloquear get() = t("Desbloquear", "Unblock")
    val eliminar get() = t("Eliminar", "Remove")
    val agregarTarjeta get() = t("Agregar tarjeta", "Add card")
    val nombreTarjeta get() = t("Nombre de la tarjeta (ej. Cívica)", "Card name (e.g. Transit)")
    val eliminarDetalle get() = t("Ya no podrás pagar con este medio. Puedes volver a agregarlo después.", "You won't be able to pay with it. You can add it again later.")
    val eliminarCelularDetalle get() = t("Es este celular: no podrás pagar acercándolo hasta que se vuelva a vincular (al reabrir la app).",
        "It's this phone: you won't be able to pay by tapping it until it's linked again (when you reopen the app).")
    fun eliminarPregunta(nombre: String) = t("¿Eliminar $nombre?", "Remove $nombre?")
    fun medioBloqueado(nombre: String) = t("$nombre bloqueada", "$nombre blocked")
    fun medioDesbloqueado(nombre: String) = t("$nombre desbloqueada", "$nombre unblocked")
    fun medioEliminado(nombre: String) = t("$nombre eliminada", "$nombre removed")
    val nombraTarjeta get() = t("Ponle un nombre a la tarjeta", "Name your card")
    val agregarDetalle get() = t("Mientras esta pantalla esté abierta, el celular lee tarjetas y no puede pagar.", "While this screen is open, the phone reads cards and can't pay.")
    val leerOtra get() = t("Leer otra tarjeta", "Read another card")
    fun tarjetaGuardada(nombre: String) = t("Tarjeta $nombre guardada", "Card $nombre saved")
    fun tarjetaYaSuya(nombre: String?) = t("Esta tarjeta ya es tuya", "This card is already yours") + (nombre?.let { ": $it" } ?: "")
    fun tarjetaYaRegistrada(nombre: String) = t("Esta tarjeta ya está registrada a $nombre", "This card is already registered to $nombre")

    // ---------------- Mi permiso ----------------
    val miPermiso get() = t("Mi permiso semanal", "My weekly permission")
    val miPermisoCorto get() = t("Tope de gasto que aprobaste en tu wallet", "Spending limit you approved in your wallet")
    val miPermisoDetalle get() = t("Open Payments solo deja pagar hasta este tope cada semana.", "Open Payments only allows payments up to this limit each week.")
    val permisoActivo get() = t("Permiso activo", "Active permission")
    val topeSemanal get() = t("Tope semanal", "Weekly limit")
    val gastado get() = t("Gastado", "Spent")
    val teQueda get() = t("Te queda", "Remaining")
    val seRenuevaEtiqueta get() = t("Se renueva", "Renews")
    val topeEnDolares get() = t("Tope semanal (en la moneda de tu wallet)", "Weekly limit (in your wallet's currency)")
    val pedirNuevoPermiso get() = t("Pedir uno nuevo y abrirlo aquí", "Request a new one and open it here")
    val soloEnlace get() = t("Solo generar el enlace", "Only generate the link")
    val enlaceAprobacion get() = t("Enlace de aprobación", "Approval link")
    val enlaceCopiado get() = t("Enlace copiado", "Link copied")
    val reglasPermiso get() = t("Solo el dueño de la wallet puede aprobarlo, con su sesión abierta en el test wallet. El enlace se abre una sola vez.",
        "Only the wallet owner can approve it, signed in to the test wallet. The link opens only once.")
    val yaLoAprobe get() = t("Ya lo aprobé: actualizar", "I approved it: refresh")

    // ---------------- Mi negocio ----------------
    val ventasConfirmadas get() = t("Ventas confirmadas", "Confirmed sales")
    val ventasPorDia get() = t("Ventas por día", "Sales per day")
    val ventasPorMes get() = t("Ventas por mes", "Sales per month")
    fun soloConfirmados(code: String) = t("Solo pagos confirmados · $code sin conversión", "Confirmed payments only · $code, no conversion")
    val ventasRecientes get() = t("Ventas recientes", "Recent sales")
    val ventasRecientesDetalle get() = t("Las últimas ventas confirmadas, no el total del período.", "Your latest confirmed sales, not the period total.")
    val sinVentas get() = t("Todavía no hay ventas confirmadas. Toca Cobrar para hacer la primera.", "No confirmed sales yet. Tap Charge to make the first one.")
    val sinVentasPeriodo get() = t("No hay ventas confirmadas en este período.", "No confirmed sales in this period.")
    val sinVentasRecientes get() = t("Aún no hay ventas en esta moneda.", "No sales in this currency yet.")
    val pieNegocio get() = t("Tu tienda, al día.", "Your shop, up to date.")
    val ventasDeHoy get() = t("Ventas de hoy", "Today's sales")
    fun descripcionGrafica(serie: SerieVentas, periodo: Periodo, scale: Int): String {
        val total = MontoUtil.numero(serie.total, scale, ingles)
        val unidad = if (periodo.porMes) t("mes", "month") else t("día", "day")
        val mejor = serie.barras.maxByOrNull { it.total }?.takeIf { it.total > 0 }
        val mejorTexto = mejor?.let {
            val cuando = if (periodo.porMes) mesLargo(it.inicio) else fechaLarga(it.inicio)
            t(" El $unidad con más ventas fue $cuando, con ${MontoUtil.numero(it.total, scale, ingles)}.", " The best $unidad was $cuando, with ${MontoUtil.numero(it.total, scale, ingles)}.")
        } ?: ""
        return t("Gráfica de barras de ventas confirmadas por $unidad. Total $total en ${cobros(serie.cantidad)}.",
            "Bar chart of confirmed sales per $unidad. Total $total in ${cobros(serie.cantidad)}.") + mejorTexto +
            t(" Toca una barra o usa los botones para ver cada $unidad.", " Tap a bar or use the buttons to see each $unidad.")
    }

    // Cobro
    val nuevoCobro get() = t("Nuevo cobro", "New charge")
    val ingresarMonto get() = t("Ingresar monto", "Enter amount")
    val ingresarMontoDetalle get() = t("Este es el importe que pagará tu cliente.", "This is the amount your customer will pay.")
    val revisaMonto get() = t("Revisa el monto antes de continuar.", "Check the amount before continuing.")
    val creandoCobro get() = t("Creando cobro…", "Creating charge…")
    val primerToque get() = t("Primer toque", "First tap")
    val acercaTarjeta get() = t("Acerca la tarjeta", "Tap the card")
    fun listaParaCobrar(tienda: String) = t("$tienda está lista para cobrar.", "$tienda is ready to charge.")
    val soloRegistradas get() = t("Solo tarjetas NFC o celulares registrados y vinculados a una billetera.", "Only NFC cards or phones registered and linked to a wallet.")
    val leyendo get() = t("Leyendo la tarjeta…", "Reading the card…")
    val cancelarCobro get() = t("Cancelar cobro", "Cancel charge")
    val segundoToque get() = t("Segundo toque · confirmación", "Second tap · confirmation")
    val retiraTarjeta get() = t("Retira la tarjeta y acércala otra vez para confirmar", "Remove the card and tap it again to confirm")
    val retiraCelular get() = t("Retira el celular y acércalo otra vez para confirmar", "Move the phone away and tap it again to confirm")
    val leyendoEnVozAlta get() = t("Leyendo los detalles en voz alta · toca para repetir", "Reading the details aloud · tap to repeat")
    val confirmandoPago get() = t("Confirmando el pago…", "Confirming the payment…")
    val aunNoConfirmado get() = t("El cobro aún no se ha confirmado.", "The charge hasn't been confirmed yet.")
    val otraTarjetaMisma get() = t("Es otra tarjeta. Acerca la misma tarjeta.", "That's a different card. Tap the same card.")
    val otroMedioCelular get() = t("Es otro medio de pago. Acerca el mismo celular.", "That's a different payment method. Tap the same phone.")
    val procesandoCobro get() = t("Procesando cobro…", "Processing charge…")
    fun confirmandoDe(cliente: String, tienda: String) = t("Estamos confirmando el pago de $cliente a $tienda.", "We're confirming $cliente's payment to $tienda.")
    val confirmandoOperacion get() = t("Confirmando la operación", "Confirming the operation")
    val esperaResultado get() = t("Espera el resultado antes de crear otro cobro.", "Wait for the result before creating another charge.")
    val pagoRecibido get() = t("Pago recibido", "Payment received")
    fun unaVentaMas(tienda: String) = t("¡Una venta más para $tienda!", "One more sale for $tienda!")
    val recibidoDe get() = t("Recibido de", "Received from")
    val recibidoEn get() = t("Recibido en", "Received at")
    val crearNuevoCobro get() = t("Crear nuevo cobro", "Create new charge")
    val usarOtraTarjeta get() = t("Usar otra tarjeta", "Use another card")
    val usarOtroMedio get() = t("Usar otro medio de pago", "Use another payment method")
    val volverAIntentar get() = t("Volver a intentar", "Try again")
    val consultarDeNuevo get() = t("Consultar otra vez", "Check again")

    // Registrar tarjeta de un cliente
    val registrarTarjeta get() = t("Registrar tarjeta", "Register card")
    val registrarCorto get() = t("Vincula la tarjeta NFC de un cliente", "Link a customer's NFC card")
    val registrarDetalle get() = t("Acerca la tarjeta del cliente y elige su cuenta.", "Tap the customer's card and choose their account.")
    val eligeCuenta get() = t("Elige la cuenta", "Choose the account")
    val sinCuentas get() = t("No hay cuentas.", "There are no accounts.")
    val conPermiso get() = t("Con permiso de pago", "Has payment permission")
    val sinPermisoTodavia get() = t("Sin permiso todavía", "No permission yet")
    val escribirWallet get() = t("Escribir la wallet address en la tarjeta", "Write the wallet address on the card")
    val acercaParaEscribir get() = t("Acerca la tarjeta otra vez para escribir…", "Tap the card again to write…")
    fun tarjetaGuardadaPara(nombre: String) = t("Tarjeta guardada para $nombre", "Card saved for $nombre")
    fun celularDe(nombre: String) = t("Es el celular de $nombre. Se administra desde su app en modo Pagar.", "It's $nombre's phone. It's managed from their app in Pay mode.")
    val celularSinVincular get() = t("Es un celular sin vincular. Se vincula desde su app en modo Pagar.", "It's an unlinked phone. It's linked from its app in Pay mode.")

    // Diagnóstico
    val diagnostico get() = t("Leer etiquetas", "Read tags")
    fun diagnosticoDetalle(n: Int) = t("Acerca una etiqueta NFC ($n/10)", "Tap an NFC tag ($n/10)")
    val leerEtiquetas get() = t("Leer etiquetas (diagnóstico)", "Read tags (diagnostics)")

    // ---------------- Cuenta y ajustes ----------------
    val bienvenida get() = t("Bienvenido", "Welcome")
    val crearCuenta get() = t("Crear mi cuenta", "Create my account")
    val crearCuentaDetalle get() = t("Con tu cuenta puedes pagar acercando este celular y también cobrar en tu negocio.", "With your account you can pay by tapping this phone and also charge in your business.")
    val tuNombre get() = t("Tu nombre", "Your name")
    val nombreNegocioOpcional get() = t("Nombre del negocio (opcional, para cobrar)", "Business name (optional, to charge)")
    fun nombreNegocioVacio(actual: String?) = t("Nombre del negocio (vacío = ${actual ?: "el de tu wallet"})", "Business name (empty = ${actual ?: "your wallet's"})")
    val recuperarCuenta get() = t("Si tu wallet ya tiene cuenta, se recupera con sus tarjetas y su permiso.", "If your wallet already has an account, it's recovered with its cards and permission.")
    val cambiarCuenta get() = t("Cambiar de cuenta", "Switch account")
    fun cuentaActual(nombre: String) = t("Ahora: $nombre", "Current: $nombre")
    val cuentasDetalle get() = t("Elige con qué cuenta paga y cobra este celular.", "Choose which account this phone pays and charges with.")
    val enUso get() = t("En uso", "In use")
    val usarCuentaExistente get() = t("Usar una cuenta existente", "Use an existing account")
    val agregarOtraCuenta get() = t("Crear o recuperar otra cuenta", "Create or recover another account")
    val cambiarCuentaAviso get() = t("Prototipo sin inicio de sesión: al cambiar, este celular paga con el permiso de esa cuenta y cobra en su wallet.",
        "Prototype without sign-in: after switching, this phone pays with that account's permission and charges into its wallet.")
    fun vozCuentaDe(nombre: String) = t("Cuenta de $nombre", "$nombre's account")
    val revisandoWallet get() = t("Revisando la wallet…", "Checking the wallet…")
    val guardado get() = t("Guardado", "Saved")
    fun guardadoCobraComo(negocio: String) = t("Guardado. Cobras como: $negocio", "Saved. You charge as: $negocio")
    val prototipoSinLogin get() = t("Prototipo sin inicio de sesión: la cuenta queda guardada en este celular. Quien lo use desbloqueado puede cobrar y ver su historial.",
        "Prototype without sign-in: the account is saved on this phone. Anyone using it unlocked can charge and see its history.")
    val cerrarCuenta get() = t("Cerrar la cuenta en este celular", "Sign out on this phone")
    val cerrarCuentaPregunta get() = t("¿Cerrar la cuenta en este celular?", "Sign out on this phone?")
    val cerrarCuentaDetalle get() = t("Este celular deja de ser un medio de pago. Tus tarjetas, permiso e historial siguen en el servidor.",
        "This phone stops being a payment method. Your cards, permission and history stay on the server.")
    val cuentaCerrada get() = t("Cuenta cerrada en este celular", "Signed out on this phone")
    val sinNfc get() = t("Este celular no tiene NFC.", "This phone has no NFC.")
    val nfcApagado get() = t("El NFC está apagado. Toca aquí para abrir los ajustes de NFC.", "NFC is off. Tap here to open NFC settings.")
    val sinConexionReintentar get() = t("Sin conexión con el servidor. Revisa que esté corriendo y el adb reverse. Toca para reintentar.",
        "No connection to the server. Check that it's running and adb reverse. Tap to retry.")

    // ---------------- Voz ----------------
    val vozModoPagar get() = t("Modo pagar", "Pay mode")
    val vozModoNegocio get() = t("Modo mi negocio", "My business mode")
    val vozAcerque get() = t("Acerque la tarjeta", "Tap the card")
    val vozOtraTarjeta get() = t("Es otra tarjeta", "That's a different card")
    val vozPagando get() = t("Pagando", "Paying")
    val vozAutorizado get() = t("Pago autorizado. Acerque el celular al datáfono.", "Payment authorized. Tap your phone on the terminal.")
    val vozAutoriceYAcerque get() = t("Autorice el pago con su huella y vuelva a acercar el celular.", "Authorize the payment with your fingerprint and tap your phone again.")
    val vozAprobarPermiso get() = t("Inicie sesión en el test wallet y acepte el permiso.", "Sign in to the test wallet and accept the permission.")
    val vozTarjetaLeida get() = t("Tarjeta leída. Escriba un nombre y guárdela.", "Card read. Type a name and save it.")
    fun vozCuentaCreada(nombre: String) = t("Listo, $nombre. Su cuenta quedó creada.", "All set, $nombre. Your account was created.")
    fun vozBienvenido(nombre: String) = t("Bienvenido de nuevo, $nombre.", "Welcome back, $nombre.")
    fun vozPregunta(cliente: String, monto: String, tienda: String, celular: Boolean) =
        t("$cliente, ¿pagar $monto a $tienda?", "$cliente, pay $monto to $tienda?") +
            if (celular) t(" Acerque el celular otra vez para confirmar.", " Tap the phone again to confirm.")
            else t(" Retire la tarjeta y acérquela otra vez para confirmar.", " Remove the card and tap it again to confirm.")
    fun vozTransfiriendo(monto: String, tienda: String) = t("Transfiriendo $monto a $tienda", "Transferring $monto to $tienda")
    fun vozPagoRecibido(monto: String) = t("Pago recibido, $monto", "Payment received, $monto")
    fun vozPagoHecho(monto: String) = t("Pago hecho, $monto", "Payment done, $monto")
    fun vozLeCobra(tienda: String, monto: String) = t("$tienda le cobra $monto. Acerque el celular otra vez para confirmar.", "$tienda is charging you $monto. Tap your phone again to confirm.")
    fun vozError(codigo: String, celular: Boolean, restante: String?): String = when (codigo) {
        "TARJETA_NO_REGISTRADA" -> if (celular) t("Este celular no está vinculado.", "This phone is not linked.") else t("Tarjeta no registrada.", "Card not registered.")
        "MEDIO_BLOQUEADO" -> if (celular) t("Este celular está bloqueado.", "This phone is blocked.") else t("Esta tarjeta está bloqueada.", "This card is blocked.")
        "MISMA_CUENTA" -> t("No puede cobrarse a usted mismo.", "You can't charge yourself.")
        "SIN_PERMISO" -> t("No tiene permiso de pago.", "No payment permission.")
        "TOPE_SUPERADO" -> t("Límite de gasto alcanzado.", "Spending limit reached.") + (restante?.let { t(" Quedan $it.", " $it left.") } ?: "")
        "COBRO_VENCIDO" -> t("El cobro venció.", "The charge expired.")
        "SIN_CONEXION" -> t("Sin conexión con el servidor.", "No connection to the server.")
        "CANCELADO" -> t("Cobro cancelado.", "Charge cancelled.")
        "RESULTADO_DESCONOCIDO" -> t("Aún no sabemos si el pago se completó.", "We don't know yet if the payment went through.")
        else -> t("El cobro no se completó.", "The charge was not completed.")
    }
    fun vozErrorPagar(codigo: String, restante: String?): String? = when (codigo) {
        "TARJETA_NO_REGISTRADA" -> t("Este celular no está vinculado.", "This phone is not linked.")
        "MEDIO_BLOQUEADO" -> t("Este celular está bloqueado. Desbloquéelo en Mis tarjetas.", "This phone is blocked. Unblock it in My cards.")
        "MISMA_CUENTA" -> t("No puede pagarse a usted mismo.", "You can't pay yourself.")
        "SIN_PERMISO" -> t("No tiene permiso de pago.", "No payment permission.")
        "TOPE_SUPERADO" -> t("Supera el tope semanal.", "Exceeds the weekly limit.") + (restante?.let { t(" Quedan $it.", " $it left.") } ?: "")
        "COBRO_VENCIDO" -> t("El cobro venció.", "The charge expired.")
        "CANCELADO" -> t("Cobro cancelado.", "Charge cancelled.")
        "PAGO_FALLIDO" -> t("El pago falló.", "The payment failed.")
        else -> null
    }
}
