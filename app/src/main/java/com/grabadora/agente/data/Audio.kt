package com.grabadora.agente.data

import java.io.File

data class Audio(
    val id: Long,
    val nombre: String,
    val ruta: String,
    val carpeta: String,
    val duracionMs: Long,
    val fecha: Long,
    val porVoz: Boolean,
) {
    val archivo: File get() = File(ruta)
}
