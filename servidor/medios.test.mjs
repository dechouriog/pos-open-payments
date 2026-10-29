import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  combinarMovimientos, esCelular, esMismaCuenta, estaBloqueado, fechaRenovacion, historialDeCuenta,
  migrarDatos, movimientoEntrante, movimientoSaliente, nombreNegocio, nombrePorDefecto, normalizarUid,
  resolverMedio, totalesDeHoy
} from './medios.mjs'

const usd = (value) => ({ value, assetCode: 'USD', assetScale: 2 })
const tienda = { walletAddress: 'https://ilp.interledger-test.dev/josep', nombre: 'Tienda Don José' }

/** Datos con el formato que tenía datos.json antes de las cuentas. */
function datosViejos () {
  return {
    clientes: [{ id: 'fa96746d', nombre: 'Doña Rosa', walletAddress: 'https://ilp.interledger-test.dev/rosa' }],
    medios: {
      '10305DE4': { clienteId: 'fa96746d', tipo: 'tarjeta', nombre: 'Tarjeta 1', bloqueada: false, creadoEn: null, ultimoUso: null },
      'CEL:abc': { clienteId: 'fa96746d', tipo: 'celular', nombre: 'A53', bloqueada: true, creadoEn: null, ultimoUso: null }
    },
    permisos: { fa96746d: { accessToken: 'x', alcance: 'completo' } },
    cobros: [
      { id: 'c1', estado: 'pagado', clienteId: 'fa96746d', monto: usd('500') },
      { id: 'c2', estado: 'error', monto: usd('100') }
    ]
  }
}

test('migración v2: clientes y tienda pasan a cuentas sin perder permisos, tarjetas ni historial', () => {
  const d = datosViejos()
  assert.equal(migrarDatos(d, { tienda }), true)
  assert.equal(d.clientes, undefined)
  // Doña Rosa conserva su id (los permisos siguen indexados igual)
  assert.equal(d.cuentas[0].id, 'fa96746d')
  assert.equal(d.permisos.fa96746d.accessToken, 'x')
  // La tienda es una cuenta más
  const t = d.cuentas.find((c) => c.walletAddress === tienda.walletAddress)
  assert.equal(t.id, 'tienda')
  assert.equal(t.negocio, 'Tienda Don José')
  // Medios: clienteId → cuentaId, con su estado
  assert.deepEqual(d.medios['10305DE4'], { tipo: 'tarjeta', nombre: 'Tarjeta 1', bloqueada: false, creadoEn: null, ultimoUso: null, cuentaId: 'fa96746d' })
  assert.equal(d.medios['CEL:abc'].bloqueada, true)
  // Cobros: clienteId → pagadorId y los cobró la tienda
  assert.equal(d.cobros[0].pagadorId, 'fa96746d')
  assert.equal(d.cobros[0].clienteId, undefined)
  assert.equal(d.cobros[0].cuentaCobroId, 'tienda')
  assert.equal(d.cobros[1].cuentaCobroId, 'tienda')
  // Idempotente
  assert.equal(migrarDatos(d, { tienda }), false)
  assert.equal(d.cuentas.length, 2)
})

test('migración: si la wallet de la tienda ya es una cuenta, no la duplica', () => {
  const d = datosViejos()
  d.clientes.push({ id: 'jose1', nombre: 'José', walletAddress: tienda.walletAddress })
  migrarDatos(d, { tienda })
  assert.equal(d.cuentas.length, 2)
  assert.equal(d.cobros[1].cuentaCobroId, 'jose1')
})

test('migración v1: tarjetas viejas → medios (formato más antiguo)', () => {
  const d = { clientes: [{ id: 'r', nombre: 'R', walletAddress: 'https://w/r' }], tarjetas: { AA: 'r', BB: 'r' }, permisos: {}, cobros: [] }
  migrarDatos(d)
  assert.equal(d.tarjetas, undefined)
  assert.equal(d.medios.AA.cuentaId, 'r')
  assert.equal(d.medios.BB.nombre, 'Tarjeta 2')
})

test('migración sin tienda en el .env', () => {
  const d = datosViejos()
  migrarDatos(d)
  assert.equal(d.cuentas.length, 1)
  assert.equal(d.cobros[0].cuentaCobroId, undefined)
})

test('normalizarUid distingue tarjetas y celulares', () => {
  assert.equal(normalizarUid('10:30:5d:e4'), '10305DE4')
  assert.equal(normalizarUid('cel:ABCDEF12-3456'), 'CEL:abcdef12-3456')
  assert.equal(esCelular('CEL:abc'), true)
  assert.equal(esCelular('10305DE4'), false)
})

test('nombreNegocio: negocio, si no publicName, si no nombre', () => {
  assert.equal(nombreNegocio({ nombre: 'José', negocio: 'Tienda Don José', publicName: 'josep' }), 'Tienda Don José')
  assert.equal(nombreNegocio({ nombre: 'José', negocio: null, publicName: 'Don Jose' }), 'Don Jose')
  assert.equal(nombreNegocio({ nombre: 'José' }), 'José')
})

test('nombrePorDefecto numera las tarjetas de la cuenta', () => {
  const medios = { A: { cuentaId: 'x', tipo: 'tarjeta' }, 'CEL:1': { cuentaId: 'x', tipo: 'celular' }, B: { cuentaId: 'y', tipo: 'tarjeta' } }
  assert.equal(nombrePorDefecto(medios, 'x'), 'Tarjeta 2')
  assert.equal(nombrePorDefecto(medios, 'z'), 'Tarjeta 1')
})

test('resolverMedio: primero UID registrado, luego wallet; celulares solo si están vinculados', () => {
  const d = {
    cuentas: [{ id: 'r', walletAddress: 'https://w/rosa' }, { id: 'j', walletAddress: 'https://w/jose' }],
    medios: {
      '10305DE4': { cuentaId: 'r', tipo: 'tarjeta', nombre: 'Tarjeta 1', bloqueada: false },
      'CEL:abc': { cuentaId: 'r', tipo: 'celular', nombre: 'A53', bloqueada: true }
    }
  }
  assert.equal(resolverMedio(d, '10305DE4', 'https://w/jose').cuentaId, 'r')
  assert.deepEqual(resolverMedio(d, 'FFFF', 'https://w/jose'), { cuentaId: 'j', medio: null, por: 'wallet' })
  assert.equal(resolverMedio(d, 'FFFF', null), null)
  assert.equal(resolverMedio(d, 'CEL:zzz', 'https://w/rosa'), null)
  assert.equal(resolverMedio(d, 'CEL:abc', null).cuentaId, 'r')
})

test('estaBloqueado', () => {
  assert.equal(estaBloqueado({ medio: { bloqueada: true } }), true)
  assert.equal(estaBloqueado({ medio: { bloqueada: false } }), false)
  assert.equal(estaBloqueado({ medio: null, por: 'wallet' }), false)
  assert.equal(estaBloqueado(null), false)
})

test('MISMA_CUENTA: no se puede cobrar a la propia cuenta', () => {
  assert.equal(esMismaCuenta({ cuentaCobroId: 'r' }, 'r'), true)
  assert.equal(esMismaCuenta({ cuentaCobroId: 'tienda' }, 'r'), false)
  assert.equal(esMismaCuenta({ cuentaCobroId: 'r' }, null), false)
})

test('fechaRenovacion es el fin del periodo semanal vigente', () => {
  const ini = '2026-10-15T23:47:05.000Z'
  assert.equal(fechaRenovacion(ini, Date.parse('2026-10-16T10:00:00Z')), '2026-10-22T23:47:05.000Z')
  assert.equal(fechaRenovacion(ini, Date.parse('2026-10-23T00:00:00Z')), '2026-10-29T23:47:05.000Z')
})

test('historialDeCuenta: cobros propios y pagos hechos, con signo de tipo y del más reciente al más antiguo', () => {
  const d = {
    cuentas: [
      { id: 'r', nombre: 'Doña Rosa', negocio: 'Arepas Rosa' },
      { id: 't', nombre: 'José', negocio: 'Tienda Don José' }
    ],
    medios: { T1: { cuentaId: 'r', nombre: 'Tarjeta 1' } },
    cobros: [
      { id: 'a', cuentaCobroId: 't', pagadorId: 'r', uid: 'T1', estado: 'pagado', monto: usd('500'), debitAmount: usd('500'), creadoEn: '2026-10-15T23:48:00Z', pagadoEn: '2026-10-15T23:48:45Z' },
      { id: 'b', cuentaCobroId: 'r', pagadorId: 't', estado: 'pagado', monto: usd('1200'), creadoEn: '2026-10-16T01:00:00Z', pagadoEn: '2026-10-16T01:00:10Z' },
      { id: 'c', cuentaCobroId: 't', pagadorId: 'r', estado: 'error', error: { codigo: 'TOPE_SUPERADO' }, monto: usd('9900'), creadoEn: '2026-10-16T02:00:00Z' },
      { id: 'd', cuentaCobroId: 'r', estado: 'error', error: { codigo: 'CANCELADO' }, monto: usd('100'), creadoEn: '2026-10-16T03:00:00Z' }
    ]
  }
  const h = historialDeCuenta(d, 'r', Date.parse('2026-10-16T04:00:00Z'))
  // El pago fallido de Rosa no aparece como pago; sus cobros aparecen todos
  assert.deepEqual(h.map((m) => `${m.tipo}:${m.id}`), ['cobro:d', 'cobro:b', 'pago:a'])
  assert.equal(h.find((m) => m.id === 'a').contraparte, 'Tienda Don José')
  assert.equal(h.find((m) => m.id === 'a').medio, 'Tarjeta 1')
  assert.equal(h.find((m) => m.id === 'b').contraparte, 'José')
  assert.equal(h.find((m) => m.id === 'd').estadoTexto, 'Cancelado')
  const tot = totalesDeHoy(h, Date.parse('2026-10-16T01:30:00Z'))
  assert.equal(tot.cobrado, '1200')
})

test('combinarMovimientos ordena del más reciente al más antiguo', () => {
  const r = combinarMovimientos(
    [{ id: 'p1', fecha: '2026-10-15T23:48:45Z' }, { id: 'p2', fecha: '2026-10-16T00:16:05Z' }],
    [{ id: 'i1', fecha: '2026-10-15T23:00:00Z' }, { id: 'i2', fecha: '2026-10-16T01:00:00Z' }]
  )
  assert.deepEqual(r.map((m) => m.id), ['i2', 'p2', 'p1', 'i1'])
})

test('movimientoSaliente y movimientoEntrante', () => {
  const o = { id: 'o1', createdAt: '2026-10-16T00:16:05Z', debitAmount: usd('500'), receiveAmount: usd('500'), sentAmount: usd('500'), failed: false }
  const m = movimientoSaliente(o, { id: 'c1' }, { nombre: 'Tarjeta 1' }, 'Tienda Don José')
  assert.equal(m.estado, 'completado')
  assert.equal(m.contraparte, 'Tienda Don José')
  assert.equal(movimientoSaliente({ ...o, failed: true }, null, null, 'T').estado, 'fallido')
  assert.equal(movimientoEntrante({ id: 'i', createdAt: 'x', receivedAmount: usd('0'), completed: false }), null)
  assert.equal(movimientoEntrante({ id: 'i', createdAt: 'x', receivedAmount: usd('500'), completed: true }).tipo, 'recibido')
})
