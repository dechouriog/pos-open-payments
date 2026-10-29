// Prueba de credenciales: incoming payment de 1 dólar en la tienda + quote desde la wallet de Doña Rosa.
// Uso: node probar.mjs   (no imprime tokens ni la llave)
import { config, problemasConfig } from './config.mjs'
import * as op from './op.mjs'
import { ETAPA } from './op.mjs'
import { aUnidades } from './util.mjs'

const CLIENTA = process.env.CLIENTA_WALLET_ADDRESS || 'https://ilp.interledger-test.dev/rosa'

const problemas = problemasConfig()
if (!config.tiendaWallet) problemas.push('Falta TIENDA_WALLET_ADDRESS en .env (la wallet del comercio de prueba)')
if (problemas.length) {
  console.error('✘ Configuración incompleta:')
  for (const p of problemas) console.error('  - ' + p)
  process.exit(1)
}

let paso = 'crear el cliente autenticado'
try {
  await op.cliente()
  paso = 'leer la wallet de la tienda'
  const tienda = await op.obtenerWallet(config.tiendaWallet)
  console.log(`Tienda: ${tienda.id} (${tienda.assetCode}, assetScale ${tienda.assetScale})`)

  paso = 'pedir el grant de incoming-payment de la tienda'
  const cred = await op.grantNoInteractivo(ETAPA.SOLICITUD, tienda, 'incoming-payment', ['create', 'read'])
  paso = 'crear el incoming payment de 1 dólar'
  const monto = { value: aUnidades('1', tienda.assetScale), assetCode: tienda.assetCode, assetScale: tienda.assetScale }
  const ip = await op.crearIncoming(tienda, cred.accessToken, monto, new Date(Date.now() + 60_000).toISOString())

  paso = 'leer la wallet de la clienta'
  const rosa = await op.obtenerWallet(CLIENTA)
  console.log(`Clienta: ${rosa.id} (${rosa.assetCode}, assetScale ${rosa.assetScale})`)
  paso = 'pedir el grant de quote de la clienta'
  const credQ = await op.grantNoInteractivo(ETAPA.AUTORIZACION, rosa, 'quote', ['create', 'read'])
  paso = 'crear el quote'
  await op.crearQuote(rosa, credQ.accessToken, ip.id)

  console.log('\n✔ Las credenciales funcionan: incoming payment y quote creados (no se movió dinero).')
} catch (e) {
  console.error(`\n✘ Falló al ${paso}:`)
  console.error('  ' + op.describirError(e))
  process.exit(1)
}
