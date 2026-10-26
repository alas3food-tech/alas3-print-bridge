# Alas3 Impresora (puente de impresion Android)

App minima, gratuita para siempre y de codigo abierto, que recibe los
recibos desde el panel de Operaciones y los manda directo a la impresora
termica JALTECH JAL 58M por USB, sin depender de RawBT ni de ningun otro
programa de terceros.

## Como llega el recibo hasta aqui

1. En Operaciones, al tocar "Imprimir recibos", la pagina web arma los
   comandos ESC/POS (negrilla, texto grande, corte de papel) y abre un
   enlace `alas3print://print?data=...`.
2. Android ve que esta app registro ese tipo de enlace y la abre.
3. Esta app decodifica los datos y los manda por USB a la impresora.

## Compilar el APK (no requiere Android Studio)

Este proyecto ya trae un archivo `.github/workflows/build-apk.yml` que le
dice a GitHub que lo compile solo, en la nube, cada vez que se sube
codigo nuevo.

1. Sube esta carpeta (`AndroidPrintBridge/`) a un repositorio de GitHub.
2. Entra a la pestaña **Actions** del repositorio.
3. Espera a que termine el flujo **"Compilar APK"** (unos 2-4 minutos, el
   circulo se pone verde cuando termina).
4. Entra a esa ejecucion y baja hasta **Artifacts**. Descarga
   **Alas3Impresora-APK** (es un .zip).
5. Descomprime el .zip -- adentro esta `app-debug.apk`.

## Instalar en la tablet

1. Pasa el archivo `app-debug.apk` a la tablet (por USB, Google Drive,
   correo, WhatsApp Web, lo que sea mas facil).
2. Abre el archivo desde la tablet. Android puede pedir permiso para
   "instalar apps de origen desconocido" la primera vez -- acepta ese
   permiso solo para este archivo.
3. Instala. Deberia aparecer un icono **"Alas3 Impresora"**.

## Configurar el permiso USB (una sola vez)

1. Conecta la impresora por USB si no lo esta.
2. Abre la app **Alas3 Impresora** desde el cajon de aplicaciones.
3. Android va a mostrar un cuadro: *"¿Permitir que Alas3 Impresora acceda
   al dispositivo USB?"* -- marca la casilla **"Usar por defecto para
   este dispositivo USB"** y toca **Aceptar**.
4. Listo. Desde ahora, cualquier recibo que mande Operaciones va a
   imprimir solo, sin volver a preguntar.

## PIN de la cuenta impresora (desde la version 1.1)

El PIN ya NO va escrito dentro de la app: se escribe en la pantalla de la
app, en **"PIN de la cuenta impresora"**, y se toca **Guardar PIN**. Queda
guardado solo en esta tablet.

- Para cambiarlo: en Operaciones -> **Staff**, ponle el PIN nuevo a la
  cuenta *impresora*, y despues escribe ese mismo PIN en la app.
- Si la app dice **"El PIN no es correcto"**, deja de intentar sola (para
  no bloquear la cuenta: 5 intentos fallidos la bloquean 15 minutos).
  Escribe el PIN correcto y toca Guardar PIN.
- Si al instalar una version nueva Android dice "No se instalo la app",
  desinstala primero la anterior (cada compilacion en GitHub firma la app
  con una llave distinta) y vuelve a escribir el PIN.

## Si algo falla

La app muestra en pantalla que fue lo que paso (queda abierta solo si
hubo un error, y se cierra sola si imprimio bien):

- **"No se detecto ninguna impresora USB conectada"**: revisa el cable.
- **"Permiso USB denegado"**: vuelve a mandar un recibo de prueba y esta
  vez acepta el permiso.
- **"La impresora no respondio a tiempo"**: revisa que tenga papel y
  este encendida.

Si el enlace `alas3print://` no hace nada en absoluto (ni siquiera abre
la app), en Operaciones sigue apareciendo el aviso con el boton
**"toca aqui para usar la impresion del navegador"** como respaldo -- no
se pierde la capacidad de imprimir mientras se resuelve.

## Monitoreo del sistema completo

Este repositorio también aloja el monitoreo automático de todo ALAS 3
OS (sitio, funciones de servidor y la cola de impresión) — ver
[`monitoring/README.md`](monitoring/README.md). No tiene relación con
la app Android en sí; vive aquí porque es el único repositorio con
GitHub Actions ya configurado.
