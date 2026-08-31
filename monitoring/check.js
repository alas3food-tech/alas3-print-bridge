#!/usr/bin/env node
// ALAS 3 OS — Monitoreo. Corre cada 10 minutos desde GitHub Actions.
//
// Revisa dos cosas muy distintas:
//   1) Que el sitio y las funciones de servidor respondan (lo que
//      cualquier monitor de "arriba/abajo" ya sabe hacer).
//   2) Que la cola de impresion se este vaciando de verdad -- esto NO
//      lo detecta un monitor generico, porque el sitio puede estar
//      perfectamente arriba mientras la tablet de cocina esta apagada
//      o la app de impresion se cerro. Es el riesgo real: cocina se
//      queda ciega y nadie se entera hasta que un cliente reclama.
//
// Si algo falla, manda una alerta por WhatsApp (CallMeBot) y por correo
// (SMTP de Gmail) -- las credenciales vienen de variables de entorno
// (GitHub Actions secrets), nunca escritas aqui.

const SB_URL = "https://cerhybkxvziukxlkrrkf.supabase.co";
const SB_KEY = "sb_publishable_jcP3bIIssHblHBDts3kWNw_P7U9EZT4";

const SITE_PAGES = [
  "https://alas3.co/",
  "https://alas3.co/menu/",
  "https://alas3.co/mesa/",
  "https://alas3.co/cocina/",
  "https://alas3.co/mensajero/",
  "https://alas3.co/operaciones/",
  "https://alas3.co/dashboard/",
];

// Horario real del negocio: 4:00 PM a 11:30 PM todos los dias, hora
// Colombia (UTC-5, sin horario de verano). Fuera de esas horas no tiene
// sentido alertar por trabajos de impresion sin procesar -- no hay
// nadie en el restaurante para verlo de todas formas.
function dentroDeHorarioColombia() {
  const ahoraUtc = new Date();
  const horaCol = new Date(ahoraUtc.getTime() - 5 * 60 * 60 * 1000);
  const minutosDelDia = horaCol.getUTCHours() * 60 + horaCol.getUTCMinutes();
  return minutosDelDia >= 16 * 60 && minutosDelDia <= 23 * 60 + 30;
}

const problemas = [];

async function checkUrl(url) {
  try {
    const res = await fetch(url, { method: "GET", signal: AbortSignal.timeout(15000) });
    if (!res.ok) problemas.push(`${url} respondio HTTP ${res.status}`);
  } catch (e) {
    problemas.push(`${url} no respondio: ${e.message}`);
  }
}

async function checkEdgeFunction(name, body) {
  const url = `${SB_URL}/functions/v1/${name}`;
  try {
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(15000),
    });
    const data = await res.json().catch(() => null);
    if (!data || typeof data.ok !== "boolean") {
      problemas.push(`Funcion ${name} respondio algo inesperado (HTTP ${res.status})`);
    }
  } catch (e) {
    problemas.push(`Funcion ${name} no respondio: ${e.message}`);
  }
}

async function checkColaImpresion() {
  if (!dentroDeHorarioColombia()) return; // fuera de horario, no aplica

  try {
    const loginRes = await fetch(`${SB_URL}/rest/v1/rpc/staff_login`, {
      method: "POST",
      headers: { "Content-Type": "application/json", apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` },
      body: JSON.stringify({ p_codigo_acceso: "impresora", p_pin: "482917" }),
      signal: AbortSignal.timeout(15000),
    });
    const loginRows = await loginRes.json();
    if (!Array.isArray(loginRows) || !loginRows[0]) {
      problemas.push("No se pudo iniciar sesion con la cuenta de impresora para revisar la cola.");
      return;
    }
    const token = loginRows[0].token;

    const pendRes = await fetch(`${SB_URL}/rest/v1/rpc/listar_trabajos_impresion_pendientes`, {
      method: "POST",
      headers: { "Content-Type": "application/json", apikey: SB_KEY, Authorization: `Bearer ${SB_KEY}` },
      body: JSON.stringify({ p_token: token }),
      signal: AbortSignal.timeout(15000),
    });
    const pendientes = await pendRes.json();
    if (!Array.isArray(pendientes)) {
      problemas.push("La consulta de trabajos de impresion pendientes no devolvio una lista.");
      return;
    }

    const ahora = Date.now();
    const QUINCE_MIN = 15 * 60 * 1000;
    const atrasados = pendientes.filter((t) => ahora - new Date(t.created_at).getTime() > QUINCE_MIN);
    if (atrasados.length > 0) {
      problemas.push(
        `${atrasados.length} recibo(s) llevan mas de 15 min esperando a imprimirse -- la tablet de cocina puede estar apagada o sin la app abierta.`
      );
    }
  } catch (e) {
    problemas.push(`No se pudo revisar la cola de impresion: ${e.message}`);
  }
}

async function enviarWhatsApp(mensaje) {
  const phone = process.env.CALLMEBOT_PHONE;
  const apikey = process.env.CALLMEBOT_APIKEY;
  if (!phone || !apikey) {
    console.log("CALLMEBOT_PHONE / CALLMEBOT_APIKEY no configurados -- se omite la alerta por WhatsApp.");
    return;
  }
  const url = `https://api.callmebot.com/whatsapp.php?phone=${encodeURIComponent(phone)}&apikey=${encodeURIComponent(apikey)}&text=${encodeURIComponent(mensaje)}`;
  try {
    await fetch(url, { signal: AbortSignal.timeout(15000) });
    console.log("Alerta de WhatsApp enviada.");
  } catch (e) {
    console.log("No se pudo enviar la alerta de WhatsApp:", e.message);
  }
}

async function enviarCorreo(asunto, mensaje) {
  // El envio real de correo lo hace el paso de GitHub Actions
  // "dawidd6/action-send-mail" (ver monitor.yml) -- este script solo
  // deja el asunto en una variable de salida y el cuerpo en un archivo,
  // para no tener que traer una libreria de SMTP aqui.
  const fs = await import("node:fs");
  fs.writeFileSync("monitor-output.txt", mensaje);
  const out = process.env.GITHUB_OUTPUT;
  if (out) {
    fs.appendFileSync(out, `asunto=${asunto}\n`);
    fs.appendFileSync(out, `hay_problema=true\n`);
  }
}

async function run() {
  console.log(`ALAS 3 OS — monitoreo — ${new Date().toISOString()}\n`);

  await Promise.all(SITE_PAGES.map(checkUrl));
  await checkEdgeFunction("calcular-domicilio", { direccion: "Calle 46E Sur 42A-10, Envigado" });
  await checkColaImpresion();

  if (problemas.length === 0) {
    console.log("Todo en orden -- sitio, funciones y cola de impresion responden bien.");
    const fs = await import("node:fs");
    const out = process.env.GITHUB_OUTPUT;
    if (out) fs.appendFileSync(out, `hay_problema=false\n`);
    return;
  }

  console.log(`${problemas.length} problema(s) encontrados:\n`);
  problemas.forEach((p) => console.log(`  - ${p}`));

  const mensaje = `ALAS 3 OS -- alerta:\n\n${problemas.map((p) => `- ${p}`).join("\n")}`;
  await enviarWhatsApp(mensaje);
  await enviarCorreo("ALAS 3 OS -- se encontro un problema", mensaje);

  process.exitCode = 1;
}

run().catch((e) => {
  console.error("Error inesperado corriendo el monitoreo:", e);
  process.exitCode = 1;
});
