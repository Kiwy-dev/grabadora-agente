package com.grabadora.agente.ui

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.grabadora.agente.R
import com.grabadora.agente.data.Audio
import java.util.Date

/**
 * Cap. 3 del libro: RecyclerView con Adapter y ViewHolder.
 * Usa ListAdapter + DiffUtil: al cambiar la lista solo se redibujan las filas que cambiaron
 * (antes notifyDataSetChanged redibujaba todo en cada grabación, borrado o reproducción).
 */
class AudioAdapter(
    private val alReproducir: (Audio) -> Unit,
    private val alMenu: (Audio, View) -> Unit,
) : ListAdapter<Audio, AudioAdapter.VH>(DIFERENCIAS) {

    /** id del audio que suena ahora (para cambiar el icono a "stop"). Solo refresca las 2 filas afectadas. */
    var sonandoId: Long = -1
        set(v) {
            val anterior = field
            field = v
            if (anterior == v) return
            currentList.forEachIndexed { i, a -> if (a.id == anterior || a.id == v) notifyItemChanged(i) }
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val nombre: TextView = v.findViewById(R.id.nombre)
        val detalle: TextView = v.findViewById(R.id.detalle)
        val play: ImageButton = v.findViewById(R.id.play)
        val menu: ImageButton = v.findViewById(R.id.menu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_audio, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val a = getItem(pos)
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
        private val DIFERENCIAS = object : DiffUtil.ItemCallback<Audio>() {
            override fun areItemsTheSame(a: Audio, b: Audio) = a.id == b.id
            override fun areContentsTheSame(a: Audio, b: Audio) = a == b
        }

        fun duracion(ms: Long): String {
            val s = ms / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
