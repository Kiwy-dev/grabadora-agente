package com.grabadora.agente.agent

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.grabadora.agente.R
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Prepara el modelo de voz de Vosk sin depender de StorageService.unpack (no viene en el libro).
 *
 * Por qué no usamos StorageService:
 *  - Solo copia a getExternalFilesDir(), que en algunos teléfonos no está disponible.
 *  - No verifica la copia: un archivo dañado con el uuid correcto se queda así para siempre.
 *  - No revisa el espacio libre antes de copiar.
 *
 * Aquí:
 *  1. Copiamos a la memoria interna (filesDir); el almacenamiento externo queda de respaldo.
 *  2. La copia es atómica: se escribe en "model-es.tmp", se verifica el tamaño de cada archivo
 *     y solo entonces se renombra a "model-es". Nunca queda una copia a medias que parezca buena.
 *  3. Revisamos el espacio libre antes de copiar.
 *  4. Si Vosk no puede abrir la copia, la borramos, copiamos de nuevo y probamos una vez más.
 *  5. Atrapamos Throwable: los errores de la librería nativa (UnsatisfiedLinkError) no son Exception.
 */
object ModeloManager {

    private const val TAG = "ModeloManager"
    private const val ASSET = "model-es"
    private const val DESTINO = "model-es"
    private const val TEMPORAL = "model-es.tmp"
    private const val LEGADO = "model" // carpeta que usaba StorageService en la versión 0.1
    private const val MARGEN = 10L * 1024 * 1024

    enum class Fase { SIN_PREPARAR, PREPARANDO, LISTO, ERROR }

    data class InfoModelo(val fase: Fase, val mensaje: String = "")

    /** Errores con un mensaje ya listo para mostrar al usuario. */
    class ErrorModelo(val mensaje: String, causa: Throwable? = null) : Exception(mensaje, causa)

    val estado = MutableLiveData(InfoModelo(Fase.SIN_PREPARAR))

    /** Para "Copiar diagnóstico". */
    @Volatile var ultimoError: Throwable? = null
        private set
    @Volatile var rutaEnUso: File? = null
        private set

    private val hilo = Executors.newSingleThreadExecutor()
    private var manifiesto: Map<String, Long>? = null

    // ---------------- API pública ----------------

    /** Prepara el modelo en segundo plano (al abrir la app). Si ya está listo no hace nada. */
    fun prepararEnSegundoPlano(context: Context, forzar: Boolean = false, alTerminar: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        if (!forzar && estado.value?.fase == Fase.LISTO) { alTerminar?.invoke(true); return }
        hilo.execute {
            val ok = try { asegurar(app, forzar); true } catch (t: Throwable) { false }
            alTerminar?.invoke(ok)
        }
    }

    /**
     * Devuelve el modelo abierto, listo para usar. Bloquea: llamar fuera del hilo principal.
     * Si la primera apertura falla, borra la copia, copia de nuevo y vuelve a intentar.
     */
    fun cargar(context: Context): Model {
        val app = context.applicationContext
        val dir = asegurar(app, forzar = false)
        return try {
            Model(dir.absolutePath)
        } catch (primero: Throwable) {
            Log.w(TAG, "No se pudo abrir la copia; se vuelve a copiar", primero)
            if (esIncompatible(primero)) throw fallar(app, primero)
            val nuevo = asegurar(app, forzar = true)
            try {
                Model(nuevo.absolutePath)
            } catch (segundo: Throwable) {
                throw fallar(app, segundo)
            }
        }
    }

    /** Traduce cualquier error a un texto para el usuario. */
    fun mensaje(context: Context, t: Throwable): String = when {
        t is ErrorModelo -> t.mensaje
        esIncompatible(t) -> context.getString(R.string.modelo_incompatible, detalle(t))
        else -> context.getString(R.string.motivo_error_modelo, detalle(t))
    }

    /** Texto corto con la causa real: recorre la cadena de excepciones. */
    fun detalle(e: Throwable): String {
        val partes = mutableListOf<String>()
        var t: Throwable? = e
        while (t != null && partes.size < 3) {
            partes += t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            t = t.cause?.takeIf { it !== t }
        }
        return partes.distinct().joinToString(" → ")
    }

    // ---------------- Preparación ----------------

    @Synchronized
    private fun asegurar(app: Context, forzar: Boolean): File {
        estado.postValue(InfoModelo(Fase.PREPARANDO, app.getString(R.string.modelo_preparando)))
        try {
            // Carga la librería nativa ya: si el teléfono no es compatible lo sabemos ahora.
            LibVosk.setLogLevel(LogLevel.WARNINGS)

            val esperado = manifiesto(app)
            val uuid = leerAsset(app, "$ASSET/uuid").trim()
            borrar(app.getExternalFilesDir(null)?.let { File(it, LEGADO) }) // libera los ~60 MB de la v0.1

            val bases = listOfNotNull(app.filesDir, app.getExternalFilesDir(null))
            if (forzar) bases.forEach { borrar(File(it, DESTINO)) }

            // ¿Ya hay una copia buena en alguna de las ubicaciones?
            if (!forzar) {
                bases.map { File(it, DESTINO) }.firstOrNull { copiaValida(it, esperado, uuid) }?.let {
                    return listo(app, it)
                }
            }

            val necesario = esperado.values.sum() + MARGEN
            var ultimo: Throwable? = null
            for (base in bases) {
                if (libre(base) < necesario) {
                    ultimo = ErrorModelo(app.getString(R.string.modelo_sin_espacio, mb(necesario - libre(base)).coerceAtLeast(1)))
                    continue
                }
                try {
                    return listo(app, copiar(app, base, esperado))
                } catch (t: Throwable) {
                    Log.w(TAG, "Falló la copia en $base", t)
                    ultimo = t
                    borrar(File(base, TEMPORAL))
                }
            }
            throw ultimo ?: IOException("No hay dónde guardar el modelo")
        } catch (t: Throwable) {
            throw fallar(app, t)
        }
    }

    private fun listo(app: Context, dir: File): File {
        rutaEnUso = dir
        ultimoError = null
        estado.postValue(InfoModelo(Fase.LISTO))
        Log.i(TAG, "Modelo listo en $dir")
        return dir
    }

    private fun fallar(app: Context, t: Throwable): Throwable {
        val error = if (t is ErrorModelo) t else ErrorModelo(mensaje(app, t), t)
        ultimoError = error.cause ?: error
        estado.postValue(InfoModelo(Fase.ERROR, error.mensaje))
        Log.e(TAG, "Modelo no disponible", t)
        return error
    }

    /** Copia atómica: todo a model-es.tmp, verifica y renombra. El uuid va al final. */
    private fun copiar(app: Context, base: File, esperado: Map<String, Long>): File {
        val tmp = File(base, TEMPORAL)
        val destino = File(base, DESTINO)
        borrar(tmp)
        val ordenados = esperado.keys.sortedBy { it == "uuid" }
        for (rel in ordenados) {
            val salida = File(tmp, rel)
            salida.parentFile?.mkdirs()
            app.assets.open("$ASSET/$rel").use { entrada ->
                salida.outputStream().use { entrada.copyTo(it, 64 * 1024) }
            }
            if (salida.length() != esperado.getValue(rel))
                throw IOException("Copia incompleta de $rel (${salida.length()} de ${esperado.getValue(rel)} bytes)")
        }
        borrar(destino)
        if (!tmp.renameTo(destino)) throw IOException("No se pudo renombrar $tmp")
        return destino
    }

    private fun copiaValida(dir: File, esperado: Map<String, Long>, uuid: String): Boolean {
        val u = File(dir, "uuid")
        if (!u.isFile || u.readText().trim() != uuid) return false
        return esperado.all { (rel, tam) -> File(dir, rel).let { it.isFile && it.length() == tam } }
    }

    /** Lista de archivos del modelo con su tamaño, tal como vienen en la APK. */
    private fun manifiesto(app: Context): Map<String, Long> {
        manifiesto?.let { return it }
        val r = mutableMapOf<String, Long>()
        fun recorrer(ruta: String, rel: String) {
            val hijos = app.assets.list(ruta).orEmpty()
            if (hijos.isEmpty()) {
                r[rel] = tamanoAsset(app, ruta)
            } else {
                hijos.forEach { recorrer("$ruta/$it", if (rel.isEmpty()) it else "$rel/$it") }
            }
        }
        val raiz = app.assets.list(ASSET).orEmpty()
        if ("uuid" !in raiz) throw ErrorModelo(app.getString(R.string.motivo_sin_modelo))
        raiz.forEach { recorrer("$ASSET/$it", it) }
        return r.also { manifiesto = it }
    }

    private fun tamanoAsset(app: Context, ruta: String): Long =
        try {
            app.assets.openFd(ruta).use { it.length }
        } catch (e: IOException) {
            // Archivo comprimido dentro de la APK: hay que contarlo leyendo.
            app.assets.open(ruta).use { entrada ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) { val n = entrada.read(buf); if (n < 0) break; total += n }
                total
            }
        }

    private fun leerAsset(app: Context, ruta: String) = app.assets.open(ruta).bufferedReader().use { it.readText() }

    private fun libre(dir: File): Long = try { StatFs(dir.absolutePath).availableBytes } catch (e: Exception) { 0L }

    private fun mb(bytes: Long) = bytes / (1024 * 1024)

    private fun borrar(f: File?) { if (f != null && f.exists()) f.deleteRecursively() }

    private fun esIncompatible(t: Throwable): Boolean {
        var x: Throwable? = t
        while (x != null) {
            if (x is LinkageError) return true // UnsatisfiedLinkError, NoClassDefFoundError…
            x = x.cause?.takeIf { it !== x }
        }
        return false
    }

    /** Para el diagnóstico: espacio libre en cada ubicación posible. */
    fun espacioLibre(context: Context): String =
        listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
            .joinToString(" · ") { "${it.absolutePath}: ${mb(libre(it))} MB" }
}
