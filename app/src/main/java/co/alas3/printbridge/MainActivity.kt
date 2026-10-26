package co.alas3.printbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
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
    private lateinit var pinInp: EditText
    private lateinit var guardarPinBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        estadoTxt = findViewById(R.id.estadoTxt)
        cerrarBtn = findViewById(R.id.cerrarBtn)
        cerrarBtn.setOnClickListener { finish() }
        pinInp = findViewById(R.id.pinInp)
        guardarPinBtn = findViewById(R.id.guardarPinBtn)
        guardarPinBtn.setOnClickListener { guardarPin() }

        solicitarPermisoNotificacionesSiHaceFalta()
        PrintPollingService.start(this)
    }

    /**
     * El PIN de la cuenta "impresora" se escribe aqui y queda guardado solo en esta tablet (antes iba
     * escrito dentro del codigo de la app). Al guardarlo, el servicio vuelve a iniciar sesion con el nuevo.
     */
    private fun guardarPin() {
        val pin = pinInp.text.toString().trim()
        if (!pin.matches(Regex("^\\d{4,8}$"))) {
            mostrarEstado("El PIN debe tener de 4 a 8 numeros.", true)
            return
        }
        getSharedPreferences(PrintPollingService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PrintPollingService.PREF_PIN, pin)
            .remove(PrintPollingService.PREF_TOKEN)
            .apply()
        PrintPollingService.reconectar = true
        pinInp.setText("")
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(pinInp.windowToken, 0)
        mostrarEstado("PIN guardado. Conectando…", false)
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
