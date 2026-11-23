package com.grabadora.agente.agent

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.grabadora.agente.R

/** Mosaico en los ajustes rápidos para activar o apagar la escucha sin abrir la app. */
class AgentTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        actualizar()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (AgentState.activo) {
            KeywordService.detener(this)
            return
        }
        // Con el teléfono bloqueado, primero se pide desbloquear; si no, la actividad no se ve
        // y Android no deja abrir el micrófono en primer plano.
        if (isLocked) unlockAndRun { abrirArmar() } else abrirArmar()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun abrirArmar() {
        val i = Intent(this, ArmarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    private fun actualizar() {
        val tile = qsTile ?: return
        val info = AgentState.info.value ?: InfoAgente(Estado.APAGADO)
        tile.state = if (info.estado == Estado.APAGADO) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (info.estado) {
                Estado.APAGADO -> getString(R.string.estado_apagado)
                Estado.PREPARANDO -> getString(R.string.tile_preparando)
                Estado.ESCUCHANDO -> getString(R.string.tile_escuchando)
                Estado.PAUSADO -> getString(R.string.tile_pausado)
                Estado.GRABANDO -> getString(R.string.tile_grabando)
            }
        }
        tile.updateTile()
    }
}
