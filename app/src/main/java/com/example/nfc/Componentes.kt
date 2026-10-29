package com.example.nfc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------------- Tokens del prototipo de Figma ----------------

object Paleta {
    val Fondo = Color(0xFFF8F6EF)
    val Superficie = Color(0xFFFFFFFF)
    val Borde = Color(0xFFE6E3DA)
    val Neutro = Color(0xFFEDEBE3)
    val NeutroSuave = Color(0xFFF1EFE8)
    val Verde = Color(0xFF1F5A44)
    val VerdeOscuro = Color(0xFF1B4A39)
    val VerdeMedio = Color(0xFF2C6A52)
    val Lima = Color(0xFFDDEAB6)
    val LimaFondo = Color(0xFFE3EEC3)
    val LimaFuerte = Color(0xFFC9DE8E)
    val Lavanda = Color(0xFFE8E2F6)
    val LavandaFuerte = Color(0xFFCDC2EE)
    val LavandaTexto = Color(0xFF5B4B8F)
    val Texto = Color(0xFF1C2420)
    val TextoSuave = Color(0xFF6B706A)
    val Exito = Color(0xFF2E7D4F)
    val ErrorFondo = Color(0xFFF7E0DA)
    val Error = Color(0xFFC4513F)
}

/** Textos del idioma elegido (español o inglés). */
val LocalTextos = compositionLocalOf { Textos(Idioma.ES) }

@Composable
fun TemaPagoAlToque(contenido: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Paleta.Verde, onPrimary = Color.White,
            secondary = Paleta.Lima, onSecondary = Paleta.Verde,
            background = Paleta.Fondo, onBackground = Paleta.Texto,
            surface = Paleta.Superficie, onSurface = Paleta.Texto,
            surfaceVariant = Paleta.Neutro, onSurfaceVariant = Paleta.TextoSuave,
            outline = Paleta.Borde, error = Paleta.Error
        ),
        content = contenido
    )
}

private val Forma = RoundedCornerShape(12.dp)
private val FormaGrande = RoundedCornerShape(16.dp)

// ---------------- Textos ----------------

@Composable
fun Titulo(texto: String, tamano: TextUnit = 24.sp, centrado: Boolean = false, color: Color = Paleta.Texto) {
    Text(
        texto, fontSize = tamano, fontWeight = FontWeight.Bold, color = color, lineHeight = tamano * 1.18f,
        textAlign = if (centrado) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth().semantics { heading() }
    )
}

@Composable
fun Subtitulo(texto: String, centrado: Boolean = false, color: Color = Paleta.TextoSuave, tamano: TextUnit = 13.sp) {
    Text(
        texto, fontSize = tamano, color = color, lineHeight = tamano * 1.45f,
        textAlign = if (centrado) TextAlign.Center else TextAlign.Start, modifier = Modifier.fillMaxWidth()
    )
}

/** Etiqueta pequeña en mayúsculas sobre el título ("COBRO PENDIENTE"). */
@Composable
fun Ceja(texto: String) {
    Text(texto.uppercase(), fontSize = 11.sp, color = Paleta.Verde, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
}

/** Monto protagonista con su moneda debajo ("COP · Pesos colombianos"). */
@Composable
fun MontoGrande(texto: String, moneda: String?, tamano: TextUnit = 38.sp, centrado: Boolean = false, color: Color = Paleta.Texto) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (centrado) Alignment.CenterHorizontally else Alignment.Start) {
        Text(texto, fontSize = tamano, fontWeight = FontWeight.Bold, color = color, letterSpacing = (-0.5).sp, maxLines = 1)
        moneda?.let { Text(it, fontSize = 11.sp, color = if (color == Paleta.Texto) Paleta.TextoSuave else color.copy(alpha = 0.8f), fontWeight = FontWeight.Medium) }
    }
}

@Composable
fun Pie(texto: String) {
    Text(texto, fontSize = 12.sp, color = Paleta.TextoSuave, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
}

// ---------------- Encabezado y selector de modo ----------------

@Composable
fun Logo(tamano: Dp = 28.dp) {
    Box(Modifier.size(tamano).background(Paleta.Verde, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Icon(Iconos.Rayo, null, tint = Color.White, modifier = Modifier.size(tamano * 0.55f))
    }
}

/** Barra superior: logo, "Pago al Toque" y una etiqueta a la derecha. Con `alVolver`, flecha para regresar. */
@Composable
fun Encabezado(etiqueta: String?, alVolver: (() -> Unit)? = null) {
    val tx = LocalTextos.current
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (alVolver != null) {
            Box(
                Modifier.size(40.dp).clip(Forma).clickable(onClickLabel = tx.volver, role = Role.Button, onClick = alVolver),
                contentAlignment = Alignment.Center
            ) { Icon(Iconos.Atras, tx.volver, tint = Paleta.Verde, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(4.dp))
        }
        Logo()
        Spacer(Modifier.width(8.dp))
        Text("Pago al Toque", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Paleta.Texto)
        Spacer(Modifier.weight(1f))
        etiqueta?.let { Text(it, fontSize = 12.sp, color = Paleta.TextoSuave, maxLines = 1) }
    }
}

/** Selector "Pagar | Mi negocio" del prototipo. */
@Composable
fun SelectorModo(modo: Modo, alCambiar: (Modo) -> Unit) {
    val tx = LocalTextos.current
    Row(
        Modifier.fillMaxWidth().background(Paleta.Neutro, Forma).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf(Triple(Modo.PAGAR, tx.pagar, Iconos.Billetera), Triple(Modo.COBRAR, tx.miNegocio, Iconos.Tienda)).forEach { (m, texto, icono) ->
            val elegido = modo == m
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (elegido) Paleta.Verde else Color.Transparent)
                    .clickable(role = Role.Tab) { alCambiar(m) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                val color = if (elegido) Color.White else Paleta.TextoSuave
                Icon(icono, null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(texto, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
            }
        }
    }
}

// ---------------- Botones ----------------

@Composable
fun BotonPrimario(texto: String, onClick: () -> Unit, modifier: Modifier = Modifier, icono: ImageVector? = null, enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, shape = Forma,
        colors = ButtonDefaults.buttonColors(
            containerColor = Paleta.Verde, contentColor = Color.White,
            disabledContainerColor = Paleta.Verde.copy(alpha = 0.35f), disabledContentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.fillMaxWidth().height(50.dp)
    ) {
        icono?.let { Icon(it, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(texto, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
fun BotonSecundario(
    texto: String, onClick: () -> Unit, modifier: Modifier = Modifier, icono: ImageVector? = null,
    enabled: Boolean = true, color: Color = Paleta.Verde
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = Forma,
        border = androidx.compose.foundation.BorderStroke(1.dp, Paleta.Borde),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Paleta.Superficie, contentColor = color, disabledContentColor = Paleta.TextoSuave
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.fillMaxWidth().height(46.dp)
    ) {
        icono?.let { Icon(it, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
        Text(texto, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

// ---------------- Contenedores ----------------

@Composable
fun TarjetaBlanca(modifier: Modifier = Modifier, relleno: Dp = 16.dp, contenido: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Paleta.Superficie, FormaGrande).border(1.dp, Paleta.Borde, FormaGrande).padding(relleno),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = contenido
    )
}

@Composable
fun FilaDato(etiqueta: String, valor: String, colorValor: Color = Paleta.Texto) {
    Row(Modifier.fillMaxWidth().heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(etiqueta, fontSize = 13.sp, color = Paleta.TextoSuave, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(valor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colorValor, textAlign = TextAlign.End)
    }
}

@Composable
fun Separador(color: Color = Paleta.Borde) = HorizontalDivider(thickness = 1.dp, color = color)

enum class Tono { NEUTRO, LAVANDA, ERROR, EXITO }

/** Caja de aviso con ícono (gris, lavanda, roja o verde). */
@Composable
fun CajaInfo(texto: String, icono: ImageVector = Iconos.Info, tono: Tono = Tono.NEUTRO, onClick: (() -> Unit)? = null) {
    val (fondo, color) = when (tono) {
        Tono.NEUTRO -> Paleta.NeutroSuave to Paleta.TextoSuave
        Tono.LAVANDA -> Paleta.Lavanda to Paleta.LavandaTexto
        Tono.ERROR -> Paleta.ErrorFondo to Paleta.Error
        Tono.EXITO -> Paleta.LimaFondo to Paleta.Verde
    }
    Row(
        Modifier.fillMaxWidth().clip(Forma).background(fondo)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icono, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(texto, fontSize = 12.sp, color = color, lineHeight = 17.sp)
    }
}

/** Cuadro de ícono para filas de lista (pagos, ventas, opciones). */
@Composable
fun IconoCuadro(icono: ImageVector, fondo: Color = Paleta.Neutro, color: Color = Paleta.Verde, tamano: Dp = 34.dp) {
    Box(Modifier.size(tamano).background(fondo, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
        Icon(icono, null, tint = color, modifier = Modifier.size(tamano * 0.5f))
    }
}

/**
 * Ícono grande de resultado sobre un cuadro lima (o rosado si es error), con las pinceladas
 * discretas del prototipo: una lavanda arriba a la derecha y dos lima abajo a la izquierda.
 */
@Composable
fun IconoResultado(icono: ImageVector?, error: Boolean = false, cargando: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(116.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 150.dp, height = 116.dp)) {
            Pincelada(Paleta.LavandaFuerte, 44.dp, 13.dp, (-14f), Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = 10.dp))
            Pincelada(Paleta.LimaFuerte, 26.dp, 9.dp, 18f, Modifier.align(Alignment.BottomStart).offset(x = 10.dp, y = (-22).dp))
            Pincelada(Paleta.LimaFuerte, 16.dp, 7.dp, -10f, Modifier.align(Alignment.BottomStart).offset(x = 38.dp, y = (-6).dp))
            Box(
                Modifier.size(84.dp).align(Alignment.Center)
                    .background(if (error) Paleta.ErrorFondo else Paleta.Lima, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    cargando -> CircularProgressIndicator(Modifier.size(34.dp), color = Paleta.Verde, strokeWidth = 2.5.dp, trackColor = Color.Transparent)
                    icono != null -> Icon(icono, null, tint = if (error) Paleta.Error else Paleta.Verde, modifier = Modifier.size(34.dp))
                }
            }
        }
    }
}

@Composable
private fun Pincelada(color: Color, ancho: Dp, alto: Dp, giro: Float, modifier: Modifier) {
    Box(modifier.size(ancho, alto).rotate(giro).background(color, RoundedCornerShape(50)))
}

// ---------------- Listas ----------------

/** Fila de pago o venta: ícono, nombre, fecha, monto y estado. */
@Composable
fun FilaMovimiento(icono: ImageVector, titulo: String, detalle: String, monto: String, estado: String, colorEstado: Color = Paleta.Exito) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconoCuadro(icono)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Paleta.Texto, maxLines = 1)
            Text(detalle, fontSize = 12.sp, color = Paleta.TextoSuave)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(monto, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Paleta.Texto)
            Text(estado, fontSize = 11.sp, color = colorEstado)
        }
    }
}

/** Opción de menú con chevrón (Mis tarjetas, Ajustes…). */
@Composable
fun FilaOpcion(icono: ImageVector, titulo: String, detalle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Forma).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconoCuadro(icono)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Paleta.Texto)
            detalle?.let { Text(it, fontSize = 12.sp, color = Paleta.TextoSuave) }
        }
        Icon(Iconos.Derecha, null, tint = Paleta.TextoSuave, modifier = Modifier.size(18.dp))
    }
}

// ---------------- Controles ----------------

/** Ficha seleccionable (moneda, monto rápido). */
@Composable
fun Ficha(texto: String, seleccionada: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, conCheck: Boolean = false, oscura: Boolean = true) {
    val fondo = when {
        !seleccionada -> Paleta.Superficie
        oscura -> Paleta.Verde
        else -> Paleta.Lima
    }
    val color = if (seleccionada && oscura) Color.White else if (seleccionada) Paleta.Verde else Paleta.Texto
    Row(
        modifier.height(40.dp).clip(Forma).background(fondo)
            .border(1.dp, if (seleccionada) fondo else Paleta.Borde, Forma)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        if (conCheck && seleccionada) {
            Icon(Iconos.Check, null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(texto, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

/** Control segmentado claro (7 días · 30 días · 12 meses; Español · English). */
@Composable
fun <T> Segmentado(opciones: List<Pair<T, String>>, seleccion: T, alElegir: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Paleta.Neutro, Forma).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        opciones.forEach { (valor, texto) ->
            val elegido = valor == seleccion
            Box(
                Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (elegido) Paleta.Superficie else Color.Transparent)
                    .then(if (elegido) Modifier.border(1.dp, Paleta.Borde, RoundedCornerShape(9.dp)) else Modifier)
                    .clickable(role = Role.Tab) { alElegir(valor) },
                contentAlignment = Alignment.Center
            ) {
                Text(texto, fontSize = 13.sp, fontWeight = if (elegido) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (elegido) Paleta.Texto else Paleta.TextoSuave)
            }
        }
    }
}

@Composable
fun Interruptor(texto: String, detalle: String?, activo: Boolean, alCambiar: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Forma).clickable(role = Role.Switch) { alCambiar(!activo) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(texto, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Paleta.Texto)
            detalle?.let { Text(it, fontSize = 12.sp, color = Paleta.TextoSuave) }
        }
        Switch(
            checked = activo, onCheckedChange = alCambiar,
            colors = SwitchDefaults.colors(checkedTrackColor = Paleta.Verde, uncheckedTrackColor = Paleta.Neutro, uncheckedBorderColor = Paleta.Borde)
        )
    }
}

@Composable
fun CampoTexto(valor: String, alCambiar: (String) -> Unit, etiqueta: String, teclado: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        valor, alCambiar, label = { Text(etiqueta, fontSize = 13.sp) }, singleLine = true,
        textStyle = TextStyle(fontSize = 15.sp, color = Paleta.Texto),
        keyboardOptions = KeyboardOptions(keyboardType = teclado),
        shape = Forma,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Paleta.Verde, unfocusedBorderColor = Paleta.Borde,
            focusedContainerColor = Paleta.Superficie, unfocusedContainerColor = Paleta.Superficie,
            focusedLabelColor = Paleta.Verde, cursorColor = Paleta.Verde
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Teclado numérico del prototipo: 1–9, "00" (o separador decimal si la moneda tiene centavos), 0 y Borrar. */
@Composable
fun Teclado(alTocar: (Char) -> Unit, conDecimal: Boolean, separador: Char) {
    val tx = LocalTextos.current
    val filas = listOf("123", "456", "789", (if (conDecimal) "." else "D") + "0<")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        filas.forEach { fila ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fila.forEach { c ->
                    Tecla(Modifier.weight(1f), onClick = { alTocar(c) }) {
                        when (c) {
                            '<' -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Iconos.Borrar, null, tint = Paleta.Texto, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tx.borrar, fontSize = 13.sp, color = Paleta.Texto)
                            }
                            'D' -> Text("00", fontSize = 19.sp, color = Paleta.Texto)
                            '.' -> Text(separador.toString(), fontSize = 19.sp, color = Paleta.Texto)
                            else -> Text(c.toString(), fontSize = 19.sp, color = Paleta.Texto)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tecla(modifier: Modifier, onClick: () -> Unit, contenido: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.height(50.dp).clip(RoundedCornerShape(10.dp)).background(Paleta.Superficie)
            .border(1.dp, Paleta.Borde, RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center, content = contenido
    )
}

/** "El cobro vence en 00:59": llama [alVencer] una vez al llegar a cero. */
@Composable
fun CuentaRegresiva(limiteMs: Long, alVencer: () -> Unit) {
    val tx = LocalTextos.current
    var ahora by remember(limiteMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(limiteMs) {
        while (true) {
            ahora = System.currentTimeMillis()
            if (ahora >= limiteMs) {
                alVencer()
                break
            }
            delay(250)
        }
    }
    val segundos = ((limiteMs - ahora + 999) / 1000).coerceAtLeast(0)
    val texto = "%02d:%02d".format(segundos / 60, segundos % 60)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val color = if (segundos <= 10) Paleta.Error else Paleta.Verde
        Icon(Iconos.Temporizador, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(tx.venceEn(texto), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** Barra de progreso indeterminada de "Procesando…". */
@Composable
fun BarraProgreso() {
    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
        color = Paleta.Verde, trackColor = Paleta.Neutro
    )
}

/**
 * Ilustración del toque: la tarjeta (lavanda) se acerca al teléfono que cobra.
 * `regresa` = segundo toque (la flecha apunta al teléfono desde la derecha).
 */
@Composable
fun IlustracionToque(etiqueta: String, nombre: String, detalle: String, tienda: String, regresa: Boolean) {
    Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 260.dp, height = 150.dp)) {
            Box(Modifier.align(Alignment.BottomStart).offset(x = 16.dp, y = (-4).dp).size(90.dp, 12.dp).background(Paleta.LimaFuerte.copy(alpha = 0.6f), RoundedCornerShape(50)))
            Column(
                Modifier.align(Alignment.CenterStart).offset(x = 8.dp).size(width = 116.dp, height = 70.dp)
                    .background(Paleta.Lavanda, RoundedCornerShape(10.dp))
                    .border(1.dp, Paleta.LavandaFuerte, RoundedCornerShape(10.dp)).padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(etiqueta, fontSize = 9.sp, color = Paleta.LavandaTexto, modifier = Modifier.weight(1f))
                    Icon(Iconos.Contactless, null, tint = Paleta.LavandaTexto, modifier = Modifier.size(12.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(nombre, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Paleta.Texto, maxLines = 1)
                Text(detalle, fontSize = 8.sp, color = Paleta.LavandaTexto, maxLines = 1)
            }
            Icon(
                if (regresa) Iconos.Atras else Iconos.FlechaDerecha, null, tint = Paleta.Verde,
                modifier = Modifier.align(Alignment.Center).offset(x = 16.dp).size(20.dp)
            )
            Column(
                Modifier.align(Alignment.CenterEnd).offset(x = (-12).dp).size(width = 64.dp, height = 118.dp)
                    .background(Paleta.Superficie, RoundedCornerShape(14.dp))
                    .border(2.dp, Paleta.Verde, RoundedCornerShape(14.dp)).padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Icon(Iconos.Contactless, null, tint = Paleta.Verde, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(6.dp))
                Text(tienda, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Paleta.Verde, maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Círculo de carga simple para pantallas que esperan datos. */
@Composable
fun Cargando() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(28.dp)) {
            drawArc(Paleta.Neutro, 0f, 360f, false, style = Stroke(3.dp.toPx()))
        }
        CircularProgressIndicator(Modifier.size(28.dp), color = Paleta.Verde, strokeWidth = 3.dp, trackColor = Color.Transparent)
    }
}

/** Fila con dos botones del mismo ancho. */
@Composable
fun FilaBotones(contenido: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = contenido)
}

@Composable
fun TarjetaLectura(l: Lectura) {
    TarjetaBlanca(relleno = 12.dp) {
        Text(l.hora, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("UID: ${l.uid}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text("Tech: ${l.tecnologias.joinToString(", ")}", fontSize = 12.sp)
        Text("NDEF: ${l.formatoNdef} · ${if (l.tieneMensajeNdef) "msg" else "—"}", fontSize = 12.sp)
        l.tamanoMaximo?.let { Text("Max: $it bytes", fontSize = 12.sp) }
        l.registros.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
    }
}
