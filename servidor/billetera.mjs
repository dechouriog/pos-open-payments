// Rutas /api/billetera/*: la app Billetera del celular del cliente.
// El celular se identifica con ?dispositivo=<dispositivoId> (prototipo: sin autenticación real).
import { buscarCuenta, datos, guardar } from './datos.mjs'
import {
  PREFIJO_CELULAR, combinarMovimientos, esCelular, movimientoEntrante, movimientoSaliente,
  nombreNegocio, nombrePorDefecto, normalizarUid
} from './medios.mjs'
import * as op from './op.mjs'
import { ETAPA, log } from './op.mjs'
import { estadoPermiso, solicitarPermiso } from './permisos.mjs'
import { ErrorApp, formatearAmount, normalizarWallet } from './util.mjs'

const LOG = 'BILLETERA'

// Último texto registrado por clave: las consultas frecuentes solo se registran cuando algo cambia
const vistos = new Map()
function logSiCambia (clave, texto) {
  if (vistos.get(clave) === texto) return
  vistos.set(clave, texto)
  log(LOG, texto)
}

/** Errores del primer toque de un celular (el cobro no queda asociado a él), para el feedback. */
export const intentos = new Map()

const idValido = (id) => /^[0-9a-f-]{8,64}$/.test(id)
const limpiarNombre = (s) => String(s ?? '').trim().slice(0, 40)

export function registrarRutasBilletera (app, { ruta, vistaCobro, revisarPago }) {
  /** Celular vinculado que hace la petición → { uid, medio, cliente }. */
  function porDispositivo (req) {
    const id = String(req.query.dispositivo ?? req.body?.dispositivo ?? '').trim().toLowerCase()
    const uid = PREFIJO_CELULAR + id
    const medio = idValido(id) ? datos.medios[uid] : null
    const cliente = medio && buscarCuenta(medio.cuentaId)
    if (!cliente) throw new ErrorApp('NO_VINCULADO', 'Este celular no está vinculado', 404)
    return { uid, medio, cliente }
  }

  function medioDelCliente (cliente, uidParam) {
    const uid = normalizarUid(uidParam)
    const medio = datos.medios[uid]
    if (!medio || medio.cuentaId !== cliente.id) throw new ErrorApp('MEDIO_NO_ENCONTRADO', 'Ese medio de pago no existe o no es suyo', 404)
    return { uid, medio }
  }

  const vistaMedio = (uid, m, uidPropio) => ({
    uid,
    tipo: m.tipo,
    nombre: m.nombre,
    bloqueada: !!m.bloqueada,
    creadoEn: m.creadoEn,
    ultimoUso: m.ultimoUso,
    esEsteCelular: uid === uidPropio
  })

  app.post('/api/billetera/vincular', ruta(async (req, res) => {
    const id = String(req.body.dispositivoId ?? '').trim().toLowerCase()
    if (!idValido(id)) throw new ErrorApp('DISPOSITIVO_INVALIDO', 'Identificador de celular no válido')
    const wallet = req.body.walletAddress ? normalizarWallet(req.body.walletAddress) : null
    const cliente = (buscarCuenta(req.body.cuentaId ?? req.body.clienteId)) ||
      (wallet && datos.cuentas.find((c) => c.walletAddress === wallet))
    if (!cliente) {
      throw new ErrorApp('CLIENTE_NO_ENCONTRADO', 'Esa wallet no corresponde a un cliente registrado. Pida al familiar que lo agregue en /familiar.', 404)
    }
    const uid = PREFIJO_CELULAR + id
    const previo = datos.medios[uid]
    const mismoCliente = previo?.cuentaId === cliente.id
    datos.medios[uid] = {
      cuentaId: cliente.id,
      tipo: 'celular',
      nombre: limpiarNombre(req.body.nombreDispositivo) || previo?.nombre || 'Celular',
      bloqueada: mismoCliente ? !!previo.bloqueada : false,
      creadoEn: mismoCliente ? previo.creadoEn : new Date().toISOString(),
      ultimoUso: mismoCliente ? previo.ultimoUso : null
    }
    guardar()
    log(LOG, `Celular "${datos.medios[uid].nombre}" (${uid}) vinculado a ${cliente.nombre}`)
    res.json({ uid, cliente: { id: cliente.id, nombre: cliente.nombre, walletAddress: cliente.walletAddress } })
  }))

  app.delete('/api/billetera/dispositivos/:dispositivoId', (req, res) => {
    const uid = PREFIJO_CELULAR + String(req.params.dispositivoId).trim().toLowerCase()
    if (datos.medios[uid]) {
      log(LOG, `Celular "${datos.medios[uid].nombre}" (${uid}) desvinculado`)
      delete datos.medios[uid]
      guardar()
    }
    res.json({ ok: true })
  })

  app.get('/api/billetera/resumen', ruta(async (req, res) => {
    const { uid, medio, cliente } = porDispositivo(req)
    const w = await op.obtenerWallet(cliente.walletAddress)
    const permiso = await estadoPermiso(cliente)
    logSiCambia(`resumen:${uid}`, `Resumen de ${cliente.nombre}: permiso ${permiso.estado}${permiso.activo ? `, quedan ${permiso.restanteTexto} (${permiso.fuente})` : ''}`)
    res.json({
      cliente: { id: cliente.id, nombre: cliente.nombre, walletAddress: cliente.walletAddress },
      assetCode: w.assetCode,
      assetScale: w.assetScale,
      medio: vistaMedio(uid, medio, uid),
      permiso
    })
  }))

  app.get('/api/billetera/movimientos', ruta(async (req, res) => {
    const { cliente } = porDispositivo(req)
    const p = datos.permisos[cliente.id]
    const w = await op.obtenerWallet(cliente.walletAddress)
    const usable = !!p?.accessToken && !p.invalidoDesde
    const completo = usable && p.alcance === 'completo'
    const cobroPorOutgoing = new Map(datos.cobros.filter((c) => c.outgoingPaymentId).map((c) => [c.outgoingPaymentId, c]))
    const avisos = []
    let fuente = 'wallet'
    let salientes = []
    let entrantes = []

    // Pagos hechos: con el token del permiso (list = solo los de esta app; list-all = todos)
    if (usable) {
      try {
        const r = await op.conRotacion(ETAPA.AUTORIZACION, p, (t) => op.listarOutgoing(w, t), guardar)
        salientes = r.result.map((o) => {
          const c = cobroPorOutgoing.get(o.id)
          return movimientoSaliente(o, c, c ? datos.medios[c.uid] : null, nombreNegocio(buscarCuenta(c?.cuentaCobroId)))
        })
      } catch (e) {
        fuente = 'local'
        avisos.push(`No se pudieron leer los pagos en la wallet: ${op.describirError(e)}`)
      }
    } else {
      fuente = 'local'
      avisos.push('Sin permiso válido: se muestran solo los pagos registrados en el datáfono.')
    }
    if (fuente === 'local') {
      salientes = datos.cobros
        .filter((c) => c.pagadorId === cliente.id && c.estado === 'pagado')
        .map((c) => ({
          id: c.id, tipo: 'pago', fecha: c.pagadoEn, monto: c.debitAmount ?? c.monto, estado: 'completado',
          contraparte: nombreNegocio(buscarCuenta(c.cuentaCobroId)), medio: datos.medios[c.uid]?.nombre ?? null
        }))
    }

    // Pagos recibidos: con list-all del permiso, o un grant no interactivo (solo los de esta app)
    try {
      let r
      if (completo) {
        r = await op.conRotacion(ETAPA.AUTORIZACION, p, (t) => op.listarIncoming(w, t), guardar)
      } else {
        const g = await op.grantNoInteractivo(ETAPA.AUTORIZACION, w, 'incoming-payment', ['list', 'read'], { identifier: w.id, silencioso: true })
        r = await op.listarIncoming(w, g.accessToken)
      }
      entrantes = r.result.map(movimientoEntrante).filter(Boolean)
    } catch (e) {
      avisos.push(`No se pudieron leer los pagos recibidos: ${op.describirError(e)}`)
    }
    if (completo) {
      // Rafiki filtra los outgoing por cliente aunque el token tenga list-all (sí respeta list-all en incoming)
      avisos.push('Los pagos que hizo fuera de esta app (por ejemplo desde el test wallet) y los depósitos no se pueden consultar aquí: véalos en el test wallet.')
    } else {
      avisos.push('Solo se ven los movimientos hechos con esta app. Para ver también los pagos recibidos, solicite un permiso nuevo en "Mi permiso".')
    }

    const movimientos = combinarMovimientos(salientes, entrantes).map((m) => ({ ...m, montoTexto: formatearAmount(m.monto) }))
    log(LOG, `Movimientos de ${cliente.nombre}: ${salientes.length} hechos, ${entrantes.length} recibidos (${fuente}, alcance ${completo ? 'completo' : 'básico'})`)
    res.json({ movimientos, fuente, alcance: completo ? 'completo' : 'basico', avisos })
  }))

  app.get('/api/billetera/medios', (req, res) => {
    const { uid, cliente } = porDispositivo(req)
    const medios = Object.entries(datos.medios)
      .filter(([, m]) => m.cuentaId === cliente.id)
      .map(([u, m]) => vistaMedio(u, m, uid))
      .sort((a, b) => (a.tipo === b.tipo ? String(a.creadoEn ?? '').localeCompare(String(b.creadoEn ?? '')) : a.tipo === 'celular' ? -1 : 1))
    res.json({ medios })
  })

  /** Agrega una tarjeta física leída por el propio celular del cliente. */
  app.post('/api/billetera/medios', (req, res) => {
    const { uid: uidPropio, cliente } = porDispositivo(req)
    const uid = normalizarUid(req.body.uid)
    if (!uid) throw new ErrorApp('UID_INVALIDO', 'Falta el UID de la tarjeta')
    if (esCelular(uid)) throw new ErrorApp('ES_CELULAR', 'Los celulares se vinculan desde su propia Billetera')
    const previo = datos.medios[uid]
    if (previo && previo.cuentaId !== cliente.id) {
      const dueno = buscarCuenta(previo.cuentaId)
      throw new ErrorApp('MEDIO_YA_REGISTRADO', `Esta tarjeta ya está registrada a ${dueno?.nombre ?? 'otro cliente'}`, 409, { cliente: dueno?.nombre ?? null })
    }
    const wallet = req.body.walletAddress ? normalizarWallet(req.body.walletAddress) : null
    if (wallet && wallet !== cliente.walletAddress) {
      const otro = datos.cuentas.find((c) => c.walletAddress === wallet)
      throw new ErrorApp('MEDIO_YA_REGISTRADO', `Esta tarjeta tiene escrita la wallet de ${otro?.nombre ?? 'otra persona'}`, 409, { cliente: otro?.nombre ?? null })
    }
    const nombre = limpiarNombre(req.body.nombre) || previo?.nombre || nombrePorDefecto(datos.medios, cliente.id)
    datos.medios[uid] = previo
      ? { ...previo, nombre }
      : { cuentaId: cliente.id, tipo: 'tarjeta', nombre, bloqueada: false, creadoEn: new Date().toISOString(), ultimoUso: null }
    guardar()
    log(LOG, `Tarjeta ${uid} "${nombre}" ${previo ? 'actualizada' : 'agregada'} para ${cliente.nombre}`)
    res.json({ medio: vistaMedio(uid, datos.medios[uid], uidPropio), yaEstaba: !!previo })
  })

  app.patch('/api/billetera/medios/:uid', (req, res) => {
    const { uid: uidPropio, cliente } = porDispositivo(req)
    const { uid, medio } = medioDelCliente(cliente, req.params.uid)
    const nombre = limpiarNombre(req.body.nombre)
    if (nombre) medio.nombre = nombre
    if (typeof req.body.bloqueada === 'boolean') medio.bloqueada = req.body.bloqueada
    guardar()
    log(LOG, `${medio.tipo === 'celular' ? 'Celular' : 'Tarjeta'} "${medio.nombre}" de ${cliente.nombre}: ${medio.bloqueada ? 'BLOQUEADO' : 'activo'}`)
    res.json({ medio: vistaMedio(uid, medio, uidPropio) })
  })

  app.delete('/api/billetera/medios/:uid', (req, res) => {
    const { cliente } = porDispositivo(req)
    const { uid, medio } = medioDelCliente(cliente, req.params.uid)
    delete datos.medios[uid]
    guardar()
    log(LOG, `${medio.tipo === 'celular' ? 'Celular' : 'Tarjeta'} "${medio.nombre}" de ${cliente.nombre} eliminado`)
    res.json({ ok: true })
  })

  /** Cobro en curso de este celular (o recién terminado), para el feedback en la Billetera. */
  app.get('/api/billetera/cobro-pendiente', ruta(async (req, res) => {
    const { uid, cliente } = porDispositivo(req)
    const ahora = Date.now()
    const reciente = (c) => Date.parse(c.pagadoEn ?? c.finalizadoEn ?? 0) > ahora - 30_000
    let cobro = datos.cobros.findLast((c) => c.uid === uid &&
      (['esperando_confirmacion', 'procesando'].includes(c.estado) || reciente(c)))
    if (cobro?.estado === 'procesando') {
      try { await revisarPago(cobro) } catch (e) { /* el datáfono también lo consulta */ }
    }
    // Un error del primer toque no deja el cobro asociado al celular: se informa aparte
    const intento = intentos.get(uid)
    const intentoVigente = intento && intento.fecha > ahora - 30_000 &&
      (!cobro || Date.parse(cobro.creadoEn) < intento.fecha) ? intento : null
    if (intentoVigente) cobro = null
    const vista = cobro ? vistaCobro(cobro) : null
    logSiCambia(`pendiente:${uid}`, vista
      ? `Billetera de ${cliente.nombre} ve el cobro ${vista.id} en estado ${vista.estado}${vista.error ? ` (${vista.error.codigo})` : ''}`
      : intentoVigente ? `Billetera de ${cliente.nombre} ve el error ${intentoVigente.codigo}` : `Billetera de ${cliente.nombre} sin cobro en curso`)
    res.json({
      cobro: vista,
      intento: intentoVigente ? { codigo: intentoVigente.codigo, mensaje: intentoVigente.mensaje, cobroId: intentoVigente.cobroId, ...intentoVigente.extra } : null
    })
  }))

  app.post('/api/billetera/permiso/solicitar', ruta(async (req, res) => {
    const { cliente } = porDispositivo(req)
    const enlace = await solicitarPermiso(cliente, req.body.tope)
    log(LOG, `Permiso solicitado desde la Billetera de ${cliente.nombre}`)
    res.json({ enlace })
  }))
}
