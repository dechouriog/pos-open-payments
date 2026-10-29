// Permiso semanal (grant interactivo de outgoing-payment): estado y solicitud.
// Lo usan la página del familiar y la Billetera.
import { randomUUID } from 'node:crypto'
import { config } from './config.mjs'
import { datos, guardar } from './datos.mjs'
import { fechaRenovacion } from './medios.mjs'
import * as op from './op.mjs'
import { ETAPA, log } from './op.mjs'
import { aDecimal, aUnidades, formatearMonto, inicioPeriodoActual } from './util.mjs'

/**
 * Estado del permiso de un cliente:
 *   'activo' | 'pendiente' (esperando aprobación) | 'sin_permiso' | 'no_valido' (token que ya no se puede renovar).
 * Gastado: según la wallet (getGrantSpentAmounts) y, si la wallet no responde, el registro local.
 */
export async function estadoPermiso (cliente) {
  const p = datos.permisos[cliente.id]
  const pendiente = !!p?.pendiente
  if (!p?.accessToken) {
    return { estado: pendiente ? 'pendiente' : 'sin_permiso', activo: false, pendiente, topeDolares: p?.pendiente?.topeDolares }
  }
  if (p.invalidoDesde) {
    return { estado: pendiente ? 'pendiente' : 'no_valido', activo: false, pendiente, noValido: true, topeDolares: aDecimal(p.tope.value, p.tope.assetScale) }
  }
  const { value: tope, assetCode, assetScale } = p.tope
  let gastado = null
  let fuente = 'según la wallet'
  try {
    const w = await op.obtenerWallet(cliente.walletAddress)
    const r = await op.conRotacion(ETAPA.AUTORIZACION, p, (t) => op.gastadoGrant(w, t), guardar)
    gastado = r?.spentDebitAmount?.value ?? '0'
  } catch (e) {
    // Token vencido y la rotación también falló: el permiso ya no sirve
    if (op.esTokenVencido(e)) {
      p.invalidoDesde = new Date().toISOString()
      guardar()
      log(ETAPA.AUTORIZACION, `Permiso de ${cliente.nombre} NO VÁLIDO (${op.describirError(e)}): hay que aprobarlo de nuevo`)
      return estadoPermiso(cliente)
    }
    fuente = 'registro local'
    const desde = inicioPeriodoActual(p.inicio)
    gastado = datos.cobros
      .filter((c) => c.pagadorId === cliente.id && c.estado === 'pagado' && Date.parse(c.pagadoEn) >= desde)
      .reduce((s, c) => s + BigInt(c.debitAmount.value), 0n)
      .toString()
  }
  let restante = BigInt(tope) - BigInt(gastado)
  if (restante < 0n) restante = 0n
  return {
    estado: 'activo',
    activo: true,
    pendiente,
    tope: p.tope,
    gastado,
    restante: restante.toString(),
    topeTexto: formatearMonto(tope, assetCode, assetScale),
    gastadoTexto: formatearMonto(gastado, assetCode, assetScale),
    restanteTexto: formatearMonto(restante, assetCode, assetScale),
    topeDolares: aDecimal(tope, assetScale),
    renuevaEl: fechaRenovacion(p.inicio),
    alcance: p.alcance ?? 'basico',
    fuente
  }
}

/**
 * Pide el grant interactivo (tope semanal) y deja el permiso pendiente.
 * Intenta el alcance completo (ver todos los movimientos); si la wallet lo rechaza, pide el básico.
 * Devuelve el enlace de aprobación (interact.redirect).
 */
export async function solicitarPermiso (cliente, topeTexto) {
  const w = await op.obtenerWallet(cliente.walletAddress)
  // Moneda y escala salen siempre de la wallet del cliente
  const tope = { value: aUnidades(topeTexto || '200', w.assetScale), assetCode: w.assetCode, assetScale: w.assetScale }
  const inicio = new Date().toISOString()
  const interval = `R/${inicio}/P1W`
  const nonce = randomUUID()
  const finishUri = `http://localhost:${config.port}/familiar/callback?cliente=${encodeURIComponent(cliente.id)}`
  let alcance = 'completo'
  let grant
  try {
    grant = await op.grantInteractivo(w, tope, interval, finishUri, nonce, true)
  } catch (e) {
    if (!(e?.status >= 400 && e?.status < 500)) throw e
    log(ETAPA.AUTORIZACION, `La wallet rechazó el alcance completo (${op.describirError(e)}); se pide el básico`)
    alcance = 'basico'
    grant = await op.grantInteractivo(w, tope, interval, finishUri, nonce, false)
  }
  const p = datos.permisos[cliente.id] ?? {}
  p.pendiente = {
    tope, inicio, interval, nonce, alcance,
    topeDolares: aDecimal(tope.value, tope.assetScale),
    nonceServidor: grant.interact.finish,
    urlGrant: w.authServer,
    continueUri: grant.continue.uri,
    continueToken: grant.continue.access_token.value
  }
  datos.permisos[cliente.id] = p
  guardar()
  return grant.interact.redirect
}
