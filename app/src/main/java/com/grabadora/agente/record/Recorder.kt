package com.grabadora.agente.record

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * Cap. 6 del libro (ejercicio "Grabadora"): MediaRecorder con
 * setAudioSource → setOutputFormat → setAudioEncoder → setOutputFile → prepare → start.
 * Grabamos en AAC dentro de .m4a, que todas las apps de mensajería aceptan.
 */
class Recorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var inicio = 0L

    var archivo: File? = null
        private set

    val grabando: Boolean get() = recorder != null

    val transcurridoMs: Long get() = if (grabando) SystemClock.elapsedRealtime() - inicio else 0

    fun iniciar(salida: File) {
        // Un MediaRecorder a la vez: una segunda llamada dejaría el anterior sin liberar.
        check(!grabando) { "Ya hay una grabación en curso" }
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(salida.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            salida.delete()
            throw e
        }
        recorder = r
        archivo = salida
        inicio = SystemClock.elapsedRealtime()
    }

    /** Volumen máximo desde la última llamada (0..32767). Sirve para detectar silencio. */
    fun amplitud(): Int = try { recorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }

    /** Detiene y devuelve la duración en ms, o -1 si la grabación quedó vacía o falló. */
    fun detener(): Long {
        val r = recorder ?: return -1
        recorder = null
        val duracion = SystemClock.elapsedRealtime() - inicio
        return try {
            r.stop()
            duracion
        } catch (e: RuntimeException) {
            // stop() falla si no llegó a grabar nada: borramos el archivo vacío
            archivo?.delete()
            -1
        } finally {
            r.release()
        }
    }
}
