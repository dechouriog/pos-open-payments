// Persistencia simple en datos.json: cuentas, medios de pago, permisos y cobros.
import { existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { DIR, config } from './config.mjs'
import { migrarDatos } from './medios.mjs'

const RUTA = resolve(DIR, 'datos.json')

export const datos = existsSync(RUTA)
  ? JSON.parse(readFileSync(RUTA, 'utf8'))
  : { cuentas: [], medios: {}, permisos: {}, cobros: [] }

export function guardar () {
  // Escribe a un temporal y renombra para no dejar el archivo a medias
  writeFileSync(RUTA + '.tmp', JSON.stringify(datos, null, 2))
  renameSync(RUTA + '.tmp', RUTA)
}

// Formatos viejos (tarjetas, clientes, tienda del .env) → cuentas y medios
const tienda = config.tiendaWallet ? { walletAddress: config.tiendaWallet, nombre: config.tiendaNombre } : null
if (migrarDatos(datos, { tienda })) guardar()

export const buscarCuenta = (id) => datos.cuentas.find((c) => c.id === id)
