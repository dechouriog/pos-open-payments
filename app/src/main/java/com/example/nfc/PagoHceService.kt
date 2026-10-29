package com.example.nfc

import android.content.Context
import android.nfc.cardemulation.HostApduService
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * En modo Pagar, el celular responde al que cobra como si fuera una tarjeta (Host Card Emulation).
 * Entrega "v1|CEL:<id>|<wallet>" + 90 00 solo si: hay cuenta, el modo guardado es Pagar y el pago
 * está autorizado con huella o bloqueo de pantalla. Si no, 6A 82 (nadie le cobra a un celular que está cobrando).
 */
class PagoHceService : HostApduService() {

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        val modo = Estado.modo(this)
        val autorizado = Estado.autorizado()
        val respuesta = ProtocoloHce.responder(apdu, Estado.dispositivoId(this), Estado.wallet(this), modo, autorizado)
        val ok = respuesta.size > 2
        Log.d(TAG_LOG, "HCE <- ${NdefUtil.aHex(apdu)}  -> ${if (ok) "identidad + 90 00" else NdefUtil.aHex(respuesta)} (modo=$modo, autorizado=$autorizado)")
        if (ProtocoloHce.esSelectApp(apdu) && modo == Modo.PAGAR) {
            if (ok) {
                vibrarCorto()
                Estado.ultimoToque.value = System.currentTimeMillis()
            } else {
                // Avisa a la pantalla para que pida la huella
                Estado.toqueSinAutorizar.value = System.currentTimeMillis()
            }
        }
        return respuesta
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG_LOG, "HCE desactivado: ${if (reason == DEACTIVATION_LINK_LOSS) "se retiró el celular" else "otro AID"}")
    }

    private fun vibrarCorto() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(80)
        }
    }
}
