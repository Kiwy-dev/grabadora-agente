package com.grabadora.agente

import android.app.Application
import com.grabadora.agente.data.AudioRepository

/**
 * Cap. 3 del libro: la clase Application como punto único (Singleton)
 * para compartir el repositorio entre actividades y el servicio.
 */
class AudioApp : Application() {
    lateinit var repo: AudioRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = AudioRepository(this)
    }
}
