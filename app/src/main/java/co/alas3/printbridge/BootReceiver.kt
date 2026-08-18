package co.alas3.printbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Arranca el servicio de impresion solo cuando la tablet se reinicia
 * (por un corte de luz, una actualizacion, etc.), sin que nadie tenga
 * que abrir la app a mano despues de un reinicio.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PrintPollingService.start(context)
        }
    }
}
