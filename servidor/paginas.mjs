// HTML de la página del familiar: grande, legible y en español.

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])

function plantilla (titulo, cuerpo) {
  return `<!doctype html>
<html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(titulo)}</title>
<style>
  body { font-family: system-ui, sans-serif; font-size: 22px; line-height: 1.4; margin: 0 auto; max-width: 900px; padding: 16px; background: #fff; color: #111; }
  h1 { font-size: 34px; } h2 { font-size: 28px; margin-top: 40px; }
  .cliente { border: 3px solid #111; border-radius: 12px; padding: 16px; margin: 16px 0; }
  .activo { color: #0a6b1f; font-weight: bold; } .inactivo { color: #a00; font-weight: bold; }
  input, button { font-size: 22px; padding: 12px; border-radius: 8px; }
  input { border: 2px solid #111; width: 100%; box-sizing: border-box; margin: 4px 0 12px; }
  button { background: #0b3d91; color: #fff; border: none; font-weight: bold; cursor: pointer; min-height: 56px; }
  .aviso { background: #fff3c4; border: 3px solid #a67c00; padding: 16px; border-radius: 12px; }
  .ok { background: #d9f7df; border: 3px solid #0a6b1f; padding: 16px; border-radius: 12px; }
  table { border-collapse: collapse; width: 100%; font-size: 18px; } td, th { border: 1px solid #555; padding: 6px; text-align: left; }
  label { font-weight: bold; }
</style></head><body>${cuerpo}</body></html>`
}

/** clientes: [{ id, nombre, walletAddress, permiso: { activo, pendiente, topeTexto, gastadoTexto, restanteTexto, fuente, topeDolares } }] */
export function paginaFamiliar ({ clientes, pagos, aviso, ok }) {
  const tarjetas = clientes.map((c) => {
    const p = c.permiso
    const estado = p.activo
      ? `<p class="activo">✔ Permiso activo</p>
         <p>Tope semanal: <b>${esc(p.topeTexto)}</b><br>
            Gastado esta semana: <b>${esc(p.gastadoTexto)}</b> <small>(${esc(p.fuente)})</small><br>
            Le queda: <b>${esc(p.restanteTexto)}</b></p>`
      : `<p class="inactivo">${p.pendiente ? '⏳ Permiso pendiente de aprobar' : p.noValido ? '⚠ El permiso ya no es válido: apruébalo de nuevo' : '✘ Sin permiso'}</p>`
    return `<div class="cliente">
      <h3>${esc(c.nombre)}</h3>
      <p><small>${esc(c.walletAddress)}</small></p>
      ${estado}
      <form method="post" action="/familiar/aprobar">
        <input type="hidden" name="cliente" value="${esc(c.id)}">
        <label>Tope semanal (dólares)</label>
        <input name="tope" inputmode="decimal" value="${esc(p.topeDolares ?? '200')}">
        <button type="submit">${p.activo ? 'Aprobar de nuevo (reemplaza el permiso)' : 'Aprobar permiso semanal'}</button>
      </form>
    </div>`
  }).join('')

  const filas = pagos.map((x) => `<tr><td>${esc(x.fecha)}</td><td>${esc(x.cliente)}</td><td>${esc(x.monto)}</td><td>${esc(x.estado)}</td></tr>`).join('')

  return plantilla('Pagos de la familia', `
    <h1>Pagos de la familia</h1>
    <p>Cada cuenta puede pagar (con su permiso semanal) y cobrar desde la app.</p>
    ${aviso ? `<p class="aviso">${esc(aviso)}</p>` : ''}
    ${ok ? `<p class="ok">${esc(ok)}</p>` : ''}
    <h2>Cuentas</h2>
    ${tarjetas || '<p>Todavía no hay cuentas.</p>'}
    <h2>Agregar cuenta</h2>
    <form method="post" action="/familiar/clientes">
      <label>Nombre</label><input name="nombre" required placeholder="Doña Rosa">
      <label>Wallet address</label><input name="walletAddress" required placeholder="https://ilp.interledger-test.dev/rosa">
      <button type="submit">Agregar cuenta</button>
    </form>
    <h2>Historial de pagos</h2>
    ${filas ? `<table><tr><th>Fecha</th><th>Cliente</th><th>Monto</th><th>Estado</th></tr>${filas}</table>` : '<p>Sin pagos todavía.</p>'}
  `)
}

/** Enlace de aprobación para enviar al dueño de la wallet + campo para pegar la dirección de regreso. */
export function paginaAprobacion ({ cliente, enlace }) {
  return plantilla('Aprobar permiso', `
    <h1>Permiso semanal de ${esc(cliente.nombre)}</h1>
    <div class="cliente">
      <p><b>Envía este enlace al dueño de la wallet. Que inicie sesión y acepte.</b></p>
      <input id="enlace" readonly value="${esc(enlace)}" onclick="this.select()">
      <button type="button" onclick="copiar()">Copiar enlace</button>
      <span id="copiado" class="activo"></span>
    </div>
    <div class="cliente">
      <form method="post" action="/familiar/completar">
        <input type="hidden" name="cliente" value="${esc(cliente.id)}">
        <label>Pega aquí la dirección a la que llegó tu amigo</label>
        <input name="url" required placeholder="http://localhost:3000/familiar/callback?...">
        <button type="submit">Completar permiso</button>
      </form>
    </div>
    <p><a href="/familiar">Volver</a></p>
    <script>
      async function copiar () {
        const campo = document.getElementById('enlace')
        try { await navigator.clipboard.writeText(campo.value) } catch { campo.select(); document.execCommand('copy') }
        document.getElementById('copiado').textContent = ' ✔ Copiado'
      }
    </script>
  `)
}

export function paginaMensaje (titulo, mensaje, esError) {
  return plantilla(titulo, `
    <h1>${esc(titulo)}</h1>
    <p class="${esError ? 'aviso' : 'ok'}">${esc(mensaje)}</p>
    <p><a href="/familiar"><button>Volver</button></a></p>
    ${esError ? '' : '<script>setTimeout(() => location.href = "/familiar", 3000)</script>'}
  `)
}
