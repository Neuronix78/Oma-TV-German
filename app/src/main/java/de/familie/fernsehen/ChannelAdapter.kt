package de.familie.fernsehen

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(private val onPick: (Int) -> Unit) :
    RecyclerView.Adapter<ChannelAdapter.Holder>() {

    private var items: List<Channel> = emptyList()
    var currentIndex = -1

    fun submit(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.number)
        val logo: ImageView = view.findViewById(R.id.logo)
        val name: TextView = view.findViewById(R.id.name)
        val playing: View = view.findViewById(R.id.playing)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        val holder = Holder(view)
        view.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onPick(pos)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = items[position]
        holder.number.text = (position + 1).toString()
        holder.name.text = channel.name
        if (channel.logo.isNotEmpty()) holder.logo.load(channel.logo) else holder.logo.setImageDrawable(null)
        holder.playing.visibility = if (position == currentIndex) View.VISIBLE else View.GONE
    }

    override fun getItemCount() = items.size
}
