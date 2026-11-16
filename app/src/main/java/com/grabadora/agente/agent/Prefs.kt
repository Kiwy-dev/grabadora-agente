package com.grabadora.agente.agent

import android.content.Context
import androidx.preference.PreferenceManager

/** Lectura de los ajustes (cap. 3 / 9 del libro: preferencias). */
class Prefs(context: Context) {
    private val p = PreferenceManager.getDefaultSharedPreferences(context)

    val palabraClave: String
        get() = p.getString("palabra_clave", "oye grabadora")!!.trim().lowercase().ifEmpty { "oye grabadora" }

    /** Minutos sin oír la palabra clave antes de apagarse solo. 0 = sin límite. */
    val minutosInactividad: Int get() = p.getString("minutos_inactividad", "10")!!.toIntOrNull() ?: 10

    val segundosSilencio: Int get() = p.getString("segundos_silencio", "3")!!.toIntOrNull() ?: 3

    val segundosMaximo: Int get() = p.getString("segundos_maximo", "60")!!.toIntOrNull() ?: 60

    val carpetaVoz: String get() = p.getString("carpeta_voz", "Por voz")!!.trim().ifEmpty { "Por voz" }

    val soloCargando: Boolean get() = p.getBoolean("solo_cargando", false)

    val pausarPantallaApagada: Boolean get() = p.getBoolean("pausar_pantalla_apagada", true)

    val pararBateriaBaja: Boolean get() = p.getBoolean("parar_bateria_baja", true)

    var carpetaActual: String
        get() = p.getString("carpeta_actual", "General")!!
        set(v) = p.edit().putString("carpeta_actual", v).apply()
}
