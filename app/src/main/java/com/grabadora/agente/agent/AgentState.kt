package com.grabadora.agente.agent

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.service.quicksettings.TileService
import androidx.lifecycle.MutableLiveData

enum class Estado { APAGADO, PREPARANDO, ESCUCHANDO, PAUSADO, GRABANDO }

data class InfoAgente(val estado: Estado, val detalle: String = "")

/** Estado del agente compartido entre el servicio, la pantalla principal y el mosaico. */
object AgentState {
    val info = MutableLiveData(InfoAgente(Estado.APAGADO))

    val activo: Boolean get() = info.value?.estado?.let { it != Estado.APAGADO } ?: false

    fun publicar(context: Context, nuevo: InfoAgente) {
        // En el hilo principal se asigna al instante: así `activo` nunca devuelve un valor viejo
        // (con postValue había una ventana en la que se podía pulsar Grabar al arrancar el servicio).
        if (Looper.myLooper() == Looper.getMainLooper()) info.value = nuevo else info.postValue(nuevo)
        // Pide al sistema que refresque el mosaico de ajustes rápidos
        TileService.requestListeningState(context, ComponentName(context, AgentTileService::class.java))
    }
}
