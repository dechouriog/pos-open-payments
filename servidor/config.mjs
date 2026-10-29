// Carga /servidor/.env (sin dependencias: process.loadEnvFile existe desde Node 20.12).
import { existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const DIR = dirname(fileURLToPath(import.meta.url))

const rutaEnv = resolve(DIR, '.env')
if (existsSync(rutaEnv)) process.loadEnvFile(rutaEnv)

// Quita espacios y <> por si se pegaron con la URL
const limpiar = (v) => (v ?? '').trim().replace(/^<|>$/g, '')

export const config = {
  appWallet: limpiar(process.env.APP_WALLET_ADDRESS),
  keyId: limpiar(process.env.KEY_ID),
  privateKeyPath: resolve(DIR, limpiar(process.env.PRIVATE_KEY_PATH) || '../private.key'),
  tiendaWallet: limpiar(process.env.TIENDA_WALLET_ADDRESS),
  // Opcionales desde que cada cuenta puede cobrar: solo se usan para migrar la tienda vieja
  tiendaNombre: limpiar(process.env.TIENDA_NOMBRE) || 'Tienda',
  port: Number(process.env.PORT || 3000)
}

/** Lista de problemas de configuración (vacía si todo está bien). Nunca lee el contenido de la llave. */
export function problemasConfig () {
  const p = []
  if (!config.appWallet) p.push('Falta APP_WALLET_ADDRESS en .env')
  if (!config.keyId || /pega[-_]?aqui/i.test(config.keyId)) p.push('Falta KEY_ID en .env (el Key ID de la llave del test wallet)')
  if (!existsSync(config.privateKeyPath)) p.push(`No existe la llave privada en ${config.privateKeyPath}`)
  return p
}
