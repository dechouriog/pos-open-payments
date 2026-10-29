package com.example.nfc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BarraNormal = Color(0xFF86B593)
private val Rejilla = Color.White.copy(alpha = 0.12f)
private val TextoEje = Color.White.copy(alpha = 0.6f)

/**
 * Tarjeta verde "Ventas confirmadas" del prototipo: total, gráfica de barras con selección,
 * resumen de la barra elegida y navegación anterior / siguiente.
 */
@Composable
fun TarjetaVentas(
    serie: SerieVentas, periodo: Periodo, assetCode: String, assetScale: Int,
    seleccion: Int, alSeleccionar: (Int) -> Unit
) {
    val tx = LocalTextos.current
    val ingles = tx.ingles
    val elegida = serie.barras.getOrNull(seleccion)
    Column(
        Modifier.fillMaxWidth().background(Paleta.VerdeOscuro, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(tx.ventasConfirmadas, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
        Text(MontoUtil.corto(serie.total, assetCode, assetScale, ingles), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        Text("$assetCode · ${tx.cobrosConfirmados(serie.cantidad)}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(if (periodo.porMes) tx.ventasPorMes else tx.ventasPorDia, fontSize = 10.sp, color = TextoEje, modifier = Modifier.weight(1f))
            Text(assetCode, fontSize = 10.sp, color = TextoEje)
        }
        Grafica(serie, periodo, assetScale, seleccion, alSeleccionar)
        Spacer(Modifier.height(8.dp))
        if (elegida != null) {
            Column(
                Modifier.fillMaxWidth().background(Paleta.VerdeMedio, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Iconos.Reloj, null, tint = Paleta.Lima, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (periodo.porMes) tx.mesLargo(elegida.inicio) else tx.fechaLarga(elegida.inicio), fontSize = 12.sp, color = Paleta.Lima)
                }
                Text(MontoUtil.conCodigo(elegida.total, assetCode, assetScale, ingles), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(tx.cobros(elegida.cantidad), fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BotonFlecha(Iconos.Izquierda, tx.anterior, seleccion > 0) { alSeleccionar(seleccion - 1) }
            Text(
                tx.posicion(seleccion + 1, serie.barras.size, periodo), fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            BotonFlecha(Iconos.Derecha, tx.siguiente, seleccion < serie.barras.size - 1) { alSeleccionar(seleccion + 1) }
        }
    }
}

@Composable
private fun BotonFlecha(icono: ImageVector, descripcion: String, activo: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = if (activo) 0.14f else 0.06f))
            .clickable(enabled = activo, role = Role.Button, onClickLabel = descripcion, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icono, descripcion, tint = Color.White.copy(alpha = if (activo) 1f else 0.35f), modifier = Modifier.size(18.dp))
    }
}

/** Barras dibujadas con Canvas: eje con tope "redondo", rejilla punteada y la barra elegida en lima. */
@Composable
private fun Grafica(serie: SerieVentas, periodo: Periodo, assetScale: Int, seleccion: Int, alSeleccionar: (Int) -> Unit) {
    val tx = LocalTextos.current
    val medidor = rememberTextMeasurer()
    val estiloEje = TextStyle(fontSize = 9.sp, color = TextoEje)
    val tope = Ventas.topeEje(serie.maximo)
    val n = serie.barras.size
    val etiquetas = when (periodo) {
        Periodo.DIAS7 -> listOf(0, 2, 4, 6)
        Periodo.DIAS30 -> listOf(0, 10, 20, 29)
        Periodo.MESES12 -> listOf(0, 3, 6, 9, 11)
    }
    val descripcion = tx.descripcionGrafica(serie, periodo, assetScale)
    Canvas(
        Modifier.fillMaxWidth().height(170.dp)
            .semantics { contentDescription = descripcion }
            .pointerInput(serie, periodo) {
                detectTapGestures { p ->
                    val izquierda = 34.dp.toPx()
                    val ancho = size.width - izquierda
                    if (p.x >= izquierda && n > 0) alSeleccionar(((p.x - izquierda) / (ancho / n)).toInt().coerceIn(0, n - 1))
                }
            }
    ) {
        val izquierda = 34.dp.toPx()
        val abajo = size.height - 20.dp.toPx()
        val arriba = 6.dp.toPx()
        val alto = abajo - arriba
        val ancho = size.width - izquierda
        val ranura = ancho / n
        val anchoBarra = ranura * (if (n > 12) 0.62f else 0.56f)
        val punteada = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))

        // Rejilla y etiquetas del eje Y (tope y 0)
        listOf(arriba, arriba + alto / 2).forEach {
            drawLine(Rejilla, Offset(izquierda, it), Offset(size.width, it), strokeWidth = 1.dp.toPx(), pathEffect = punteada)
        }
        drawLine(Color.White.copy(alpha = 0.25f), Offset(izquierda, abajo), Offset(size.width, abajo), strokeWidth = 1.dp.toPx())
        if (tope > 0) {
            val t = medidor.measure(MontoUtil.compacto(tope, assetScale, tx.ingles), estiloEje)
            drawText(t, topLeft = Offset(0f, arriba - t.size.height / 2f))
        }
        val cero = medidor.measure("0", estiloEje)
        drawText(cero, topLeft = Offset(0f, abajo - cero.size.height / 2f))

        // Barras
        serie.barras.forEachIndexed { i, b ->
            val x = izquierda + ranura * i + (ranura - anchoBarra) / 2
            val h = if (tope > 0) alto * (b.total.toFloat() / tope) else 0f
            val elegida = i == seleccion
            val color = if (elegida) Paleta.Lima else BarraNormal
            if (h <= 0f) {
                drawRoundRect(color.copy(alpha = 0.35f), Offset(x, abajo - 2.dp.toPx()), Size(anchoBarra, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
            } else {
                drawRoundRect(color, Offset(x, abajo - h), Size(anchoBarra, h), CornerRadius(minOf(3.dp.toPx(), anchoBarra / 2)))
            }
        }

        // Etiquetas del eje X
        etiquetas.filter { it < n }.forEach { i ->
            val b = serie.barras[i]
            val extremo = i == 0 || i == n - 1
            val texto = if (periodo.porMes) tx.mesCorto(b.inicio, conAnio = extremo) else tx.diaEje(b.inicio, conMes = extremo || periodo == Periodo.DIAS30)
            val t = medidor.measure(texto, estiloEje)
            val centro = izquierda + ranura * i + ranura / 2
            val x = (centro - t.size.width / 2f).coerceIn(izquierda - 4.dp.toPx(), size.width - t.size.width)
            drawText(t, topLeft = Offset(x, abajo + 5.dp.toPx()))
        }
    }
}
