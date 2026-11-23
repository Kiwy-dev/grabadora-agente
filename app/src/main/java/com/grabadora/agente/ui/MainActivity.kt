package com.grabadora.agente.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.grabadora.agente.AudioApp
import com.grabadora.agente.R
import com.grabadora.agente.agent.AgentState
import com.grabadora.agente.agent.Estado
import com.grabadora.agente.agent.KeywordService
import com.grabadora.agente.agent.ModeloManager
import com.grabadora.agente.agent.Prefs
import com.grabadora.agente.data.Audio
import com.grabadora.agente.data.AudioRepository
import com.grabadora.agente.record.Recorder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISO_GRABAR = 1
        private const val PERMISO_ESCUCHAR = 2
    }

    private val repo: AudioRepository get() = (application as AudioApp).repo
    private lateinit var prefs: Prefs
    private lateinit var recorder: Recorder
    private lateinit var adapter: AudioAdapter

    private lateinit var chips: ChipGroup
    private lateinit var vacio: TextView
    private lateinit var estado: TextView
    private lateinit var interruptor: MaterialSwitch
    private lateinit var botonGrabar: ExtendedFloatingActionButton
    private lateinit var botonReintentar: MaterialButton
    private lateinit var botonCortar: MaterialButton

    private var player: MediaPlayer? = null
    private var carpeta = AudioRepository.CARPETA_GENERAL
    private var carpetas: List<String> = emptyList()
    private var cambiandoInterruptor = false
    private val handler = Handler(Looper.getMainLooper())

    /** Disco y SQLite fuera del hilo principal (evita tirones con muchas grabaciones). Un solo hilo = en orden. */
    private val io = Executors.newSingleThreadExecutor()

    private val reloj = object : Runnable {
        override fun run() {
            if (!recorder.grabando) return
            estado.text = getString(R.string.grabando_manual, AudioAdapter.duracion(recorder.transcurridoMs))
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        prefs = Prefs(this)
        recorder = Recorder(this)
        carpeta = prefs.carpetaActual

        chips = findViewById(R.id.chips)
        vacio = findViewById(R.id.vacio)
        estado = findViewById(R.id.estado)
        interruptor = findViewById(R.id.interruptor)
        botonGrabar = findViewById(R.id.grabar)
        botonReintentar = findViewById(R.id.reintentar)
        botonCortar = findViewById(R.id.detener_grabacion)

        adapter = AudioAdapter(::alternarReproduccion, ::mostrarMenuAudio)
        findViewById<RecyclerView>(R.id.lista).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        botonGrabar.setOnClickListener { alternarGrabacion() }
        botonReintentar.setOnClickListener { reintentarModelo() }
        botonCortar.setOnClickListener {
            botonCortar.isEnabled = false // evita doble toque mientras el servicio responde
            KeywordService.cortarGrabacion(this)
        }

        interruptor.setOnCheckedChangeListener { _, activo ->
            if (cambiandoInterruptor) return@setOnCheckedChangeListener
            if (activo) activarEscucha() else KeywordService.detener(this)
        }

        // La lista se refresca sola cuando el repositorio cambia (incluido lo que graba el servicio)
        repo.cambios.observe(this) { recargar() }
        AgentState.info.observe(this) { info ->
            cambiandoInterruptor = true
            interruptor.isChecked = info.estado != Estado.APAGADO
            cambiandoInterruptor = false
            actualizarTarjeta()
        }
        ModeloManager.estado.observe(this) { actualizarTarjeta() }

        // Copia y verifica el modelo de voz en segundo plano desde que se abre la app,
        // para que al activar la escucha ya esté listo.
        ModeloManager.prepararEnSegundoPlano(this)
    }

    /** Une el estado del agente y el del modelo en la tarjeta de arriba. */
    private fun actualizarTarjeta() {
        if (recorder.grabando) return
        val info = AgentState.info.value ?: return
        val modelo = ModeloManager.estado.value
        val apagado = info.estado == Estado.APAGADO
        val preparando = modelo?.fase == ModeloManager.Fase.PREPARANDO
        val conError = modelo?.fase == ModeloManager.Fase.ERROR

        interruptor.isEnabled = !(apagado && preparando)
        botonReintentar.visibility = if (apagado && conError) View.VISIBLE else View.GONE
        val grabandoPorVoz = info.estado == Estado.GRABANDO
        botonCortar.visibility = if (grabandoPorVoz) View.VISIBLE else View.GONE
        botonCortar.isEnabled = grabandoPorVoz
        estado.text = when {
            !apagado -> info.detalle
            preparando -> modelo?.mensaje
            conError -> modelo?.mensaje
            else -> info.detalle.ifEmpty { getString(R.string.estado_apagado) }
        }
    }

    /** Borra la copia del modelo, la vuelve a hacer desde cero y, si sale bien, activa la escucha. */
    private fun reintentarModelo() {
        botonReintentar.visibility = View.GONE
        ModeloManager.prepararEnSegundoPlano(this, forzar = true) { ok ->
            if (ok) handler.post { if (!isFinishing) activarEscucha() }
        }
    }

    /** Copia al portapapeles lo necesario para saber por qué falla en un teléfono concreto. */
    private fun copiarDiagnostico() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        val error = ModeloManager.ultimoError
        val texto = buildString {
            appendLine("Grabadora Agente $version")
            appendLine("Teléfono: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Espacio libre: ${ModeloManager.espacioLibre(this@MainActivity)}")
            appendLine("Modelo: ${ModeloManager.estado.value?.fase} ${ModeloManager.rutaEnUso ?: ""}")
            appendLine("Agente: ${AgentState.info.value?.estado} ${AgentState.info.value?.detalle.orEmpty()}")
            if (error != null) {
                appendLine("Último error: ${error.javaClass.name}: ${ModeloManager.detalle(error)}")
                error.stackTrace.take(8).forEach { appendLine("  at $it") }
            } else {
                appendLine("Último error: ninguno")
            }
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.copiar_diagnostico), texto))
        aviso(R.string.diagnostico_copiado)
    }

    // ---------- Cap. 6: ciclo de vida. Al salir paramos audio y grabación ----------
    override fun onStop() {
        super.onStop()
        detenerReproduccion()
        if (recorder.grabando) terminarGrabacionManual()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        io.shutdown()
        super.onDestroy()
    }

    // ---------- Menú de la barra ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nueva_carpeta -> pedirNombreCarpeta { recargar() }
            R.id.borrar_carpeta -> borrarCarpetaActual()
            R.id.diagnostico -> copiarDiagnostico()
            R.id.ajustes -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- Lista y carpetas ----------

    /** Lee carpetas y audios en el hilo `io` y pinta el resultado en el hilo principal. */
    private fun recargar() {
        val pedida = carpeta
        io.execute {
            val lista = repo.carpetas()
            val actual = if (pedida in lista) pedida else AudioRepository.CARPETA_GENERAL
            val audios = repo.listar(actual)
            handler.post { if (!isDestroyed) mostrar(lista, pedida, actual, audios) }
        }
    }

    private fun mostrar(carpetas: List<String>, pedida: String, actual: String, audios: List<Audio>) {
        if (carpeta != pedida) return // el usuario ya eligió otra carpeta; viene otra recarga
        carpeta = actual
        this.carpetas = carpetas
        chips.removeAllViews()
        carpetas.forEach { nombre ->
            val chip = Chip(this).apply {
                text = nombre
                isCheckable = true
                isChecked = nombre == carpeta
                setOnClickListener {
                    carpeta = nombre
                    prefs.carpetaActual = nombre
                    recargar()
                }
            }
            chips.addView(chip)
        }
        adapter.submitList(audios)
        vacio.visibility = if (audios.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun pedirNombreCarpeta(alCrear: (String) -> Unit) {
        val campo = EditText(this).apply { hint = getString(R.string.nombre_carpeta) }
        AlertDialog.Builder(this)
            .setTitle(R.string.nueva_carpeta)
            .setView(envolver(campo))
            .setPositiveButton(R.string.crear) { _, _ ->
                val nombre = campo.text.toString().trim()
                if (repo.crearCarpeta(nombre)) alCrear(nombre)
                else aviso(R.string.carpeta_invalida)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun borrarCarpetaActual() {
        if (carpeta == AudioRepository.CARPETA_GENERAL) { aviso(R.string.no_borrar_general); return }
        if (adapter.currentList.isNotEmpty()) { aviso(R.string.carpeta_no_vacia); return }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirmar_borrar_carpeta, carpeta))
            .setPositiveButton(R.string.borrar) { _, _ -> repo.borrarCarpeta(carpeta) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- Acciones sobre un audio ----------

    private fun mostrarMenuAudio(audio: Audio, ancla: View) {
        PopupMenu(this, ancla).apply {
            menuInflater.inflate(R.menu.item_menu, menu)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.compartir -> compartir(audio)
                    R.id.mover -> elegirCarpetaDestino(audio)
                    R.id.renombrar -> renombrar(audio)
                    R.id.borrar -> confirmarBorrado(audio)
                }
                true
            }
            show()
        }
    }

    /** Cap. 3: intención implícita ACTION_SEND. FileProvider da permiso temporal de lectura. */
    private fun compartir(audio: Audio) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", audio.archivo)
        val enviar = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, audio.nombre)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(enviar, getString(R.string.compartir)))
    }

    private fun elegirCarpetaDestino(audio: Audio) {
        val otras = carpetas.filter { it != audio.carpeta }
        val opciones = (otras + getString(R.string.nueva_carpeta_puntos)).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.mover_a)
            .setItems(opciones) { _, i ->
                if (i == otras.size) pedirNombreCarpeta { nueva -> mover(audio, nueva) }
                else mover(audio, otras[i])
            }
            .show()
    }

    private fun mover(audio: Audio, destino: String) {
        if (audio.id == adapter.sonandoId) detenerReproduccion()
        if (repo.mover(audio, destino)) Toast.makeText(this, getString(R.string.movido_a, destino), Toast.LENGTH_SHORT).show()
        else aviso(R.string.error_mover)
    }

    private fun renombrar(audio: Audio) {
        val campo = EditText(this).apply { setText(audio.nombre); selectAll() }
        AlertDialog.Builder(this)
            .setTitle(R.string.renombrar)
            .setView(envolver(campo))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (audio.id == adapter.sonandoId) detenerReproduccion()
                if (!repo.renombrar(audio, campo.text.toString())) aviso(R.string.nombre_invalido)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmarBorrado(audio: Audio) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirmar_borrar, audio.nombre))
            .setPositiveButton(R.string.borrar) { _, _ ->
                if (audio.id == adapter.sonandoId) detenerReproduccion()
                repo.borrar(audio)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- Reproducción (cap. 6: MediaPlayer) ----------

    private fun alternarReproduccion(audio: Audio) {
        val eraEste = audio.id == adapter.sonandoId
        detenerReproduccion()
        if (eraEste) return
        try {
            // prepareAsync: no bloquea la pantalla mientras abre el archivo.
            player = MediaPlayer().apply {
                setDataSource(audio.ruta)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { detenerReproduccion() }
                setOnErrorListener { _, _, _ -> detenerReproduccion(); aviso(R.string.error_reproducir); true }
                prepareAsync()
            }
            adapter.sonandoId = audio.id
        } catch (e: Exception) {
            detenerReproduccion()
            aviso(R.string.error_reproducir)
        }
    }

    private fun detenerReproduccion() {
        player?.release()
        player = null
        if (adapter.sonandoId != -1L) adapter.sonandoId = -1
    }

    // ---------- Grabación manual ----------

    private fun alternarGrabacion() {
        if (recorder.grabando) { terminarGrabacionManual(); return }
        if (AgentState.activo) { aviso(R.string.apaga_escucha_primero); return }
        if (!tienePermiso(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERMISO_GRABAR)
            return
        }
        detenerReproduccion()
        try {
            recorder.iniciar(repo.nuevoArchivo(carpeta))
        } catch (e: Exception) { // incluye IllegalStateException si ya grababa
            aviso(R.string.error_grabar)
            return
        }
        botonGrabar.setText(R.string.detener)
        botonGrabar.setIconResource(R.drawable.ic_stop)
        interruptor.isEnabled = false
        handler.post(reloj)
    }

    private fun terminarGrabacionManual() {
        handler.removeCallbacks(reloj)
        val archivo = recorder.archivo
        val duracion = recorder.detener()
        if (duracion > 0 && archivo != null) repo.agregar(archivo, carpeta, duracion, porVoz = false)
        botonGrabar.setText(R.string.grabar)
        botonGrabar.setIconResource(R.drawable.ic_mic)
        interruptor.isEnabled = true
        actualizarTarjeta()
    }

    // ---------- Escucha por palabra clave ----------

    private fun activarEscucha() {
        val faltan = mutableListOf<String>()
        if (!tienePermiso(Manifest.permission.RECORD_AUDIO)) faltan += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !tienePermiso(Manifest.permission.POST_NOTIFICATIONS))
            faltan += Manifest.permission.POST_NOTIFICATIONS
        if (faltan.isNotEmpty()) {
            cambiandoInterruptor = true
            interruptor.isChecked = false
            cambiandoInterruptor = false
            ActivityCompat.requestPermissions(this, faltan.toTypedArray(), PERMISO_ESCUCHAR)
            return
        }
        iniciarAgente()
    }

    /** Android 12+ puede negar el servicio en primer plano; en vez de cerrarse, la app avisa. */
    private fun iniciarAgente() {
        try {
            KeywordService.iniciar(this)
        } catch (e: Exception) {
            cambiandoInterruptor = true
            interruptor.isChecked = false
            cambiandoInterruptor = false
            aviso(R.string.motivo_no_permitido)
        }
    }

    // ---------- Cap. 7: permisos en tiempo de ejecución ----------

    private fun tienePermiso(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val micOk = tienePermiso(Manifest.permission.RECORD_AUDIO)
        if (!micOk) { aviso(R.string.sin_permiso_mic); return }
        when (requestCode) {
            PERMISO_GRABAR -> alternarGrabacion()
            PERMISO_ESCUCHAR -> iniciarAgente() // la notificación es opcional
        }
    }

    // ---------- Utilidades ----------

    private fun aviso(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    private fun envolver(v: View): View {
        val margen = (20 * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            setPadding(margen, margen / 2, margen, 0)
            addView(v)
        }
    }
}
