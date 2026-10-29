// Envoltura del SDK de Open Payments con registro por etapas en la consola.
import { createAuthenticatedClient, isFinalizedGrant, isPendingGrant } from '@interledger/open-payments'
import { config } from './config.mjs'
import { textoAmount } from './util.mjs'

export const ETAPA = {
  SOLICITUD: '① SOLICITUD',
  AUTORIZACION: '② AUTORIZACIÓN',
  EJECUCION: '③ EJECUCIÓN',
  FINALIZACION: '④ FINALIZACIÓN'
}

export function log (etapa, mensaje) {
  console.log(`${new Date().toLocaleTimeString('es-CO')} [${etapa}] ${mensaje}`)
}

/** Texto legible de un error del SDK (sin tokens ni llaves). */
export function describirError (e) {
  const partes = [e?.message ?? String(e)]
  if (e?.description && e.description !== e.message) partes.push(e.description)
  if (e?.status) partes.push(`HTTP ${e.status}`)
  if (e?.code) partes.push(`code=${e.code}`)
  if (e?.validationErrors?.length) partes.push(`validación: ${e.validationErrors.join('; ')}`)
  if (e?.details) partes.push(`detalles: ${JSON.stringify(e.details)}`)
  if (e?.cause?.message) partes.push(`causa: ${e.cause.message}`)
  return partes.join(' | ')
}

let promesaCliente
export function cliente () {
  promesaCliente ??= createAuthenticatedClient({
    walletAddressUrl: config.appWallet,
    keyId: config.keyId,
    privateKey: config.privateKeyPath // el SDK acepta la ruta del archivo
  }).catch((e) => {
    promesaCliente = undefined
    throw e
  })
  return promesaCliente
}

const cacheWallets = new Map()
export async function obtenerWallet (url) {
  if (!cacheWallets.has(url)) {
    const c = await cliente()
    const w = await c.walletAddress.get({ url })
    cacheWallets.set(url, w)
  }
  return cacheWallets.get(url)
}

/**
 * Grant no interactivo (incoming-payment o quote). Devuelve { accessToken, manageUrl }.
 * Con `identifier` el grant queda atado a esa wallet (el test wallet lo exige para listar).
 */
export async function grantNoInteractivo (etapa, wallet, tipo, acciones, { identifier, silencioso } = {}) {
  const c = await cliente()
  if (!silencioso) log(etapa, `Pidiendo grant no interactivo de ${tipo} en ${wallet.authServer}`)
  const acceso = { type: tipo, actions: acciones }
  if (identifier) acceso.identifier = identifier
  const grant = await c.grant.request(
    { url: wallet.authServer },
    { access_token: { access: [acceso] } }
  )
  if (!isFinalizedGrant(grant)) throw new Error(`Se esperaba un grant finalizado de ${tipo}`)
  if (!silencioso) log(etapa, `Grant de ${tipo} concedido`)
  return { accessToken: grant.access_token.value, manageUrl: grant.access_token.manage }
}

/** Token vencido: 401, o 403 "Inactive Token" (así responde el test wallet/Rafiki). */
export const esTokenVencido = (e) =>
  e?.status === 401 || (e?.status === 403 && /inactive token/i.test(`${e?.description} ${e?.message}`))

/** Si el token está vencido lo rota (token.rotate) y reintenta una vez. */
export async function conRotacion (etapa, cred, fn, alRotar) {
  try {
    return await fn(cred.accessToken)
  } catch (e) {
    if (!esTokenVencido(e) || !cred.manageUrl) throw e
    log(etapa, `Token vencido (HTTP ${e.status}${e.description ? ' ' + e.description : ''}): rotando con token.rotate y reintentando una vez`)
    const c = await cliente()
    const r = await c.token.rotate({ url: cred.manageUrl, accessToken: cred.accessToken })
    cred.accessToken = r.access_token.value
    cred.manageUrl = r.access_token.manage
    alRotar?.()
    return await fn(cred.accessToken)
  }
}

export async function crearIncoming (wallet, accessToken, incomingAmount, expiresAt) {
  const c = await cliente()
  log(ETAPA.SOLICITUD, `Creando incoming payment por ${textoAmount(incomingAmount)} en ${wallet.id}, vence ${expiresAt}`)
  const ip = await c.incomingPayment.create(
    { url: wallet.resourceServer, accessToken },
    { walletAddress: wallet.id, incomingAmount, expiresAt }
  )
  log(ETAPA.SOLICITUD, `Incoming payment creado: ${ip.id}`)
  return ip
}

export async function obtenerIncoming (url, accessToken) {
  const c = await cliente()
  return c.incomingPayment.get({ url, accessToken })
}

export async function crearQuote (wallet, accessToken, receiver) {
  const c = await cliente()
  log(ETAPA.AUTORIZACION, `Creando quote desde ${wallet.id} hacia ${receiver}`)
  const q = await c.quote.create(
    { url: wallet.resourceServer, accessToken },
    { method: 'ilp', walletAddress: wallet.id, receiver }
  )
  log(ETAPA.AUTORIZACION, `Quote: debita ${textoAmount(q.debitAmount)}, recibe ${textoAmount(q.receiveAmount)}`)
  return q
}

export async function crearOutgoing (wallet, accessToken, quoteId) {
  const c = await cliente()
  log(ETAPA.EJECUCION, `Creando outgoing payment en ${wallet.id} con quote ${quoteId}`)
  const op = await c.outgoingPayment.create(
    { url: wallet.resourceServer, accessToken },
    { walletAddress: wallet.id, quoteId }
  )
  log(ETAPA.EJECUCION, `Outgoing payment creado: ${op.id}, debita ${textoAmount(op.debitAmount)}`)
  return op
}

export async function obtenerOutgoing (url, accessToken) {
  const c = await cliente()
  return c.outgoingPayment.get({ url, accessToken })
}

/** Lo gastado en el periodo vigente del grant de outgoing-payment. */
export async function gastadoGrant (wallet, accessToken) {
  const c = await cliente()
  return c.outgoingPayment.getGrantSpentAmounts({ url: wallet.resourceServer, accessToken })
}

/** Lista outgoing payments de una wallet (con list-all en el token salen todos, no solo los de esta app). */
export async function listarOutgoing (wallet, accessToken, first = 50) {
  const c = await cliente()
  return c.outgoingPayment.list({ url: wallet.resourceServer, walletAddress: wallet.id, accessToken }, { first })
}

/** Lista incoming payments de una wallet. */
export async function listarIncoming (wallet, accessToken, first = 50) {
  const c = await cliente()
  return c.incomingPayment.list({ url: wallet.resourceServer, walletAddress: wallet.id, accessToken }, { first })
}

/**
 * Grant interactivo de outgoing-payment con tope e intervalo.
 * `completo`: además pide list-all/read-all de outgoing e incoming para ver todos los movimientos
 * de la wallet (no solo los hechos con esta app). Requiere aprobación del dueño.
 */
export async function grantInteractivo (wallet, debitAmount, interval, finishUri, nonce, completo = true) {
  const c = await cliente()
  log(ETAPA.AUTORIZACION, `Pidiendo grant INTERACTIVO de outgoing-payment${completo ? ' (+ ver todos los movimientos)' : ''} para ${wallet.id}: tope ${textoAmount(debitAmount)}, intervalo ${interval}`)
  const access = [{
    type: 'outgoing-payment',
    actions: completo ? ['create', 'read', 'list', 'read-all', 'list-all'] : ['create', 'read', 'list'],
    identifier: wallet.id,
    limits: { debitAmount, interval }
  }]
  if (completo) access.push({ type: 'incoming-payment', actions: ['read', 'list', 'read-all', 'list-all'], identifier: wallet.id })
  const grant = await c.grant.request(
    { url: wallet.authServer },
    {
      access_token: { access },
      interact: {
        start: ['redirect'],
        finish: { method: 'redirect', uri: finishUri, nonce }
      }
    }
  )
  if (!isPendingGrant(grant)) throw new Error('Se esperaba un grant pendiente de interacción')
  log(ETAPA.AUTORIZACION, `Grant pendiente: el familiar debe aprobar en ${grant.interact.redirect}`)
  return grant
}

export async function continuarGrant (continueUri, continueToken, interactRef) {
  const c = await cliente()
  log(ETAPA.AUTORIZACION, 'Continuando grant con interact_ref')
  const grant = await c.grant.continue({ url: continueUri, accessToken: continueToken }, { interact_ref: interactRef })
  if (!isFinalizedGrant(grant)) throw new Error('El grant no quedó finalizado (¿el familiar lo rechazó?)')
  log(ETAPA.AUTORIZACION, 'Permiso semanal activo')
  return grant
}
