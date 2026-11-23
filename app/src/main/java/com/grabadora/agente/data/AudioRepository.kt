package com.grabadora.agente.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guarda los audios en el almacenamiento externo privado de la app (cap. 9:
 * getExternalFilesDir) y sus datos en SQLite. Cada carpeta es un directorio real.
 *
 *   .../Android/data/com.grabadora.agente/files/Music/Grabaciones/<carpeta>/<archivo>.m4a
 */
class AudioRepository(context: Context) {

    companion object {
        const val CARPETA_GENERAL = "General"
        private const val LARGO_MAXIMO = 100
        private val NOMBRE_INVALIDO = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

        /**
         * Nombre usable como carpeta o archivo: sin separadores de ruta ni caracteres
         * reservados, sin empezar por punto (evita "..", ocultos) y con largo razonable.
         * Es estático para que Prefs valide la carpeta de voz con la misma regla.
         */
        fun nombreValido(nombre: String) = nombre.isNotBlank() &&
            nombre.length <= LARGO_MAXIMO &&
            !nombre.startsWith(".") &&
            !NOMBRE_INVALIDO.containsMatchIn(nombre)
    }

    private val db = AudioDbHelper(context)
    private val base: File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir, "Grabaciones")

    /** Cambia cada vez que se modifica algo; la UI la observa para refrescarse. */
    val cambios = MutableLiveData(0)
    private val contador = AtomicInteger()

    // Se llama desde la UI y desde el servicio: el contador atómico no pierde avisos.
    private fun avisar() = cambios.postValue(contador.incrementAndGet())

    fun dirCarpeta(nombre: String): File = File(base, nombre).apply { mkdirs() }

    // ---------- Carpetas ----------

    fun carpetas(): List<String> {
        dirCarpeta(CARPETA_GENERAL)
        val nombres = base.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        return nombres.sortedWith(compareBy<String> { it != CARPETA_GENERAL }.thenBy { it.lowercase() })
    }

    fun crearCarpeta(nombre: String): Boolean {
        val limpio = nombre.trim()
        if (!nombreValido(limpio)) return false
        val dir = File(base, limpio)
        if (dir.exists()) return false
        val ok = dir.mkdirs()
        if (ok) avisar()
        return ok
    }

    /** Solo se borran carpetas vacías (y nunca la General) para no perder audios por accidente. */
    fun borrarCarpeta(nombre: String): Boolean {
        if (nombre == CARPETA_GENERAL || listar(nombre).isNotEmpty()) return false
        val ok = File(base, nombre).deleteRecursively()
        if (ok) avisar()
        return ok
    }

    // ---------- Audios ----------

    /** Archivo nuevo con fecha y hora; si ya existe uno en el mismo segundo, agrega " (2)". */
    fun nuevoArchivo(carpeta: String): File {
        val sello = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return archivoLibre(dirCarpeta(carpeta), "Grabacion_$sello.m4a")
    }

    fun agregar(archivo: File, carpeta: String, duracionMs: Long, porVoz: Boolean): Long {
        val v = ContentValues().apply {
            put("nombre", archivo.nameWithoutExtension)
            put("ruta", archivo.absolutePath)
            put("carpeta", carpeta)
            put("duracion", duracionMs)
            put("fecha", System.currentTimeMillis())
            put("por_voz", if (porVoz) 1 else 0)
        }
        val id = db.writableDatabase.insert("audios", null, v)
        avisar()
        return id
    }

    fun listar(carpeta: String): List<Audio> {
        val lista = mutableListOf<Audio>()
        val perdidos = mutableListOf<Long>()
        db.readableDatabase.query(
            "audios", null, "carpeta = ?", arrayOf(carpeta), null, null, "fecha DESC"
        ).use { c ->
            while (c.moveToNext()) {
                val a = Audio(
                    id = c.getLong(c.getColumnIndexOrThrow("_id")),
                    nombre = c.getString(c.getColumnIndexOrThrow("nombre")),
                    ruta = c.getString(c.getColumnIndexOrThrow("ruta")),
                    carpeta = c.getString(c.getColumnIndexOrThrow("carpeta")),
                    duracionMs = c.getLong(c.getColumnIndexOrThrow("duracion")),
                    fecha = c.getLong(c.getColumnIndexOrThrow("fecha")),
                    porVoz = c.getInt(c.getColumnIndexOrThrow("por_voz")) == 1,
                )
                if (a.archivo.exists()) lista += a else perdidos += a.id
            }
        }
        // Si el archivo se borró por fuera de la app, quitamos también su registro (en una transacción).
        if (perdidos.isNotEmpty()) {
            val w = db.writableDatabase
            w.beginTransaction()
            try {
                perdidos.forEach { w.delete("audios", "_id = ?", arrayOf(it.toString())) }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        }
        return lista
    }

    fun borrar(audio: Audio): Boolean {
        // Si el archivo existe y no se pudo borrar, conservamos el registro para no dejarlo invisible.
        if (audio.archivo.exists() && !audio.archivo.delete()) return false
        val n = db.writableDatabase.delete("audios", "_id = ?", arrayOf(audio.id.toString()))
        avisar()
        return n > 0
    }

    fun mover(audio: Audio, carpetaDestino: String): Boolean {
        if (carpetaDestino == audio.carpeta) return true
        val destino = archivoLibre(dirCarpeta(carpetaDestino), audio.archivo.name)
        // renameTo falla entre sistemas de archivos distintos; entonces copiamos y borramos.
        // Si la copia o el borrado del original fallan, quitamos la copia: nunca dos ejemplares.
        val movido = audio.archivo.renameTo(destino) || runCatching {
            audio.archivo.copyTo(destino)
            check(audio.archivo.delete()) { "No se pudo borrar el original" }
        }.onFailure { destino.delete() }.isSuccess
        if (!movido) return false
        val v = ContentValues().apply {
            put("ruta", destino.absolutePath)
            put("carpeta", carpetaDestino)
        }
        db.writableDatabase.update("audios", v, "_id = ?", arrayOf(audio.id.toString()))
        avisar()
        return true
    }

    fun renombrar(audio: Audio, nuevoNombre: String): Boolean {
        val limpio = nuevoNombre.trim()
        if (!nombreValido(limpio)) return false
        val destino = archivoLibre(audio.archivo.parentFile!!, "$limpio.${audio.archivo.extension}")
        if (!audio.archivo.renameTo(destino)) return false
        val v = ContentValues().apply {
            put("nombre", destino.nameWithoutExtension)
            put("ruta", destino.absolutePath)
        }
        db.writableDatabase.update("audios", v, "_id = ?", arrayOf(audio.id.toString()))
        avisar()
        return true
    }

    /** Evita sobrescribir: si "x.m4a" existe, usa "x (2).m4a", "x (3).m4a"... */
    private fun archivoLibre(dir: File, nombre: String): File {
        var f = File(dir, nombre)
        val raiz = f.nameWithoutExtension
        val ext = f.extension
        var i = 2
        while (f.exists()) f = File(dir, "$raiz ($i).$ext").also { i++ }
        return f
    }
}
