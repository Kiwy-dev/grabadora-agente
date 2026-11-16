package com.grabadora.agente.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.grabadora.agente.AudioApp
import com.grabadora.agente.R
import com.grabadora.agente.record.Recorder
import com.grabadora.agente.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.concurrent.Executors

/**
 * El "agente". Cap. 8 del libro: servicio en primer plano con notificación
 * y BroadcastReceivers del sistema.
 *
 * Ciclo:  ESCUCHANDO (Vosk, solo la palabra clave) → la oye → GRABANDO (MediaRecorder)
 *         → silencio o tiempo máximo → guarda el audio → vuelve a ESCUCHANDO.
 *
 * No está activo 24/7:
 *  - Solo arranca cuando el usuario lo activa (interruptor, mosaico, notificación).
 *  - Se apaga solo tras N minutos sin oír la palabra clave.
 *  - Se pausa con la pantalla apagada y se reanuda al encenderla (opcional).
 *  - Se apaga con batería baja o al desconectar el cargador (opcional).
 *  - START_NOT_STICKY: si Android lo mata, no se reinicia por su cuenta.
 */
class KeywordService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "KeywordService"
        private const val ACCION_INICIAR = "com.grabadora.agente.INICIAR"
        private const val ACCION_DETENER = "com.grabadora.agente.DETENER"
        private const val ACCION_CORTAR = "com.grabadora.agente.CORTAR_GRABACION"
        private const val CANAL = "agente"
        private const val ID_NOTIF = 1
        private const val UMBRAL_SILENCIO = 1500 // amplitud (0..32767) por debajo de la cual es silencio
        private const val TASA = 16000f

        fun iniciar(context: Context) {
            val i = Intent(context, KeywordService::class.java).setAction(ACCION_INICIAR)
            ContextCompat.startForegroundService(context, i)
        }

        /** Termina la grabación por voz en curso y vuelve a escuchar (no apaga el agente). */
        fun cortarGrabacion(context: Context) {
            if (!AgentState.activo) return
            context.startService(Intent(context, KeywordService::class.java).setAction(ACCION_CORTAR))
        }

        fun detener(context: Context) {
            if (!AgentState.activo) return
            context.startService(Intent(context, KeywordService::class.java).setAction(ACCION_DETENER))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val hiloModelo = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var recorder: Recorder

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null

    private var iniciado = false
    private var pausadoPorPantalla = false
    private var silencioDesde = 0L
    private var palabra = ""

    private val repo get() = (application as AudioApp).repo

    // ---------------- Receptores del sistema (cap. 8) ----------------

    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (prefs.pausarPantallaApagada) {
                    pausadoPorPantalla = true
                    pausar(getString(R.string.estado_pausa_pantalla))
                }
                Intent.ACTION_SCREEN_ON -> if (pausadoPorPantalla) {
                    pausadoPorPantalla = false
                    if (!recorder.grabando) escuchar()
                }
                Intent.ACTION_BATTERY_LOW -> if (prefs.pararBateriaBaja) apagar(getString(R.string.motivo_bateria))
                Intent.ACTION_POWER_DISCONNECTED -> if (prefs.soloCargando) apagar(getString(R.string.motivo_cargador))
            }
        }
    }

    private val apagadoPorInactividad = Runnable {
        if (recorder.grabando) reiniciarInactividad()
        else apagar(getString(R.string.motivo_inactividad, prefs.minutosInactividad))
    }

    private val vigilarGrabacion = object : Runnable {
        override fun run() {
            if (!recorder.grabando) return
            val ahora = System.currentTimeMillis()
            if (recorder.amplitud() > UMBRAL_SILENCIO) silencioDesde = ahora
            val transcurrido = recorder.transcurridoMs
            val silencioLargo = ahora - silencioDesde > prefs.segundosSilencio * 1000L && transcurrido > 1500
            if (silencioLargo || transcurrido >= prefs.segundosMaximo * 1000L) {
                terminarGrabacion()
                if (!pausadoPorPantalla) escuchar() else pausar(getString(R.string.estado_pausa_pantalla))
            } else {
                handler.postDelayed(this, 250)
            }
        }
    }

    // ---------------- Ciclo de vida del servicio ----------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        recorder = Recorder(this)
        crearCanal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DETENER) {
            apagar(null)
            return START_NOT_STICKY
        }
        if (intent?.action == ACCION_CORTAR) {
            if (iniciado && recorder.grabando) {
                terminarGrabacion()
                if (!pausadoPorPantalla) escuchar() else pausar(getString(R.string.estado_pausa_pantalla))
            }
            return START_NOT_STICKY
        }
        // Hay que llamar a startForeground() en los primeros segundos (cap. 8)
        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        if (iniciado) {
            // Ya estaba activo: cada startForegroundService() exige volver a llamar a startForeground()
            val texto = AgentState.info.value?.detalle ?: getString(R.string.estado_preparando)
            ServiceCompat.startForeground(this, ID_NOTIF, notificacion(texto), tipo)
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, ID_NOTIF, notificacion(getString(R.string.estado_preparando)), tipo)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo iniciar en primer plano", e)
            AgentState.publicar(this, InfoAgente(Estado.APAGADO, getString(R.string.motivo_no_permitido)))
            stopSelf()
            return START_NOT_STICKY
        }
        iniciado = true

        if (prefs.soloCargando && !estaCargando()) {
            apagar(getString(R.string.motivo_solo_cargando))
            return START_NOT_STICKY
        }

        registrarReceptores()
        pausadoPorPantalla = prefs.pausarPantallaApagada &&
            !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        publicar(Estado.PREPARANDO, getString(R.string.estado_preparando))
        cargarModelo()
        reiniciarInactividad()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (iniciado) liberar()
        hiloModelo.shutdown()
        super.onDestroy()
    }

    // ---------------- Palabra clave (Vosk) ----------------

    private fun cargarModelo() {
        // ModeloManager copia el modelo (la primera vez), lo verifica y lo abre; si falla, reintenta una vez.
        // Corre en otro hilo porque abrir el modelo tarda unos segundos.
        hiloModelo.execute {
            val resultado = runCatching { ModeloManager.cargar(this) } // runCatching atrapa Throwable
            handler.post {
                resultado.onSuccess { m ->
                    if (!iniciado) { m.close(); return@post }
                    model = m
                    if (pausadoPorPantalla) pausar(getString(R.string.estado_pausa_pantalla)) else escuchar()
                }.onFailure { e ->
                    Log.e(TAG, "Modelo no disponible", e)
                    apagar(ModeloManager.mensaje(this, e))
                }
            }
        }
    }

    private fun escuchar() {
        val m = model ?: return
        detenerEscucha()
        palabra = prefs.palabraClave
        try {
            // Gramática cerrada: solo reconoce la palabra clave o "[unk]" (cualquier otra cosa).
            // Así consume muy poco y casi no da falsos positivos.
            val gramatica = JSONArray(listOf(palabra, "[unk]")).toString()
            val rec = Recognizer(m, TASA, gramatica)
            recognizer = rec
            speech = SpeechService(rec, TASA).also { it.startListening(this) }
            publicar(Estado.ESCUCHANDO, getString(R.string.estado_escuchando, palabra))
        } catch (e: Throwable) { // Throwable: Recognizer es código nativo
            Log.e(TAG, "No se pudo abrir el micrófono", e)
            apagar(getString(R.string.motivo_microfono_detalle, ModeloManager.detalle(e)))
        }
    }

    private fun detenerEscucha() {
        speech?.let { it.stop(); it.shutdown() }
        speech = null
        recognizer?.close()
        recognizer = null
    }

    private fun revisar(json: String?) {
        if (json == null || recorder.grabando || speech == null) return
        val o = JSONObject(json)
        val texto = o.optString("text", o.optString("partial", ""))
        if (texto.contains(palabra)) handler.post { alOirPalabraClave() }
    }

    override fun onPartialResult(hypothesis: String?) = revisar(hypothesis)
    override fun onResult(hypothesis: String?) = revisar(hypothesis)
    override fun onFinalResult(hypothesis: String?) = revisar(hypothesis)
    override fun onError(exception: Exception?) {
        Log.e(TAG, "Error de reconocimiento", exception)
        apagar(if (exception != null) getString(R.string.motivo_microfono_detalle, ModeloManager.detalle(exception))
               else getString(R.string.motivo_microfono))
    }
    override fun onTimeout() = Unit

    // ---------------- Grabación ----------------

    private fun alOirPalabraClave() {
        if (recorder.grabando || speech == null) return
        detenerEscucha() // libera el micrófono para MediaRecorder
        val archivo = repo.nuevoArchivo(prefs.carpetaVoz)
        try {
            recorder.iniciar(archivo)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo grabar", e)
            escuchar()
            return
        }
        silencioDesde = System.currentTimeMillis()
        publicar(Estado.GRABANDO, getString(R.string.estado_grabando_voz))
        handler.postDelayed(vigilarGrabacion, 250)
    }

    private fun terminarGrabacion() {
        handler.removeCallbacks(vigilarGrabacion)
        val archivo: File? = recorder.archivo
        val duracion = recorder.detener()
        if (duracion > 0 && archivo != null) repo.agregar(archivo, prefs.carpetaVoz, duracion, porVoz = true)
        reiniciarInactividad()
    }

    // ---------------- Pausa / apagado ----------------

    private fun pausar(motivo: String) {
        if (recorder.grabando) return // terminará la grabación y luego se pausa
        detenerEscucha()
        publicar(Estado.PAUSADO, motivo)
    }

    private fun reiniciarInactividad() {
        handler.removeCallbacks(apagadoPorInactividad)
        val min = prefs.minutosInactividad
        if (min > 0) handler.postDelayed(apagadoPorInactividad, min * 60_000L)
    }

    private fun apagar(motivo: String?) {
        if (!iniciado) { stopSelf(); return }
        liberar()
        AgentState.publicar(this, InfoAgente(Estado.APAGADO, motivo ?: ""))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun liberar() {
        iniciado = false
        handler.removeCallbacksAndMessages(null)
        if (recorder.grabando) {
            val archivo = recorder.archivo
            val d = recorder.detener()
            if (d > 0 && archivo != null) repo.agregar(archivo, prefs.carpetaVoz, d, porVoz = true)
        }
        detenerEscucha()
        model?.close()
        model = null
        runCatching { unregisterReceiver(receptor) }
    }

    private fun registrarReceptores() {
        val filtro = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, receptor, filtro, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Cap. 8: ACTION_BATTERY_CHANGED es "sticky"; registrar con receptor null devuelve el último valor. */
    private fun estaCargando(): Boolean {
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    // ---------------- Notificación ----------------

    private fun publicar(estado: Estado, detalle: String) {
        AgentState.publicar(this, InfoAgente(estado, detalle))
        if (iniciado) getSystemService(NotificationManager::class.java).notify(ID_NOTIF, notificacion(detalle))
    }

    private fun crearCanal() {
        val canal = NotificationChannel(CANAL, getString(R.string.canal_agente), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
    }

    private fun notificacion(texto: String): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val parar = PendingIntent.getService(
            this, 1, Intent(this, KeywordService::class.java).setAction(ACCION_DETENER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        // Mientras graba por voz, un botón para cortar solo esa grabación y seguir escuchando
        if (::recorder.isInitialized && recorder.grabando) {
            val cortar = PendingIntent.getService(
                this, 2, Intent(this, KeywordService::class.java).setAction(ACCION_CORTAR),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(R.drawable.ic_stop, getString(R.string.detener_grabacion), cortar)
        }
        return builder.addAction(R.drawable.ic_stop, getString(R.string.detener), parar).build()
    }
}
