// Cuentas, medios de pago (tarjetas y celulares), migración e historial: funciones puras,
// probadas con node --test.
import { inicioPeriodoActual } from './util.mjs'

export const PREFIJO_CELULAR = 'CEL:'
const SEMANA_MS = 7 * 24 * 3600 * 1000

export const esCelular = (uid) => String(uid ?? '').startsWith(PREFIJO_CELULAR)

/** Tarjeta: "10:30:5d:e4" → "10305DE4". Celular: "cel:ABC-…" → "CEL:abc-…". */
export function normalizarUid (uid) {
  const s = String(uid ?? '').trim()
  if (/^cel:/i.test(s)) return PREFIJO_CELULAR + s.slice(4).trim().toLowerCase().replace(/[^0-9a-f-]/g, '')
  return s.replace(/[^0-9a-f]/gi, '').toUpperCase()
}

/** Nombre con el que cobra una cuenta: su negocio, o el publicName de la wallet, o su nombre. */
export const nombreNegocio = (cuenta) => cuenta?.negocio || cuenta?.publicName || cuenta?.nombre || 'Comercio'

/** Nombre por defecto: "Tarjeta N" según cuántas tarjetas tenga ya la cuenta. */
export function nombrePorDefecto (medios, cuentaId) {
  const n = Object.values(medios).filter((m) => m.cuentaId === cuentaId && m.tipo === 'tarjeta').length
  return `Tarjeta ${n + 1}`
}

/**
 * Migración compatible de datos.json (idempotente). Devuelve true si cambió algo.
 *  v1: `tarjetas: { uid: clienteId }` → `medios: { uid: { clienteId, tipo, nombre, bloqueada, … } }`
 *  v2: `clientes` → `cuentas`; medios.clienteId → cuentaId; cobros.clienteId → pagadorId;
 *      la tienda del .env pasa a ser una cuenta y es la que cobró los cobros viejos (cuentaCobroId).
 */
export function migrarDatos (d, { tienda } = {}) {
  let cambio = false
  for (const clave of ['cuentas', 'cobros']) if (!Array.isArray(d[clave])) { d[clave] = []; cambio = true }
  for (const clave of ['permisos', 'medios']) if (!d[clave] || typeof d[clave] !== 'object') { d[clave] = {}; cambio = true }

  // v2: clientes → cuentas (mismos ids: los permisos siguen indexados igual)
  if (Array.isArray(d.clientes)) {
    for (const c of d.clientes) {
      if (!d.cuentas.some((x) => x.id === c.id)) d.cuentas.push({ negocio: null, creadoEn: null, ...c })
    }
    delete d.clientes
    cambio = true
  }

  // v1: tarjetas → medios
  if (d.tarjetas) {
    for (const [uid, cuentaId] of Object.entries(d.tarjetas)) {
      if (d.medios[uid]) continue
      d.medios[uid] = { cuentaId, tipo: 'tarjeta', nombre: nombrePorDefecto(d.medios, cuentaId), bloqueada: false, creadoEn: null, ultimoUso: null }
    }
    delete d.tarjetas
    cambio = true
  }

  // v2: medios.clienteId → cuentaId
  for (const m of Object.values(d.medios)) {
    if (m.clienteId !== undefined) {
      m.cuentaId ??= m.clienteId
      delete m.clienteId
      cambio = true
    }
  }

  // v2: la tienda del .env es una cuenta más
  let tiendaId = null
  if (tienda?.walletAddress) {
    let cuenta = d.cuentas.find((c) => c.walletAddress === tienda.walletAddress)
    if (!cuenta) {
      let id = 'tienda'
      while (d.cuentas.some((c) => c.id === id)) id += '1'
      cuenta = { id, nombre: tienda.nombre || 'Tienda', negocio: tienda.nombre || null, walletAddress: tienda.walletAddress, creadoEn: null }
      d.cuentas.push(cuenta)
      cambio = true
    }
    tiendaId = cuenta.id
  }

  // v2: cobros.clienteId → pagadorId, y quién cobró
  for (const c of d.cobros) {
    if (c.clienteId !== undefined) {
      c.pagadorId ??= c.clienteId
      delete c.clienteId
      cambio = true
    }
    if (!c.cuentaCobroId && tiendaId) {
      c.cuentaCobroId = tiendaId
      cambio = true
    }
  }
  return cambio
}

/**
 * ¿Quién paga? Primero el UID registrado (así aplica el bloqueo); si no, la wallet escrita
 * en la tarjeta. Un celular solo se acepta si está vinculado (sin respaldo por wallet,
 * para que desvincularlo funcione). Devuelve { cuentaId, medio, por } o null.
 */
export function resolverMedio (d, uid, wallet) {
  const medio = d.medios[uid]
  if (medio) return { cuentaId: medio.cuentaId, medio, por: 'uid' }
  if (esCelular(uid) || !wallet) return null
  const cuenta = d.cuentas.find((c) => c.walletAddress === wallet)
  return cuenta ? { cuentaId: cuenta.id, medio: null, por: 'wallet' } : null
}

export const estaBloqueado = (resuelto) => !!resuelto?.medio?.bloqueada

/** No se puede cobrar a la propia cuenta. */
export const esMismaCuenta = (cobro, pagadorId) => !!pagadorId && cobro?.cuentaCobroId === pagadorId

/** Fecha (ISO) en que se renueva el tope semanal del intervalo R/<inicio>/P1W. */
export function fechaRenovacion (inicioIso, ahora = Date.now()) {
  return new Date(inicioPeriodoActual(inicioIso, ahora) + SEMANA_MS).toISOString()
}

/** Une pagos hechos y recibidos y los ordena del más reciente al más antiguo. */
export function combinarMovimientos (salientes, entrantes) {
  return [...salientes, ...entrantes].sort((a, b) => Date.parse(b.fecha) - Date.parse(a.fecha))
}

/** Outgoing payment de Open Payments → movimiento. `cobro` es el registro local si existe. */
export function movimientoSaliente (p, cobro, medio, negocio) {
  const enviado = BigInt(p.sentAmount?.value ?? '0')
  const debito = BigInt(p.debitAmount?.value ?? '0')
  return {
    id: p.id,
    tipo: 'pago',
    fecha: p.createdAt,
    monto: p.debitAmount,
    estado: p.failed ? 'fallido' : enviado > 0n && enviado >= BigInt(p.receiveAmount?.value ?? debito) ? 'completado' : 'en_proceso',
    contraparte: cobro ? negocio : (p.metadata?.description || 'Otro destino'),
    medio: medio?.nombre ?? null
  }
}

/** Incoming payment de Open Payments → movimiento (solo si llegó dinero). */
export function movimientoEntrante (p) {
  if (BigInt(p.receivedAmount?.value ?? '0') <= 0n) return null
  return {
    id: p.id,
    tipo: 'recibido',
    fecha: p.createdAt,
    monto: p.receivedAmount,
    estado: p.completed ? 'completado' : 'en_proceso',
    contraparte: p.metadata?.description || 'Pago recibido',
    medio: null
  }
}

/** Texto corto del estado de un cobro para los historiales. */
export function textoEstadoCobro (c, ahora = Date.now()) {
  if (c.estado === 'pagado') return 'Pagado'
  if (c.estado === 'procesando') return 'Procesando'
  if (c.estado === 'error') {
    return { CANCELADO: 'Cancelado', COBRO_VENCIDO: 'Vencido' }[c.error?.codigo] ?? `Error: ${c.error?.mensaje ?? ''}`
  }
  return ahora > Date.parse(c.expiresAt) ? 'Vencido' : 'En curso'
}

/**
 * Historial de una cuenta con los registros del servidor: cobros que hizo (todos sus estados)
 * y pagos que hizo como pagadora (solo los que se ejecutaron). Del más reciente al más antiguo.
 */
export function historialDeCuenta (d, cuentaId, ahora = Date.now()) {
  const cuenta = (id) => d.cuentas.find((c) => c.id === id)
  const movimientos = []
  for (const c of d.cobros) {
    if (c.cuentaCobroId === cuentaId) {
      movimientos.push({
        id: c.id, tipo: 'cobro', fecha: c.pagadoEn ?? c.creadoEn, monto: c.monto, estado: c.estado,
        estadoTexto: textoEstadoCobro(c, ahora), contraparte: c.pagadorId ? (cuenta(c.pagadorId)?.nombre ?? '?') : null,
        medio: c.uid && d.medios[c.uid]?.cuentaId === c.pagadorId ? d.medios[c.uid].nombre : null
      })
    }
    if (c.pagadorId === cuentaId && ['pagado', 'procesando'].includes(c.estado)) {
      movimientos.push({
        id: c.id, tipo: 'pago', fecha: c.pagadoEn ?? c.creadoEn, monto: c.debitAmount ?? c.monto, estado: c.estado,
        estadoTexto: textoEstadoCobro(c, ahora), contraparte: nombreNegocio(cuenta(c.cuentaCobroId)),
        medio: d.medios[c.uid]?.nombre ?? null
      })
    }
  }
  return movimientos.sort((a, b) => Date.parse(b.fecha) - Date.parse(a.fecha))
}

/** Totales de hoy (cobrado y pagado), en unidades mínimas como string. */
export function totalesDeHoy (movimientos, ahora = Date.now()) {
  const hoy = new Date(ahora).toDateString()
  const suma = (tipo) => movimientos
    .filter((m) => m.tipo === tipo && m.estado === 'pagado' && new Date(m.fecha).toDateString() === hoy)
    .reduce((s, m) => s + BigInt(m.monto.value), 0n)
  return { cobrado: suma('cobro').toString(), pagado: suma('pago').toString() }
}
