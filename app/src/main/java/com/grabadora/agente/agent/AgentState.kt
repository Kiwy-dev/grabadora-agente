package com.grabadora.agente.agent

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.lifecycle.MutableLiveData

enum class Estado { APAGADO, PREPARANDO, ESCUCHANDO, PAUSADO, GRABANDO }

data class InfoAgente(val estado: Estado, val detalle: String = "")

/** Estado del agente compartido entre el servicio, la pantalla principal y el mosaico. */
object AgentState {
    val info = MutableLiveData(InfoAgente(Estado.APAGADO))

    val activo: Boolean get() = info.value?.estado?.let { it != Estado.APAGADO } ?: false

    fun publicar(context: Context, nuevo: InfoAgente) {
        info.postValue(nuevo)
        // Pide al sistema que refresque el mosaico de ajustes rápidos
        TileService.requestListeningState(context, ComponentName(context, AgentTileService::class.java))
    }
}
