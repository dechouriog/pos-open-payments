// Servidor de la app de pagos: único que tiene las llaves y habla con Open Payments.
// Cada cuenta puede cobrar (datáfono) y pagar (con tarjeta o con el celular).
import { randomUUID } from 'node:crypto'
import express from 'express'
import { config, problemasConfig } from './config.mjs'
import { buscarCuenta, datos, guardar } from './datos.mjs'
import { intentos, registrarRutasBilletera } from './billetera.mjs'
import {
  esCelular, esMismaCuenta, estaBloqueado, historialDeCuenta, nombreNegocio, nombrePorDefecto,
  normalizarUid, resolverMedio, textoEstadoCobro, totalesDeHoy
} from './medios.mjs'
import * as op from './op.mjs'
import { ETAPA, log } from './op.mjs'
import { paginaAprobacion, paginaFamiliar, paginaMensaje } from './paginas.mjs'
import { estadoPermiso, solicitarPermiso } from './permisos.mjs'
import {
  ErrorApp, aUnidades, formatearAmount, formatearMonto, normalizarWallet, textoAmount, verificarHash
} from './util.mjs'

const app = express()
app.use(express.json())
app.use(express.urlencoded({ extended: false }))
// HttpURLConnection de Android no admite PATCH: la app manda POST con X-HTTP-Method-Override
app.use((req, res, next) => {
  const metodo = req.get('X-HTTP-Method-Override')
  if (req.method === 'POST' && metodo) req.method = metodo.toUpperCase()
  next()
})

// Envuelve rutas async para que los errores lleguen al manejador
const ruta = (fn) => (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next)

function cuentaObligatoria (id) {
  const cuenta = buscarCuenta(String(id ?? ''))
  if (!cuenta) throw new ErrorApp('CUENTA_NO_ENCONTRADA', 'Cuenta no encontrada', 404)
  return cuenta
}

/** Wallet de la cuenta; guarda assetCode, assetScale y publicName la primera vez. */
async function walletDeCuenta (cuenta) {
  const w = await op.obtenerWallet(cuenta.walletAddress)
  if (cuenta.assetCode !== w.assetCode || cuenta.assetScale !== w.assetScale || cuenta.publicName !== (w.publicName ?? null)) {
    Object.assign(cuenta, { assetCode: w.assetCode, assetScale: w.assetScale, publicName: w.publicName ?? null })
    guardar()
  }
  return w
}

const vistaCuenta = (c) => ({
  id: c.id,
  nombre: c.nombre,
  walletAddress: c.walletAddress,
  negocio: c.negocio ?? null,
  negocioTexto: nombreNegocio(c),
  publicName: c.publicName ?? null,
  assetCode: c.assetCode ?? null,
  assetScale: c.assetScale ?? null,
  tienePermiso: !!datos.permisos[c.id]?.accessToken && !datos.permisos[c.id]?.invalidoDesde
})

// ---------------- Página del familiar ----------------

app.get('/', (req, res) => res.redirect('/familiar'))

app.get('/familiar', ruta(async (req, res) => {
  const cuentas = []
  for (const c of datos.cuentas) cuentas.push({ ...c, permiso: await estadoPermiso(c) })
  res.send(paginaFamiliar({ clientes: cuentas, pagos: historialFamiliar(), aviso: req.query.aviso, ok: req.query.ok }))
}))

app.post('/familiar/clientes', ruta(async (req, res) => {
  const nombre = String(req.body.nombre ?? '').trim()
  const walletAddress = normalizarWallet(req.body.walletAddress)
  if (!nombre || !walletAddress) return res.redirect('/familiar?aviso=' + encodeURIComponent('Escribe el nombre y la wallet address'))
  try {
    await crearOActualizarCuenta({ nombre, walletAddress })
  } catch (e) {
    return res.redirect('/familiar?aviso=' + encodeURIComponent(e instanceof ErrorApp ? e.message : op.describirError(e)))
  }
  res.redirect('/familiar?ok=' + encodeURIComponent(`Cuenta ${nombre} guardada`))
}))

app.post('/familiar/aprobar', ruta(async (req, res) => {
  const cuenta = buscarCuenta(req.body.cliente)
  if (!cuenta) return res.redirect('/familiar?aviso=Cuenta%20no%20encontrada')
  try {
    const enlace = await solicitarPermiso(cuenta, req.body.tope)
    // El dueño de la wallet puede estar en otro computador: se muestra el enlace en vez de redirigir
    res.send(paginaAprobacion({ cliente: cuenta, enlace }))
  } catch (e) {
    const msg = e instanceof ErrorApp ? e.message : op.describirError(e)
    log(ETAPA.AUTORIZACION, `Error pidiendo el permiso: ${msg}`)
    res.redirect('/familiar?aviso=' + encodeURIComponent(`No se pudo pedir el permiso: ${msg}`))
  }
}))

app.get('/familiar/callback', ruta(async (req, res) => {
  const { cliente, interact_ref: interactRef, hash, result, message } = req.query
  res.send(await completarPermiso(cliente, interactRef, hash, result, message))
}))

// El familiar pega la URL a la que llegó el dueño de la wallet tras aceptar
app.post('/familiar/completar', ruta(async (req, res) => {
  let url
  try {
    url = new URL(String(req.body.url ?? '').trim())
  } catch {
    return res.send(paginaMensaje('Permiso', 'Esa dirección no es válida. Pega la dirección completa que aparece en el navegador de tu amigo.', true))
  }
  const q = url.searchParams
  log(ETAPA.AUTORIZACION, `Dirección pegada: ${url.origin}${url.pathname} con parámetros [${[...q.keys()].join(', ')}]`)
  res.send(await completarPermiso(req.body.cliente || q.get('cliente'), q.get('interact_ref'), q.get('hash'), q.get('result'), q.get('message')))
}))

/** Verifica el hash, continúa el grant y guarda el token. Devuelve la página de resultado. */
async function completarPermiso (cuentaId, interactRef, hash, result, message) {
  const cuenta = buscarCuenta(cuentaId)
  const p = cuenta && datos.permisos[cuenta.id]
  const pend = p?.pendiente
  if (!pend) return paginaMensaje('Permiso', 'No hay un permiso pendiente para esta cuenta.', true)
  if (result === 'grant_rejected') {
    delete p.pendiente
    guardar()
    return paginaMensaje('Permiso rechazado', 'El dueño de la wallet rechazó el permiso.', true)
  }
  if (result) {
    // Otro error de la wallet (p. ej. invalid_interaction: el enlace ya se había usado o venció)
    log(ETAPA.AUTORIZACION, `La wallet devolvió result=${result} (${message ?? ''})`)
    delete p.pendiente
    guardar()
    return paginaMensaje('Permiso no completado', `La wallet respondió "${message || result}". Pasa cuando el enlace se abre después de haberse usado (o ya venció). Pide el permiso otra vez y abre el enlace nuevo una sola vez.`, true)
  }
  if (!interactRef) {
    // Se conserva el permiso pendiente para poder pegar la dirección correcta
    return paginaMensaje('Permiso', 'Esa dirección no trae "interact_ref". Debe ser la dirección completa que quedó en el navegador después de aceptar, y empieza por http://localhost:3000/familiar/callback?', true)
  }
  const hashOk = verificarHash({
    nonceCliente: pend.nonce, nonceServidor: pend.nonceServidor, interactRef, urlGrant: pend.urlGrant, hash
  })
  if (!hashOk) {
    log(ETAPA.AUTORIZACION, 'Hash de interacción NO coincide: se rechaza el callback')
    return paginaMensaje('Permiso', 'La respuesta de la wallet no pasó la verificación de seguridad (hash). Intenta aprobar de nuevo.', true)
  }
  log(ETAPA.AUTORIZACION, 'Hash de interacción verificado')
  try {
    const grant = await op.continuarGrant(pend.continueUri, pend.continueToken, interactRef)
    datos.permisos[cuenta.id] = {
      accessToken: grant.access_token.value,
      manageUrl: grant.access_token.manage,
      tope: pend.tope,
      interval: pend.interval,
      inicio: pend.inicio,
      alcance: pend.alcance ?? 'basico',
      aprobadoEn: new Date().toISOString()
    }
    guardar()
    log(ETAPA.AUTORIZACION, `Permiso activo para ${cuenta.nombre}: tope ${textoAmount(pend.tope)} por semana`)
    return paginaMensaje('Permiso activo', `Permiso activo para ${cuenta.nombre}: hasta ${formatearAmount(pend.tope)} por semana. Ya puede volver a la app.`, false)
  } catch (e) {
    log(ETAPA.AUTORIZACION, `Error continuando el grant: ${op.describirError(e)}`)
    return paginaMensaje('Permiso', `No se pudo activar el permiso: ${op.describirError(e)}`, true)
  }
}

// ---------------- Cuentas ----------------

/** Crea la cuenta, o devuelve la existente si esa wallet ya tiene una (así se recupera desde otro celular). */
async function crearOActualizarCuenta ({ nombre, walletAddress, negocio }) {
  const wallet = normalizarWallet(walletAddress)
  if (!wallet) throw new ErrorApp('WALLET_INVALIDA', 'Escriba la wallet address')
  let w
  try {
    w = await op.obtenerWallet(wallet)
  } catch (e) {
    throw new ErrorApp('WALLET_INVALIDA', `No se encontró la wallet ${wallet}`, 400, { detalle: op.describirError(e) })
  }
  let cuenta = datos.cuentas.find((c) => c.walletAddress === w.id || c.walletAddress === wallet)
  const nueva = !cuenta
  if (nueva) {
    cuenta = { id: randomUUID().slice(0, 8), nombre: nombre || w.publicName || 'Sin nombre', walletAddress: w.id, negocio: null, creadoEn: new Date().toISOString() }
    datos.cuentas.push(cuenta)
  } else if (nombre) {
    cuenta.nombre = nombre
  }
  if (negocio !== undefined) cuenta.negocio = String(negocio ?? '').trim() || null
  Object.assign(cuenta, { assetCode: w.assetCode, assetScale: w.assetScale, publicName: w.publicName ?? null })
  guardar()
  log('CUENTAS', `${nueva ? 'Cuenta creada' : 'Cuenta existente'}: ${cuenta.nombre} (${cuenta.walletAddress}, ${w.assetCode}/${w.assetScale})`)
  return { cuenta, nueva }
}

app.post('/api/cuentas', ruta(async (req, res) => {
  const { cuenta, nueva } = await crearOActualizarCuenta({
    nombre: String(req.body.nombre ?? '').trim(),
    walletAddress: req.body.walletAddress,
    negocio: req.body.negocio
  })
  res.status(nueva ? 201 : 200).json({ ...vistaCuenta(cuenta), nueva })
}))

app.get('/api/cuentas/:id', ruta(async (req, res) => {
  const cuenta = cuentaObligatoria(req.params.id)
  if (!cuenta.assetCode) await walletDeCuenta(cuenta).catch(() => null)
  res.json(vistaCuenta(cuenta))
}))

app.patch('/api/cuentas/:id', (req, res) => {
  const cuenta = cuentaObligatoria(req.params.id)
  const nombre = String(req.body.nombre ?? '').trim()
  if (nombre) cuenta.nombre = nombre.slice(0, 60)
  if (req.body.negocio !== undefined) cuenta.negocio = String(req.body.negocio ?? '').trim().slice(0, 60) || null
  guardar()
  res.json(vistaCuenta(cuenta))
})

/** Historial de la cuenta: cobros que hizo y pagos que hizo, con totales de hoy. */
app.get('/api/cuentas/:id/historial', (req, res) => {
  const cuenta = cuentaObligatoria(req.params.id)
  const movimientos = historialDeCuenta(datos, cuenta.id)
  const totales = totalesDeHoy(movimientos)
  const formato = (v) => cuenta.assetCode ? formatearMonto(v, cuenta.assetCode, cuenta.assetScale) : v
  res.json({
    hoy: { cobradoTexto: formato(totales.cobrado), pagadoTexto: formato(totales.pagado) },
    movimientos: movimientos.slice(0, 60).map((m) => ({ ...m, montoTexto: formatearAmount(m.monto) }))
  })
})

// ---------------- API del datáfono ----------------

/** Datos para cobrar con la cuenta de este celular (por compatibilidad, sin ?cuenta= usa la tienda migrada). */
app.get('/api/config', ruta(async (req, res) => {
  const cuenta = cuentaQueCobra(req.query.cuenta)
  const w = await walletDeCuenta(cuenta)
  res.json({ tienda: nombreNegocio(cuenta), assetCode: w.assetCode, assetScale: w.assetScale, walletAddress: w.id, cuentaId: cuenta.id })
}))

function cuentaQueCobra (cuentaId) {
  if (cuentaId) return cuentaObligatoria(cuentaId)
  const tienda = config.tiendaWallet && datos.cuentas.find((c) => c.walletAddress === normalizarWallet(config.tiendaWallet))
  if (!tienda) throw new ErrorApp('CUENTA_NO_ENCONTRADA', 'Falta la cuenta que cobra', 400)
  return tienda
}

app.get('/api/clientes', (req, res) => res.json(datos.cuentas.map(vistaCuenta)))

/** ¿A quién pertenece esta tarjeta? Por UID registrado o por la wallet escrita en ella (?wallet=). */
app.get('/api/tarjetas/:uid', (req, res) => {
  const uid = normalizarUid(req.params.uid)
  const wallet = req.query.wallet ? normalizarWallet(req.query.wallet) : null
  const r = resolverMedio(datos, uid, wallet)
  const cuenta = r ? buscarCuenta(r.cuentaId) : null
  res.json({
    uid,
    registrada: !!cuenta,
    por: r?.por ?? null,
    tipo: esCelular(uid) ? 'celular' : 'tarjeta',
    nombre: r?.medio?.nombre ?? null,
    bloqueada: !!r?.medio?.bloqueada,
    cliente: cuenta ? { id: cuenta.id, nombre: cuenta.nombre } : null
  })
})

app.post('/api/tarjetas', (req, res) => {
  const uid = normalizarUid(req.body.uid)
  const cuenta = buscarCuenta(req.body.clienteId ?? req.body.cuentaId)
  if (!uid) throw new ErrorApp('UID_INVALIDO', 'Falta el UID de la tarjeta')
  if (!cuenta) throw new ErrorApp('CUENTA_NO_ENCONTRADA', 'Cuenta no encontrada', 404)
  if (esCelular(uid)) throw new ErrorApp('ES_CELULAR', 'Es un celular: se vincula desde la propia app en modo Pagar', 400)
  const previo = datos.medios[uid]
  datos.medios[uid] = previo
    ? { ...previo, cuentaId: cuenta.id }
    : { cuentaId: cuenta.id, tipo: 'tarjeta', nombre: nombrePorDefecto(datos.medios, cuenta.id), bloqueada: false, creadoEn: new Date().toISOString(), ultimoUso: null }
  guardar()
  log('TARJETAS', `Tarjeta ${uid} asociada a ${cuenta.nombre}`)
  res.json({ ok: true, uid, cliente: cuenta.nombre })
})

/** [①] Crea el cobro en la wallet de la cuenta que cobra: grant de incoming-payment + incoming payment de 60 s. */
app.post('/api/cobros', ruta(async (req, res) => {
  const cobradora = cuentaQueCobra(req.body.cuentaId)
  const w = await walletDeCuenta(cobradora)
  const value = aUnidades(req.body.monto, w.assetScale)
  if (BigInt(value) <= 0n) throw new ErrorApp('MONTO_INVALIDO', 'El monto debe ser mayor que cero')
  const monto = { value, assetCode: w.assetCode, assetScale: w.assetScale }
  log(ETAPA.SOLICITUD, `Nuevo cobro de ${textoAmount(monto)} para ${nombreNegocio(cobradora)} (${w.id})`)
  const credTienda = await op.grantNoInteractivo(ETAPA.SOLICITUD, w, 'incoming-payment', ['create', 'read', 'list', 'complete'])
  const expiresAt = new Date(Date.now() + 60_000).toISOString()
  const ip = await op.crearIncoming(w, credTienda.accessToken, monto, expiresAt)
  const cobro = {
    id: randomUUID().slice(0, 8),
    creadoEn: new Date().toISOString(),
    estado: 'esperando_tarjeta',
    cuentaCobroId: cobradora.id,
    monto,
    incomingPaymentId: ip.id,
    expiresAt: ip.expiresAt ?? expiresAt,
    credTienda
  }
  datos.cobros.push(cobro)
  guardar()
  res.json(vistaCobro(cobro))
}))

function obtenerCobro (id) {
  const cobro = datos.cobros.find((c) => c.id === id)
  if (!cobro) throw new ErrorApp('COBRO_NO_ENCONTRADO', 'Cobro no encontrado', 404)
  return cobro
}

function marcarError (cobro, codigo, mensaje, extra = {}) {
  cobro.estado = 'error'
  cobro.error = { codigo, mensaje, ...extra }
  cobro.finalizadoEn = new Date().toISOString()
  guardar()
}

function revisarVencido (cobro) {
  if (['esperando_tarjeta', 'esperando_confirmacion'].includes(cobro.estado) && Date.now() > Date.parse(cobro.expiresAt)) {
    marcarError(cobro, 'COBRO_VENCIDO', 'El cobro venció. Marque el valor de nuevo.')
  }
  if (cobro.error?.codigo === 'COBRO_VENCIDO') throw new ErrorApp('COBRO_VENCIDO', cobro.error.mensaje, 410)
}

/** [②] Primera lectura: resolver quién paga, permiso y quote. */
app.post('/api/cobros/:id/tarjeta', ruta(async (req, res) => {
  const cobro = obtenerCobro(req.params.id)
  revisarVencido(cobro)
  if (cobro.estado !== 'esperando_tarjeta') throw new ErrorApp('ESTADO_INVALIDO', `El cobro está en estado ${cobro.estado}`, 409)
  const uid = normalizarUid(req.body.uid)
  const walletTarjeta = req.body.walletAddress ? normalizarWallet(req.body.walletAddress) : null

  // Primero el UID registrado (para que aplique el bloqueo); si no, la wallet escrita en la tarjeta
  const r = resolverMedio(datos, uid, walletTarjeta)
  const pagador = r && buscarCuenta(r.cuentaId)
  if (!pagador) {
    throw new ErrorApp('TARJETA_NO_REGISTRADA', esCelular(uid) ? 'Celular no vinculado' : 'Tarjeta no registrada', 404, { medioTipo: esCelular(uid) ? 'celular' : 'tarjeta' })
  }
  const tipo = esCelular(uid) ? 'Celular' : 'Tarjeta'
  log(ETAPA.AUTORIZACION, `${tipo} ${uid} → ${pagador.nombre} (por ${r.por === 'uid' ? 'UID registrado' : 'wallet en la tarjeta'})`)
  if (esMismaCuenta(cobro, pagador.id)) {
    throw new ErrorApp('MISMA_CUENTA', 'No se puede cobrar a la propia cuenta.', 409, { medioTipo: esCelular(uid) ? 'celular' : 'tarjeta' })
  }
  if (estaBloqueado(r)) {
    throw new ErrorApp('MEDIO_BLOQUEADO', `${r.medio.nombre} está bloqueado. Desbloquéelo en la app.`, 403, { medio: r.medio.nombre, medioTipo: r.medio.tipo })
  }

  const permiso = await estadoPermiso(pagador)
  if (!permiso.activo) {
    const msg = permiso.noValido
      ? `El permiso de ${pagador.nombre} ya no es válido. Hay que aprobarlo de nuevo.`
      : `${pagador.nombre} no tiene permiso de pago activo`
    throw new ErrorApp('SIN_PERMISO', msg, 403, { cliente: pagador.nombre })
  }

  const w = await op.obtenerWallet(pagador.walletAddress)
  const credQuote = await op.grantNoInteractivo(ETAPA.AUTORIZACION, w, 'quote', ['create', 'read'])
  const quote = await op.crearQuote(w, credQuote.accessToken, cobro.incomingPaymentId)

  if (BigInt(quote.debitAmount.value) > BigInt(permiso.restante)) {
    log(ETAPA.AUTORIZACION, `TOPE_SUPERADO: debita ${textoAmount(quote.debitAmount)} y quedan ${permiso.restanteTexto}`)
    throw new ErrorApp('TOPE_SUPERADO', `Supera el tope semanal. Quedan ${permiso.restanteTexto}.`, 402, {
      restante: { value: permiso.restante, assetCode: permiso.tope.assetCode, assetScale: permiso.tope.assetScale },
      restanteTexto: permiso.restanteTexto
    })
  }

  Object.assign(cobro, {
    estado: 'esperando_confirmacion', uid, pagadorId: pagador.id,
    quoteId: quote.id, debitAmount: quote.debitAmount
  })
  guardar()
  res.json(vistaCobro(cobro))
}))

/** [③] Segunda lectura con el mismo medio: outgoing payment con el permiso guardado. */
app.post('/api/cobros/:id/confirmar', ruta(async (req, res) => {
  const cobro = obtenerCobro(req.params.id)
  revisarVencido(cobro)
  if (cobro.estado !== 'esperando_confirmacion') throw new ErrorApp('ESTADO_INVALIDO', `El cobro está en estado ${cobro.estado}`, 409)
  if (normalizarUid(req.body.uid) !== cobro.uid) throw new ErrorApp('TARJETA_DISTINTA', 'Es otra tarjeta. Acerque la misma tarjeta para confirmar.', 409)

  const medio = datos.medios[cobro.uid]
  if (medio?.bloqueada) {
    marcarError(cobro, 'MEDIO_BLOQUEADO', `${medio.nombre} está bloqueado. Desbloquéelo en la app.`, { medio: medio.nombre, medioTipo: medio.tipo })
    throw new ErrorApp('MEDIO_BLOQUEADO', cobro.error.mensaje, 403, { medio: medio.nombre, medioTipo: medio.tipo })
  }
  const pagador = buscarCuenta(cobro.pagadorId)
  const permiso = datos.permisos[pagador.id]
  if (!permiso?.accessToken || permiso.invalidoDesde) throw new ErrorApp('SIN_PERMISO', `${pagador.nombre} no tiene permiso de pago activo`, 403)
  cobro.estado = 'procesando'
  cobro.procesandoDesde = new Date().toISOString()
  guardar()
  try {
    const w = await op.obtenerWallet(pagador.walletAddress)
    const og = await op.conRotacion(ETAPA.EJECUCION, permiso, (t) => op.crearOutgoing(w, t, cobro.quoteId), guardar)
    cobro.outgoingPaymentId = og.id
    if (medio) medio.ultimoUso = new Date().toISOString()
    guardar()
  } catch (e) {
    log(ETAPA.EJECUCION, `Error en outgoing payment: ${op.describirError(e)}`)
    // Rafiki responde al límite del permiso con 403 InsufficientGrant ("unauthorized");
    // un token vencido también es 403 ("Inactive Token") y ya se intentó rotar arriba
    if (e?.status === 403 && !op.esTokenVencido(e)) {
      const estado = await estadoPermiso(pagador)
      const err = new ErrorApp('TOPE_SUPERADO', `Open Payments rechazó el pago por el límite del permiso. Quedan ${estado.restanteTexto ?? 'pocos fondos'}.`, 402, {
        restante: estado.activo ? { value: estado.restante, assetCode: estado.tope.assetCode, assetScale: estado.tope.assetScale } : undefined,
        restanteTexto: estado.restanteTexto
      })
      marcarError(cobro, err.codigo, err.message, err.extra)
      throw err
    }
    if (op.esTokenVencido(e)) {
      marcarError(cobro, 'SIN_PERMISO', 'El permiso venció o fue revocado. Hay que aprobarlo de nuevo.')
      throw new ErrorApp('SIN_PERMISO', cobro.error.mensaje, 403)
    }
    marcarError(cobro, 'ERROR_OPEN_PAYMENTS', op.describirError(e))
    throw e
  }
  res.json(vistaCobro(cobro))
}))

/** [④] Estado del cobro consultando el incoming payment. */
app.get('/api/cobros/:id', ruta(async (req, res) => {
  const cobro = obtenerCobro(req.params.id)
  try { revisarVencido(cobro) } catch { /* el estado queda en error y se devuelve abajo */ }
  if (cobro.estado === 'procesando') await revisarPago(cobro)
  res.json(vistaCobro(cobro))
}))

app.post('/api/cobros/:id/cancelar', (req, res) => {
  const cobro = obtenerCobro(req.params.id)
  if (['esperando_tarjeta', 'esperando_confirmacion'].includes(cobro.estado)) {
    marcarError(cobro, 'CANCELADO', 'Cobro cancelado')
    log('CANCELADO', `Cobro ${cobro.id} cancelado`)
  }
  res.json(vistaCobro(cobro))
})

async function revisarPago (cobro) {
  const ip = await op.conRotacion(ETAPA.FINALIZACION, cobro.credTienda, (t) => op.obtenerIncoming(cobro.incomingPaymentId, t), guardar)
  const recibido = ip.receivedAmount?.value ?? '0'
  if (recibido !== cobro.ultimoRecibido) {
    cobro.ultimoRecibido = recibido
    log(ETAPA.FINALIZACION, `Incoming payment: recibido ${formatearMonto(recibido, cobro.monto.assetCode, cobro.monto.assetScale)} (value=${recibido}) de ${textoAmount(cobro.monto)}, completed=${ip.completed}`)
  }
  if (ip.completed || BigInt(recibido) >= BigInt(cobro.monto.value)) {
    cobro.estado = 'pagado'
    cobro.pagadoEn = new Date().toISOString()
    guardar()
    log(ETAPA.FINALIZACION, `PAGADO: ${textoAmount(cobro.monto)} a ${nombreNegocio(buscarCuenta(cobro.cuentaCobroId))}`)
    return
  }
  // ¿Falló el outgoing payment?
  const permiso = datos.permisos[cobro.pagadorId]
  if (cobro.outgoingPaymentId && permiso?.accessToken) {
    try {
      const og = await op.conRotacion(ETAPA.FINALIZACION, permiso, (t) => op.obtenerOutgoing(cobro.outgoingPaymentId, t), guardar)
      if (og.failed) {
        log(ETAPA.FINALIZACION, 'El outgoing payment falló')
        return marcarError(cobro, 'PAGO_FALLIDO', 'El pago falló en la wallet de quien paga.')
      }
    } catch (e) {
      log(ETAPA.FINALIZACION, `No se pudo consultar el outgoing payment: ${op.describirError(e)}`)
    }
  }
  if (Date.now() - Date.parse(cobro.procesandoDesde) > 90_000) {
    marcarError(cobro, 'TIEMPO_AGOTADO', 'El pago no se completó a tiempo. Revise el saldo en la wallet.')
  }
}

function vistaCobro (c) {
  const pagador = c.pagadorId ? buscarCuenta(c.pagadorId) : null
  return {
    id: c.id,
    estado: c.estado,
    monto: c.monto,
    montoTexto: formatearAmount(c.monto),
    debitTexto: c.debitAmount ? formatearAmount(c.debitAmount) : null,
    tienda: nombreNegocio(buscarCuenta(c.cuentaCobroId)),
    cuentaCobroId: c.cuentaCobroId ?? null,
    cliente: pagador?.nombre ?? null,
    medioTipo: c.uid ? (esCelular(c.uid) ? 'celular' : 'tarjeta') : null,
    expiresAt: c.expiresAt,
    segundosRestantes: Math.max(0, Math.round((Date.parse(c.expiresAt) - Date.now()) / 1000)),
    error: c.error ?? null
  }
}

/** Historial para la página del familiar: todos los pagos con quien pagó. */
function historialFamiliar () {
  return datos.cobros
    .filter((c) => c.pagadorId)
    .slice()
    .reverse()
    .map((c) => ({
      id: c.id,
      fecha: new Date(c.pagadoEn ?? c.creadoEn).toLocaleString('es-CO'),
      cliente: `${buscarCuenta(c.pagadorId)?.nombre ?? '?'} → ${nombreNegocio(buscarCuenta(c.cuentaCobroId))}`,
      monto: formatearAmount(c.debitAmount ?? c.monto),
      estado: c.estado === 'error' ? `error: ${c.error?.mensaje}` : c.estado
    }))
}

app.get('/api/pagos', (req, res) => res.json(historialFamiliar()))

/** Historial del datáfono de una cuenta (?cuenta=): sus cobros y el total de hoy. */
app.get('/api/cobros', (req, res) => {
  const cuenta = cuentaQueCobra(req.query.cuenta)
  const dos = (n) => String(n).padStart(2, '0')
  const propios = datos.cobros.filter((c) => c.cuentaCobroId === cuenta.id)
  const hoy = new Date().toDateString()
  const pagadosHoy = propios.filter((c) => c.estado === 'pagado' && new Date(c.pagadoEn).toDateString() === hoy)
  const total = pagadosHoy.reduce((s, c) => s + BigInt(c.monto.value), 0n)
  const ref = propios.at(-1)?.monto
  res.json({
    hoy: { cantidad: pagadosHoy.length, totalTexto: ref ? formatearMonto(total, ref.assetCode, ref.assetScale) : null },
    cobros: propios.slice(-50).reverse().map((c) => {
      const f = new Date(c.pagadoEn ?? c.creadoEn)
      return {
        id: c.id,
        fecha: `${dos(f.getDate())}/${dos(f.getMonth() + 1)} ${dos(f.getHours())}:${dos(f.getMinutes())}`,
        montoTexto: formatearAmount(c.monto),
        cliente: c.pagadorId ? buscarCuenta(c.pagadorId)?.nombre ?? null : null,
        estado: c.estado,
        estadoTexto: textoEstadoCobro(c)
      }
    })
  })
})

registrarRutasBilletera(app, { ruta, vistaCobro, revisarPago })

// Manejador de errores: ErrorApp → JSON con código; resto → error de Open Payments
app.use((err, req, res, next) => {
  if (err instanceof ErrorApp) {
    log('ERROR', `${err.codigo}: ${err.message}`)
    // Error en el primer toque de un celular: se guarda para el feedback en su app
    const uid = normalizarUid(req.body?.uid)
    const enTarjeta = req.path.match(/^\/api\/cobros\/([^/]+)\/tarjeta$/)
    if (enTarjeta && esCelular(uid)) {
      intentos.set(uid, { codigo: err.codigo, mensaje: err.message, extra: err.extra, cobroId: enTarjeta[1], fecha: Date.now() })
    }
    return res.status(err.status).json({ error: { codigo: err.codigo, mensaje: err.message, ...err.extra } })
  }
  const detalle = op.describirError(err)
  log('ERROR', `Open Payments: ${detalle}`)
  res.status(502).json({ error: { codigo: 'ERROR_OPEN_PAYMENTS', mensaje: `Error con Open Payments: ${detalle}` } })
})

const problemas = problemasConfig()
app.listen(config.port, '127.0.0.1', () => {
  console.log(`Servidor en http://localhost:${config.port}  (familiar: http://localhost:${config.port}/familiar)`)
  console.log(`Cuentas: ${datos.cuentas.map((c) => `${c.nombre} (${nombreNegocio(c)})`).join(', ') || 'ninguna'}`)
  for (const p of problemas) console.log(`⚠ ${p}`)
  // Completa assetCode/assetScale/publicName de las cuentas migradas (sin bloquear el arranque)
  for (const c of datos.cuentas) if (!c.assetCode) walletDeCuenta(c).catch(() => null)
})
