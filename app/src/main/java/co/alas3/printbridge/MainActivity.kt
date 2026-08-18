package co.alas3.printbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * ALAS 3 OS -- Puente de impresion Android (v3).
 *
 * El trabajo real (sondear la cola de impresion y mandar los recibos a
 * la impresora USB) vive en PrintPollingService, que corre en primer
 * plano de forma independiente. Esta pantalla solo sirve para: arrancar
 * ese servicio la primera vez, aceptar los permisos que Android pide
 * (USB y notificaciones), y mostrar el ultimo estado conocido mientras
 * esta abierta. Se puede cerrar sin que la impresion se detenga.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_NOTIFICACIONES = 100
    }

    private lateinit var estadoTxt: TextView
    private lateinit var cerrarBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        estadoTxt = findViewById(R.id.estadoTxt)
        cerrarBtn = findViewById(R.id.cerrarBtn)
        cerrarBtn.setOnClickListener { finish() }

        solicitarPermisoNotificacionesSiHaceFalta()
        PrintPollingService.start(this)
    }

    override fun onResume() {
        super.onResume()
        PrintPollingService.listener = { msg, esError -> mostrarEstado(msg, esError) }
        mostrarEstado(PrintPollingService.lastStatusMsg, PrintPollingService.lastStatusEsError)
    }

    override fun onPause() {
        super.onPause()
        PrintPollingService.listener = null
    }

    private fun solicitarPermisoNotificacionesSiHaceFalta() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICACIONES
            )
        }
    }

    private fun mostrarEstado(msg: String, esError: Boolean) {
        estadoTxt.text = msg
        estadoTxt.setTextColor(if (esError) 0xFFFF6B6B.toInt() else 0xFFE8890A.toInt())
    }
}
