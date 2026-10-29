# Guía de pruebas manuales

Pruebas de punta a punta de los modos **Cobrar** y **Pagar** contra el test wallet de Interledger. Antes de empezar, sigue la sección [Cómo correrlo](../README.md#cómo-correrlo) del README.

## 0. Preparación

```bash
cd servidor
npm run probar   # debe terminar con "Las credenciales funcionan"
npm start        # deja esta terminal visible: ahí se ven las etapas del pago
```

En **cada** celular conectado por USB (se pierde al reconectar el cable):

```bash
adb devices                              # anota el serial
adb -s <SERIAL> reverse tcp:3000 tcp:3000
```

El celular que **paga** con NFC necesita NFC encendido, HCE y un bloqueo de pantalla configurado (PIN, patrón o huella).

## 1. Crear la cuenta

1. Abre la app: aparece "Crear mi cuenta".
2. Escribe tu nombre, tu wallet address y, opcional, el nombre del negocio.
3. Si la wallet ya tenía cuenta, se recupera con sus tarjetas, su permiso y su historial.

## A. Cobrar con una tarjeta NFC (un celular)

1. Toca **Cobrar**: la barra se pone azul.
2. Marca **$5.00** y toca **Cobrar**.
3. Acerca una tarjeta registrada. La voz pregunta: "¿Pagar cinco dólares a …?".
4. Retírala y acércala otra vez: vibración doble, "Transfiriendo…", "Pago recibido".
5. En **Historial** aparece "+ $5.00". En el test wallet, la wallet del cliente baja y la del comercio sube.

## B. Modo Pagar (un celular)

1. Toca **Pagar**: la barra se pone morada.
2. **Inicio** muestra lo disponible esta semana, el tope, lo gastado y la fecha de renovación.
3. Sin permiso: **Aprobar mi permiso semanal** abre el test wallet en el navegador. Inicia sesión con la cuenta dueña de la wallet y acepta.
4. **Autorizar pago con huella**: queda una ventana de 60 s para acercar el celular.
5. **Tarjetas**: renombrar, bloquear, desbloquear, eliminar y agregar tarjetas.

## C. Entre dos celulares

Celular **A** en modo Cobrar y celular **B** en modo Pagar, con permiso activo y pantalla desbloqueada.

| Caso | Qué hacer | Resultado esperado |
|---|---|---|
| Pago con el celular | B autoriza con huella, A cobra $5, acercas B a A dos veces | A: "Pago recibido"; B: "Pago hecho" |
| Sin autorizar | Acercas B sin autorizar | A no lo reconoce y B pide la huella |
| Celular bloqueado | Bloqueas el celular de B en Tarjetas | Error `MEDIO_BLOQUEADO` |
| Celular cobrando | B también en modo Cobrar | A no lo reconoce como celular (HCE apagada) |
| Medio distinto | Primer toque con tarjeta, segundo con B | Error `TARJETA_DISTINTA`, no cobra |
| Misma cuenta | Cobras con una tarjeta de tu propia cuenta | Error `MISMA_CUENTA` |

## Errores comunes

- **"Sin conexión con el servidor"**: el servidor no corre o falta `adb reverse tcp:3000 tcp:3000` en ese celular.
- **El segundo toque no confirma**: hay que retirar y volver a acercar; Android no detecta de nuevo un tag que no se movió.
- **Otra app de pagos responde al toque**: la app usa un AID propio (categoría "other") y se pide como servicio preferido mientras está abierta en modo Pagar. Mantenla en primer plano.
- **Permiso rechazado o `/no-access`**: el test wallet tiene abierta una cuenta que no es dueña de la wallet. Cierra sesión, entra con la correcta y pide un enlace nuevo.
- **`invalid_interaction`**: el enlace del permiso ya se usó. Pide uno nuevo.
- **Tarjetas bancarias**: muchas dan un UID distinto en cada lectura. Usa stickers NTAG215 o tarjetas de transporte.
