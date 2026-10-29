// Funciones puras: montos con aritmética entera, hash de interacción y fechas.
import { createHash } from 'node:crypto'

/** Error con código para la app y la página del familiar. */
export class ErrorApp extends Error {
  constructor (codigo, mensaje, status = 400, extra = {}) {
    super(mensaje)
    this.codigo = codigo
    this.status = status
    this.extra = extra
  }
}

const SIMBOLOS = { USD: '$', CAD: '$', COP: '$', MXN: '$', EUR: '€', GBP: '£' }

/** "44" | "12.5" | 12.5 → unidades mínimas como string ("4400", "1250"). Sin flotantes. */
export function aUnidades (monto, assetScale) {
  const s = String(monto ?? '').trim().replace(',', '.')
  if (!/^\d+(\.\d+)?$/.test(s)) {
    throw new ErrorApp('MONTO_INVALIDO', `Monto no válido: "${monto}"`)
  }
  const [entero, decimales = ''] = s.split('.')
  if (decimales.length > assetScale) {
    throw new ErrorApp('MONTO_INVALIDO', `El monto admite máximo ${assetScale} decimales`)
  }
  const base = 10n ** BigInt(assetScale)
  const fraccion = BigInt(decimales.padEnd(assetScale, '0') || '0')
  return (BigInt(entero) * base + fraccion).toString()
}

/** "4400", 2 → "44.00" */
export function aDecimal (valor, assetScale) {
  const v = BigInt(valor)
  const abs = v < 0n ? -v : v
  const base = 10n ** BigInt(assetScale)
  const fraccion = assetScale > 0 ? '.' + (abs % base).toString().padStart(assetScale, '0') : ''
  return `${v < 0n ? '-' : ''}${abs / base}${fraccion}`
}

/** "4400", "USD", 2 → "$44.00 USD" */
export function formatearMonto (valor, assetCode, assetScale) {
  const d = aDecimal(valor, assetScale)
  const signo = d.startsWith('-') ? '-' : ''
  return `${signo}${SIMBOLOS[assetCode] ?? ''}${d.replace('-', '')} ${assetCode}`
}

/** Monto de Open Payments ({ value, assetCode, assetScale }) → "$44.00 USD" */
export const formatearAmount = (a) => formatearMonto(a.value, a.assetCode, a.assetScale)

/** Para la consola: "$44.00 USD (value=4400, assetScale=2)" */
export const textoAmount = (a) => `${formatearAmount(a)} (value=${a.value}, assetScale=${a.assetScale})`

/**
 * Verifica el hash del redirect de interacción (openpayments.dev/identity/hash-verification):
 * sha-256 de "nonceCliente\nnonceServidor\ninteractRef\nurlGrant" en Base64.
 * La documentación dice "sin padding" pero su ejemplo usa digest('base64') (con padding),
 * y no aclara la barra final de la URL, así que se aceptan esas variantes.
 */
export function verificarHash ({ nonceCliente, nonceServidor, interactRef, urlGrant, hash }) {
  if (!hash) return false
  const urls = new Set([urlGrant, urlGrant.replace(/\/+$/, ''), urlGrant.replace(/\/*$/, '/')])
  for (const url of urls) {
    const digest = createHash('sha256')
      .update(`${nonceCliente}\n${nonceServidor}\n${interactRef}\n${url}`)
      .digest()
    const candidatos = [
      digest.toString('base64'),
      digest.toString('base64').replace(/=+$/, ''),
      digest.toString('base64url')
    ]
    if (candidatos.includes(hash)) return true
  }
  return false
}

/** "$ilp.ejemplo/x" o "https://ilp.ejemplo/x/" → "https://ilp.ejemplo/x" */
export function normalizarWallet (url) {
  let s = String(url ?? '').trim().replace(/^<|>$/g, '')
  if (s.startsWith('$')) s = 'https://' + s.slice(1)
  return s.replace(/\/+$/, '')
}

const SEMANA_MS = 7 * 24 * 3600 * 1000

/** Inicio (ms) del periodo semanal vigente de un intervalo R/<inicio>/P1W. */
export function inicioPeriodoActual (inicioIso, ahora = Date.now()) {
  const inicio = Date.parse(inicioIso)
  if (ahora < inicio) return inicio
  return inicio + Math.floor((ahora - inicio) / SEMANA_MS) * SEMANA_MS
}
