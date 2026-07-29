package co.alas3.printbridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * ALAS 3 OS -- Puente de impresion Android.
 *
 * La pagina web de Operaciones abre un enlace tipo:
 *   alas3print://print?data=BASE64_DE_COMANDOS_ESCPOS
 *
 * Esta app recibe ese enlace, decodifica los bytes y los manda directo a
 * la impresora termica conectada por USB (JALTECH JAL 58M u otra generica
 * ESC/POS), sin depender de ninguna otra app ni servicio pago.
 *
 * La primera vez que se use, Android va a preguntar "Permitir que Alas3
 * Impresora acceda al dispositivo USB?" -- hay que marcar "Usar por
 * defecto para este dispositivo USB" y aceptar. Despues de eso no vuelve
 * a preguntar.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Alas3PrintBridge"
        private const val ACTION_USB_PERMISSION = "co.alas3.printbridge.USB_PERMISSION"
        private const val BULK_TIMEOUT_MS = 4000
    }

    private lateinit var estadoTxt: TextView
    private lateinit var cerrarBtn: Button
    private lateinit var usbManager: UsbManager
    private var pendingBytes: ByteArray? = null
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION != intent.action) return
            synchronized(this) {
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    enviarAImpresora(device)
                } else {
                    mostrarEstado("Permiso USB denegado. Vuelve a intentar e acepta el permiso.", true)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        estadoTxt = findViewById(R.id.estadoTxt)
        cerrarBtn = findViewById(R.id.cerrarBtn)
        cerrarBtn.setOnClickListener { finish() }
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true

        procesarIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        procesarIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            try { unregisterReceiver(usbReceiver) } catch (e: Exception) { /* ya estaba sin registrar */ }
        }
    }

    private fun procesarIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val data = uri.getQueryParameter("data")
        if (data.isNullOrEmpty()) {
            mostrarEstado("No llego ningun dato para imprimir.", true)
            return
        }
        val bytes: ByteArray
        try {
            bytes = Base64.decode(data, Base64.DEFAULT)
        } catch (e: Exception) {
            mostrarEstado("El recibo llego en un formato invalido.", true)
            return
        }
        pendingBytes = bytes
        mostrarEstado("Buscando la impresora…", false)
        buscarImpresoraYEnviar()
    }

    private fun buscarImpresoraYEnviar() {
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            mostrarEstado("No se detecto ninguna impresora USB conectada. Revisa el cable.", true)
            return
        }
        // En una tablet POS normalmente solo hay un dispositivo USB conectado: la impresora.
        val device = deviceList.values.first()

        if (usbManager.hasPermission(device)) {
            enviarAImpresora(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            mostrarEstado("Solicitando permiso para usar la impresora…", false)
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun enviarAImpresora(device: UsbDevice) {
        val bytes = pendingBytes
        if (bytes == null) {
            mostrarEstado("No hay datos pendientes por imprimir.", true)
            return
        }
        val connection: UsbDeviceConnection? = usbManager.openDevice(device)
        if (connection == null) {
            mostrarEstado("No se pudo abrir la conexion con la impresora.", true)
            return
        }
        try {
            var usbInterface: UsbInterface? = null
            var endpointOut: UsbEndpoint? = null
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                for (e in 0 until iface.endpointCount) {
                    val endpoint = iface.getEndpoint(e)
                    if (endpoint.direction == UsbConstants.USB_DIR_OUT &&
                        endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                    ) {
                        usbInterface = iface
                        endpointOut = endpoint
                        break
                    }
                }
                if (endpointOut != null) break
            }

            if (usbInterface == null || endpointOut == null) {
                mostrarEstado("La impresora no tiene una salida de datos compatible.", true)
                return
            }

            if (!connection.claimInterface(usbInterface, true)) {
                mostrarEstado("No se pudo tomar el control de la impresora (interfaz ocupada).", true)
                return
            }

            val sent = connection.bulkTransfer(endpointOut, bytes, bytes.size, BULK_TIMEOUT_MS)
            connection.releaseInterface(usbInterface)

            if (sent >= 0) {
                mostrarEstado("Impreso correctamente ✓", false)
                pendingBytes = null
                estadoTxt.postDelayed({ finish() }, 1200)
            } else {
                mostrarEstado("La impresora no respondio a tiempo. Revisa que tenga papel y este encendida.", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando a la impresora", e)
            mostrarEstado("Error inesperado enviando a la impresora: " + e.message, true)
        } finally {
            connection.close()
        }
    }

    private fun mostrarEstado(msg: String, esError: Boolean) {
        runOnUiThread {
            estadoTxt.text = msg
            estadoTxt.setTextColor(if (esError) 0xFFFF6B6B.toInt() else 0xFFE8890A.toInt())
            cerrarBtn.visibility = if (esError) android.view.View.VISIBLE else android.view.View.GONE
        }
    }
}
