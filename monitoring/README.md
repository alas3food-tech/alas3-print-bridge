# Monitoreo — ALAS 3 OS

Corre solo cada 10 minutos (GitHub Actions) y revisa:

1. Que `alas3.co` y los 6 paneles respondan.
2. Que las Edge Functions de Supabase respondan.
3. Que la cola de impresión se esté vaciando — si hay un recibo esperando
   más de 15 minutos durante el horario del negocio (4:00 PM–11:30 PM,
   hora Colombia), avisa. Esto es lo que un monitor genérico de
   "arriba/abajo" no puede detectar: el sitio puede estar perfectamente
   arriba mientras la tablet de cocina está apagada.

Si encuentra un problema, manda una alerta por WhatsApp y por correo.

## Configuración (una sola vez)

Ve a **Settings → Secrets and variables → Actions** en este repositorio
de GitHub y agrega:

### WhatsApp (CallMeBot, gratis, sin cuenta de negocio)

1. Agrega el número `+34 644 59 71 65` a tus contactos de WhatsApp.
2. Mándale el mensaje: `I allow callmebot to send me messages`
3. Te responde con tu `apikey`.
4. Agrega dos secretos:
   - `CALLMEBOT_PHONE` → tu número con indicativo, ej. `573194077941`
   - `CALLMEBOT_APIKEY` → el que te dio el bot

### Correo (con tu Gmail existente)

1. En tu cuenta de Gmail, activa la verificación en dos pasos si no la
   tienes, y genera una "contraseña de aplicación" en
   myaccount.google.com/apppasswords.
2. Agrega tres secretos:
   - `GMAIL_USERNAME` → tu correo de Gmail
   - `GMAIL_APP_PASSWORD` → la contraseña de aplicación (no tu contraseña normal)
   - `ALERT_EMAIL_TO` → a qué correo quieres que lleguen las alertas (puede ser el mismo)

Sin estos secretos configurados, el monitoreo igual corre y revisa todo
— simplemente no puede avisarte si encuentra un problema (lo vas a ver
igual si entras a la pestaña "Actions" de este repositorio).

## Probarlo a mano

En **Actions → Monitoreo Alas 3 OS → Run workflow** lo puedes disparar
cuando quieras, sin esperar los 10 minutos.
