import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { test } from 'node:test'
import { aUnidades, formatearMonto, inicioPeriodoActual, normalizarWallet, verificarHash } from './util.mjs'

test('aUnidades usa aritmética entera', () => {
  assert.equal(aUnidades('44', 2), '4400')
  assert.equal(aUnidades(44, 2), '4400')
  assert.equal(aUnidades('12.5', 2), '1250')
  assert.equal(aUnidades('12.50', 2), '1250')
  assert.equal(aUnidades('0.01', 2), '1')
  assert.equal(aUnidades('0.29', 2), '29') // 0.29 * 100 en flotante da 28.999…
  assert.equal(aUnidades('1,5', 2), '150')
  assert.equal(aUnidades('7', 0), '7')
  assert.throws(() => aUnidades('1.234', 2), /decimales/)
  assert.throws(() => aUnidades('-3', 2))
  assert.throws(() => aUnidades('abc', 2))
})

test('formatearMonto', () => {
  assert.equal(formatearMonto('4400', 'USD', 2), '$44.00 USD')
  assert.equal(formatearMonto('5', 'USD', 2), '$0.05 USD')
  assert.equal(formatearMonto(20000n, 'USD', 2), '$200.00 USD')
  assert.equal(formatearMonto('7', 'XYZ', 0), '7 XYZ')
})

test('verificarHash acepta el hash correcto y rechaza otro', () => {
  const base = 'a\nb\nref\nhttps://auth.ejemplo'
  const hash = createHash('sha256').update(base).digest('base64')
  const args = { nonceCliente: 'a', nonceServidor: 'b', interactRef: 'ref', urlGrant: 'https://auth.ejemplo/' }
  assert.equal(verificarHash({ ...args, hash }), true)
  assert.equal(verificarHash({ ...args, hash: hash.replace(/=+$/, '') }), true)
  assert.equal(verificarHash({ ...args, hash: 'otro' }), false)
  assert.equal(verificarHash({ ...args, interactRef: 'x', hash }), false)
})

test('normalizarWallet', () => {
  assert.equal(normalizarWallet('$ilp.interledger-test.dev/dona-rosa'), 'https://ilp.interledger-test.dev/dona-rosa')
  assert.equal(normalizarWallet(' <https://x.dev/a/> '), 'https://x.dev/a')
})

test('inicioPeriodoActual avanza por semanas', () => {
  const ini = '2026-10-01T00:00:00.000Z'
  const semana = 7 * 24 * 3600 * 1000
  assert.equal(inicioPeriodoActual(ini, Date.parse(ini) + 3 * 24 * 3600 * 1000), Date.parse(ini))
  assert.equal(inicioPeriodoActual(ini, Date.parse(ini) + semana + 1), Date.parse(ini) + semana)
})
