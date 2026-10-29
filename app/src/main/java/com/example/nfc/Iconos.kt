package com.example.nfc

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Íconos de trazo (24x24, trazo 2, puntas redondas) como los del prototipo de Figma.
 * Son vectores locales: el color se aplica con el `tint` de Icon.
 */
object Iconos {
    private fun icono(nombre: String, vararg trazos: String): ImageVector =
        ImageVector.Builder(nombre, 24.dp, 24.dp, 24f, 24f).apply {
            trazos.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                )
            }
        }.build()

    private const val CIRCULO = "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20z"

    val Rayo by lazy { icono("rayo", "M13 2 3 14h9l-1 8 10-12h-9l1-8z") }
    val Billetera by lazy {
        icono(
            "billetera",
            "M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1",
            "M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4"
        )
    }
    val Tienda by lazy {
        icono(
            "tienda",
            "M3 9l2-5h14l2 5",
            "M3 9h18v1.5a2.5 2.5 0 0 1-4.5 1.5a2.5 2.5 0 0 1-4.5 0a2.5 2.5 0 0 1-4.5 0A2.5 2.5 0 0 1 3 10.5z",
            "M5 12.5V20h14v-7.5",
            "M10 20v-4h4v4"
        )
    }
    val Contactless by lazy {
        icono(
            "contactless",
            "M6 8.32a7.43 7.43 0 0 1 0 7.36",
            "M9.46 6.21a11.76 11.76 0 0 1 0 11.58",
            "M12.91 4.1a15.91 15.91 0 0 1 .01 15.8",
            "M16.37 2a20.16 20.16 0 0 1 0 20"
        )
    }
    val Tarjeta by lazy { icono("tarjeta", "M4 5h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M2 10h20") }
    val Reloj by lazy { icono("reloj", CIRCULO, "M12 6v6l4 2") }
    val Temporizador by lazy { icono("temporizador", "M10 2h4", "M12 14l3-3", "M12 6a8 8 0 1 0 0 16a8 8 0 1 0 0-16z") }
    val Escudo by lazy { icono("escudo", "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z", "M12 8v4", "M12 16h.01") }
    val EscudoCheck by lazy { icono("escudoCheck", "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z", "M9 12l2 2 4-4") }
    val Check by lazy { icono("check", "M20 6 9 17l-5-5") }
    val CirculoCheck by lazy { icono("circuloCheck", CIRCULO, "M9 12l2 2 4-4") }
    val Alerta by lazy { icono("alerta", CIRCULO, "M12 8v4", "M12 16h.01") }
    val Info by lazy { icono("info", CIRCULO, "M12 16v-4", "M12 8h.01") }
    val Volumen by lazy {
        icono("volumen", "M11 5 6 9H2v6h4l5 4V5z", "M15.54 8.46a5 5 0 0 1 0 7.07", "M19.07 4.93a10 10 0 0 1 0 14.14")
    }
    val FlechaEntrada by lazy { icono("flechaEntrada", "M17 7 7 17", "M17 17H7V7") }
    val FlechaSalida by lazy { icono("flechaSalida", "M7 17 17 7", "M7 7h10v10") }
    val Documento by lazy {
        icono(
            "documento",
            "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z",
            "M14 2v6h6", "M16 13H8", "M16 17H8", "M10 9H8"
        )
    }
    val Izquierda by lazy { icono("izquierda", "M15 18l-6-6 6-6") }
    val Derecha by lazy { icono("derecha", "M9 18l6-6-6-6") }
    val Atras by lazy { icono("atras", "M19 12H5", "M12 19l-7-7 7-7") }
    val FlechaDerecha by lazy { icono("flechaDerecha", "M5 12h14", "M12 5l7 7-7 7") }
    val Borrar by lazy {
        icono("borrar", "M20 5H9l-7 7 7 7h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z", "M18 9l-6 6", "M12 9l6 6")
    }
    val Celular by lazy { icono("celular", "M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M12 18h.01") }
    val Persona by lazy { icono("persona", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M12 3a4 4 0 1 0 0 8a4 4 0 1 0 0-8z") }
    val Ajustes by lazy {
        icono("ajustes", "M4 6h10", "M18 6h2", "M4 12h4", "M12 12h8", "M4 18h12", "M20 18h0",
            "M16 4v4", "M10 10v4", "M18 16v4")
    }
    val Globo by lazy {
        icono("globo", CIRCULO, "M2 12h20", "M12 2a15.3 15.3 0 0 1 4 10a15.3 15.3 0 0 1-4 10a15.3 15.3 0 0 1-4-10a15.3 15.3 0 0 1 4-10z")
    }
    val Lista by lazy { icono("lista", "M8 6h13", "M8 12h13", "M8 18h13", "M3 6h.01", "M3 12h.01", "M3 18h.01") }
    val Mas by lazy { icono("mas", "M12 5v14", "M5 12h14") }
    val Candado by lazy {
        icono("candado", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4")
    }
}
