package co.alas3.printbridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * ALAS 3 OS -- Puente de impresion Android (v3: Foreground Service).
 *
 * Antes el sondeo vivia dentro de MainActivity, lo que en la practica
 * obligaba al operador a tener la app abierta en pantalla -- si se
 * minimizaba o se abria otra app, Android eventualmente pausaba o mataba
 * la Activity y dejaba de imprimir. Ahora el sondeo corre en un Service
 * en primer plano (con notificacion fija, obligatoria en Android para
 * este tipo de servicio) que sigue vivo aunque la app se cierre de la
 * pantalla, y que ademas arranca solo si la tablet se reinicia
 * (ver BootReceiver). Asi, una vez instalada y abierta una vez para
 * aceptar los permisos, no hace falta volver a tocarla para que imprima.
 */
class PrintPollingService : android.app.Service() {

    private enum class ResultadoImpresion { IMPRESO, SIN_IMPRESORA, ERROR }

    private class SesionInvalidaException(msg: String) : Exception(msg)

    companion object {
        private const val TAG = "Alas3PrintBridge"
        private const val ACTION_USB_PERMISSION = "co.alas3.printbridge.USB_PERMISSION"
        private const val BULK_TIMEOUT_MS = 4000

        private const val SB_URL = "https://cerhybkxvziukxlkrrkf.supabase.co"
        private const val SB_KEY = "sb_publishable_jcP3bIIssHblHBDts3kWNw_P7U9EZT4"

        private const val CODIGO_ACCESO = "impresora"
        private const val PIN = "482917"

        private const val POLL_INTERVAL_MS = 5000L
        private const val PREFS_NAME = "alas3_print_bridge"
        private const val PREF_TOKEN = "token"

        private const val CHANNEL_ID = "alas3_print_bridge"
        private const val NOTIF_ID = 1

        /** Lo lee MainActivity mientras esta visible, para reflejar el estado en pantalla. */
        @Volatile
        var listener: ((String, Boolean) -> Unit)? = null

        @Volatile
        var lastStatusMsg: String = "Conectando…"

        @Volatile
        var lastStatusEsError: Boolean = false

        fun start(context: Context) {
            val intent = Intent(context, PrintPollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var usbManager: UsbManager
    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var polling = false
    private var token: String? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION != intent.action) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (!granted) {
                mostrarEstado("Permiso USB denegado. Abre la app y acepta el permiso para la impresora.", true)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        token = prefs.getString(PREF_TOKEN, null)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true

        crearCanalNotificacion()
        startForeground(NOTIF_ID, construirNotificacion(lastStatusMsg))
        solicitarPermisoUsbSiHaceFalta()
        iniciarSondeo()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return android.app.Service.START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
        if (receiverRegistered) {
            try { unregisterReceiver(usbReceiver) } catch (e: Exception) { /* ya estaba sin registrar */ }
        }
    }

    private fun solicitarPermisoUsbSiHaceFalta() {
        val device = elegirDispositivoImpresora(usbManager.deviceList.values) ?: return
        if (!usbManager.hasPermission(device)) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    // ==================== NOTIFICACION (obligatoria para un Foreground Service) ====================

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = android.app.NotificationChannel(
                    CHANNEL_ID,
                    "Puente de impresion",
                    android.app.NotificationManager.IMPORTANCE_LOW
                )
                channel.description = "Mantiene la conexion con la cola de impresion de Alas 3"
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun construirNotificacion(texto: String): android.app.Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        val contentIntent = if (openIntent != null) {
            PendingIntent.getActivity(this, 0, openIntent, piFlags)
        } else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Alas 3 — Puente de impresion")
            .setContentText(texto)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()
    }

    private fun actualizarNotificacion(texto: String) {
        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try { nm.notify(NOTIF_ID, construirNotificacion(texto)) } catch (e: SecurityException) { /* sin permiso, la notificacion simplemente no se actualiza */ }
        }
    }

    // ==================== SONDEO ====================

    private fun iniciarSondeo() {
        if (polling) return
        polling = true
        mostrarEstado("Conectando…", false)
        programarSiguienteSondeo(0)
    }

    private fun programarSiguienteSondeo(delayMs: Long) {
        mainHandler.postDelayed({
            if (!polling) return@postDelayed
            Thread { cicloDeSondeo() }.start()
        }, delayMs)
    }

    private fun cicloDeSondeo() {
        try {
            if (token == null) {
                token = iniciarSesion()
                prefs.edit().putString(PREF_TOKEN, token).apply()
            }
            val trabajos = obtenerTrabajosPendientes(token!!)
            if (trabajos.length() == 0) {
                mostrarEstado("Esperando pedidos para imprimir…", false)
            } else {
                for (i in 0 until trabajos.length()) {
                    val resultado = procesarTrabajo(trabajos.getJSONObject(i))
                    // Si no hay impresora conectada, no tiene sentido intentar
                    // los demas trabajos de esta ronda -- se reintentan solos
                    // en el proximo sondeo, ninguno se pierde.
                    if (resultado == ResultadoImpresion.SIN_IMPRESORA) break
                }
            }
        } catch (e: SesionInvalidaException) {
            token = null
            prefs.edit().remove(PREF_TOKEN).apply()
            mostrarEstado("Sesion vencida, reconectando…", false)
        } catch (e: Exception) {
            Log.e(TAG, "Error en el sondeo", e)
            mostrarEstado(describirError(e), true)
        } finally {
            programarSiguienteSondeo(POLL_INTERVAL_MS)
        }
    }

    /**
     * Antes esto solo decia "Sin conexion con el servidor" para CUALQUIER
     * falla, sin decir cual -- imposible de diagnosticar sin ver el
     * Logcat del dispositivo (que el negocio no tiene como revisar). Ahora
     * muestra el tipo de error real en pantalla y en la notificacion.
     */
    private fun describirError(e: Exception): String {
        val detalle = (e.message ?: "").take(120)
        return when (e) {
            is java.net.UnknownHostException ->
                "No hay internet o no encuentra el servidor (UnknownHostException). Revisa el wifi de la tablet."
            is javax.net.ssl.SSLHandshakeException ->
                "Error de seguridad/certificado (SSLHandshakeException) -- probable que Android de esta tablet este muy desactualizado. Detalle: $detalle"
            is java.net.SocketTimeoutException ->
                "El servidor tardo demasiado en responder (SocketTimeoutException). Revisa la señal de wifi."
            else ->
                "Error: [" + e.javaClass.simpleName + "] " + detalle
        }
    }

    private fun procesarTrabajo(trabajo: JSONObject): ResultadoImpresion {
        val id = trabajo.getString("id")
        try {
            val bytes = Base64.decode(trabajo.getString("datos_escpos"), Base64.DEFAULT)
            val resultado = intentarImprimir(bytes)
            when (resultado) {
                ResultadoImpresion.IMPRESO -> {
                    marcarTrabajo(token!!, id, "impreso")
                    mostrarEstado("Recibo impreso ✓", false)
                }
                ResultadoImpresion.SIN_IMPRESORA -> {
                    mostrarEstado("Hay un recibo esperando, pero no se detecta la impresora. Revisa el cable USB.", true)
                }
                ResultadoImpresion.ERROR -> {
                    marcarTrabajo(token!!, id, "error")
                    mostrarEstado("No se pudo imprimir un recibo, revisa la impresora.", true)
                }
            }
            return resultado
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando trabajo $id", e)
            try { marcarTrabajo(token!!, id, "error") } catch (e2: Exception) { /* se reintenta en el proximo sondeo si esto tambien falla */ }
            mostrarEstado("Error procesando un recibo: " + e.message, true)
            return ResultadoImpresion.ERROR
        }
    }

    // ==================== IMPRESION USB ====================

    private fun intentarImprimir(bytes: ByteArray): ResultadoImpresion {
        val device = elegirDispositivoImpresora(usbManager.deviceList.values)
            ?: return ResultadoImpresion.SIN_IMPRESORA
        if (!usbManager.hasPermission(device)) {
            solicitarPermisoUsbSiHaceFalta()
            return ResultadoImpresion.SIN_IMPRESORA
        }
        val connection: UsbDeviceConnection = usbManager.openDevice(device)
            ?: return ResultadoImpresion.SIN_IMPRESORA
        try {
            val salida = encontrarEndpointSalida(device) ?: return ResultadoImpresion.SIN_IMPRESORA
            val (usbInterface, endpointOut) = salida
            if (!connection.claimInterface(usbInterface, true)) return ResultadoImpresion.ERROR
            val sent = connection.bulkTransfer(endpointOut, bytes, bytes.size, BULK_TIMEOUT_MS)
            connection.releaseInterface(usbInterface)
            return if (sent >= 0) ResultadoImpresion.IMPRESO else ResultadoImpresion.ERROR
        } finally {
            connection.close()
        }
    }

    private fun elegirDispositivoImpresora(devices: Collection<UsbDevice>): UsbDevice? {
        for (device in devices) {
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                    return device
                }
            }
        }
        for (device in devices) {
            if (encontrarEndpointSalida(device) != null) return device
        }
        return null
    }

    private fun encontrarEndpointSalida(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(e)
                if (endpoint.direction == UsbConstants.USB_DIR_OUT &&
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                ) {
                    return Pair(iface, endpoint)
                }
            }
        }
        return null
    }

    // ==================== BACKEND (Supabase, mismo patron que los paneles web) ====================

    private fun rpc(nombre: String, body: JSONObject): String {
        val conn = URL("$SB_URL/rest/v1/rpc/$nombre").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", SB_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SB_KEY")
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                if (code == 401 || text.contains("No autorizado") || text.contains("expirado")) {
                    throw SesionInvalidaException(text)
                }
                throw Exception("Error del servidor ($code): $text")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun iniciarSesion(): String {
        val body = JSONObject().put("p_codigo_acceso", CODIGO_ACCESO).put("p_pin", PIN)
        val arr = JSONArray(rpc("staff_login", body))
        if (arr.length() == 0) throw Exception("No se pudo iniciar sesion con la cuenta de la impresora")
        return arr.getJSONObject(0).getString("token")
    }

    private fun obtenerTrabajosPendientes(token: String): JSONArray {
        val body = JSONObject().put("p_token", token)
        return JSONArray(rpc("listar_trabajos_impresion_pendientes", body))
    }

    private fun marcarTrabajo(token: String, id: String, estado: String) {
        val body = JSONObject().put("p_token", token).put("p_trabajo_id", id).put("p_estado", estado)
        rpc("marcar_trabajo_impreso", body)
    }

    private fun mostrarEstado(msg: String, esError: Boolean) {
        lastStatusMsg = msg
        lastStatusEsError = esError
        mainHandler.post {
            listener?.invoke(msg, esError)
            actualizarNotificacion(msg)
        }
    }
}
