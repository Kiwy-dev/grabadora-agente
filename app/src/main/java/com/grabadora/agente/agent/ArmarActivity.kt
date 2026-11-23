package com.grabadora.agente.agent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.grabadora.agente.R
import com.grabadora.agente.ui.MainActivity

/**
 * Actividad invisible que se abre desde el mosaico de ajustes rápidos.
 * Android 14 solo permite abrir el micrófono en primer plano mientras la app
 * es visible, así que nos "asomamos" un instante, arrancamos el servicio y cerramos.
 */
class ArmarActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permiso = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (permiso == PackageManager.PERMISSION_GRANTED) {
            // Android 12+ puede negar el arranque en primer plano (ForegroundServiceStartNotAllowedException).
            // Sin este try la app se cerraría; así solo avisamos.
            try {
                KeywordService.iniciar(this)
            } catch (e: Exception) {
                Log.e("ArmarActivity", "No se pudo iniciar el agente", e)
                Toast.makeText(this, R.string.motivo_no_permitido, Toast.LENGTH_LONG).show()
            }
        } else {
            // Sin permiso de micrófono: abrimos la app para que lo pida
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
