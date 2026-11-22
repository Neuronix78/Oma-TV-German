package de.familie.fernsehen

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

class ChannelHolder(view: View) : RecyclerView.ViewHolder(view) {
    val number: TextView = view.findViewById(R.id.number)
    val logo: ImageView = view.findViewById(R.id.logo)
    val name: TextView = view.findViewById(R.id.name)
    val marker: TextView = view.findViewById(R.id.playing)

    fun showLogo(url: String) {
        if (url.isNotEmpty()) logo.load(url) else {
            logo.dispose()
            logo.setImageDrawable(null)
        }
    }
}

class ActionHolder(view: View) : RecyclerView.ViewHolder(view) {
    val label: TextView = view.findViewById(R.id.actionLabel)
}

/** Liste "Meine Sender", im Bearbeiten-Modus mit Zusatzzeilen. */
class FavAdapter(private val listener: Listener) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onFavClick(index: Int)
        fun onAction(action: Int)
    }

    companion object {
        const val ACTION_ALL = 1
        const val ACTION_ADD = 2
        const val ACTION_RESET = 3
        private const val TYPE_CHANNEL = 0
        private const val TYPE_ACTION = 1
    }

    var favorites: List<Channel> = emptyList()
        private set
    var playingIndex = -1
    var editMode = false
    var movingIndex = -1
    var missingKeys: Set<String> = emptySet()

    fun submit(list: List<Channel>) {
        favorites = list
        notifyDataSetChanged()
    }

    fun actionAt(position: Int): Int = when {
        !editMode -> ACTION_ALL
        position == favorites.size -> ACTION_ADD
        else -> ACTION_RESET
    }

    override fun getItemCount() = favorites.size + if (editMode) 2 else 1

    override fun getItemViewType(position: Int) =
        if (position < favorites.size) TYPE_CHANNEL else TYPE_ACTION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_CHANNEL) {
            val holder = ChannelHolder(inflater.inflate(R.layout.item_channel, parent, false))
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) listener.onFavClick(pos)
            }
            holder
        } else {
            val holder = ActionHolder(inflater.inflate(R.layout.item_action, parent, false))
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) listener.onAction(actionAt(pos))
            }
            holder
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ctx = holder.itemView.context
        if (holder is ActionHolder) {
            holder.label.setText(
                when (actionAt(position)) {
                    ACTION_ALL -> R.string.action_all_channels
                    ACTION_ADD -> R.string.action_add_channels
                    else -> R.string.action_reset
                }
            )
            return
        }
        holder as ChannelHolder
        val ch = favorites[position]
        holder.number.visibility = View.VISIBLE
        holder.number.text = (position + 1).toString()
        holder.showLogo(ch.logo)
        holder.name.text = if (editMode && ch.key in missingKeys) ctx.getString(R.string.name_missing, ch.name) else ch.name
        holder.itemView.alpha = if (ch.key in missingKeys) 0.6f else 1f
        holder.itemView.setBackgroundResource(if (position == movingIndex) R.drawable.bg_moving else R.drawable.bg_focus)
        when {
            position == movingIndex -> {
                holder.marker.text = "⇅"
                holder.marker.visibility = View.VISIBLE
            }
            position == playingIndex && !editMode -> {
                holder.marker.text = "▶"
                holder.marker.visibility = View.VISIBLE
            }
            else -> holder.marker.visibility = View.GONE
        }
    }
}

/** Gruppenliste in "Alle Sender" (linke Spalte). */
class GroupAdapter(
    private val onFocus: (Int) -> Unit,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<GroupAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.countryName)
        val count: TextView = view.findViewById(R.id.countryCount)
    }

    var groups: List<ChannelGroup> = emptyList()
        private set
    var selected = 0

    fun submit(list: List<ChannelGroup>) {
        groups = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_country, parent, false)
        val holder = Holder(view)
        view.setOnFocusChangeListener { _, hasFocus ->
            val pos = holder.bindingAdapterPosition
            if (hasFocus && pos != RecyclerView.NO_POSITION) onFocus(pos)
        }
        view.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val g = groups[position]
        holder.name.text = g.name
        holder.count.text = g.channels.size.toString()
        holder.itemView.isActivated = position == selected
    }

    override fun getItemCount() = groups.size
}

/** Senderliste eines Landes in "Alle Sender". */
class AllAdapter(
    private val health: HealthStore,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<ChannelHolder>() {

    var channels: List<Channel> = emptyList()
        private set
    var favoriteKeys: Set<String> = emptySet()
    private var positions: Map<String, Int> = emptyMap()

    fun submit(list: List<Channel>, favKeys: Set<String>) {
        channels = list
        favoriteKeys = favKeys
        positions = list.withIndex().associate { it.value.key to it.index }
        notifyDataSetChanged()
    }

    fun refreshKey(key: String) {
        positions[key]?.let { notifyItemChanged(it) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelHolder {
        val holder = ChannelHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(pos)
        }
        return holder
    }

    override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
        val ch = channels[position]
        val ctx = holder.itemView.context
        holder.number.visibility = View.GONE
        holder.showLogo(ch.logo)
        val failed = health.state(ch.key) == HealthStore.State.FAIL
        holder.name.text = if (failed) ctx.getString(R.string.name_unreachable, ch.name) else ch.name
        holder.itemView.alpha = if (failed) 0.45f else 1f
        holder.itemView.setBackgroundResource(R.drawable.bg_focus)
        if (ch.key in favoriteKeys) {
            holder.marker.text = "✓"
            holder.marker.visibility = View.VISIBLE
        } else {
            holder.marker.visibility = View.GONE
        }
    }

    override fun getItemCount() = channels.size
}

/** Kleine Hilfe für die Fernbedienung: kurz drücken vs. 3 Sekunden halten. */
class KeyHold(
    private val handler: android.os.Handler,
    private val holdMs: Long,
    private val onShort: () -> Unit,
    private val onLong: () -> Unit,
    private val longEnabled: () -> Boolean
) {
    // Billige Infrarot-Fernbedienungen senden beim Halten manchmal kurze
    // Loslass-Signale; deshalb gilt die Taste erst nach 400 ms als losgelassen.
    private var tracking = false
    private var longFired = false

    private val longPress = Runnable {
        longFired = true
        onLong()
    }

    private val released = Runnable {
        tracking = false
        handler.removeCallbacks(longPress)
        val wasLong = longFired
        longFired = false
        if (!wasLong) onShort()
    }

    fun handle(event: android.view.KeyEvent): Boolean {
        when (event.action) {
            android.view.KeyEvent.ACTION_DOWN -> {
                handler.removeCallbacks(released)
                if (!tracking) {
                    tracking = true
                    longFired = false
                    if (longEnabled()) handler.postDelayed(longPress, holdMs)
                }
            }
            android.view.KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(released)
                handler.postDelayed(released, 400)
            }
        }
        return true
    }

    fun cancel() {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(released)
        tracking = false
        longFired = false
    }
}
