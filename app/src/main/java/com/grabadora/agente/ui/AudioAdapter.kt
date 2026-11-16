package com.grabadora.agente.ui

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.grabadora.agente.R
import com.grabadora.agente.data.Audio
import java.util.Date

/** Cap. 3 del libro: RecyclerView con Adapter y ViewHolder. */
class AudioAdapter(
    private val alReproducir: (Audio) -> Unit,
    private val alMenu: (Audio, View) -> Unit,
) : RecyclerView.Adapter<AudioAdapter.VH>() {

    var audios: List<Audio> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    /** id del audio que suena ahora (para cambiar el icono a "stop"). */
    var sonandoId: Long = -1
        set(v) { field = v; notifyDataSetChanged() }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val nombre: TextView = v.findViewById(R.id.nombre)
        val detalle: TextView = v.findViewById(R.id.detalle)
        val play: ImageButton = v.findViewById(R.id.play)
        val menu: ImageButton = v.findViewById(R.id.menu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_audio, parent, false))

    override fun getItemCount() = audios.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val a = audios[pos]
        val ctx = h.itemView.context
        h.nombre.text = a.nombre
        val fecha = DateFormat.getMediumDateFormat(ctx).format(Date(a.fecha)) + " " +
            DateFormat.getTimeFormat(ctx).format(Date(a.fecha))
        val origen = if (a.porVoz) " · " + ctx.getString(R.string.por_voz) else ""
        h.detalle.text = "${duracion(a.duracionMs)} · $fecha$origen"
        h.play.setImageResource(if (a.id == sonandoId) R.drawable.ic_stop else R.drawable.ic_play)
        h.play.setOnClickListener { alReproducir(a) }
        h.itemView.setOnClickListener { alReproducir(a) }
        h.menu.setOnClickListener { alMenu(a, it) }
    }

    companion object {
        fun duracion(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
