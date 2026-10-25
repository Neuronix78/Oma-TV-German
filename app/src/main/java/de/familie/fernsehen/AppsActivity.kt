package de.familie.fernsehen

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Einfache Übersicht der anderen Apps auf der Box. */
class AppsActivity : Activity() {

    class Entry(val label: String, val icon: Drawable?, val action: () -> Unit)

    private lateinit var grid: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        hideSystemBars()

        grid = findViewById(R.id.appGrid)
        grid.layoutManager = GridLayoutManager(this, 4)
        grid.itemAnimator = null
        grid.adapter = EntryAdapter(buildEntries())
        focusFirst(0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun focusFirst(attempt: Int) {
        grid.post {
            val view = grid.findViewHolderForAdapterPosition(0)?.itemView
            if (view != null) view.requestFocus()
            else if (attempt < 10) grid.postDelayed({ focusFirst(attempt + 1) }, 50)
        }
    }

    private fun buildEntries(): List<Entry> {
        val pm = packageManager
        val settingsIcon = getDrawable(android.R.drawable.ic_menu_preferences)
        val entries = mutableListOf<Entry>()

        entries += Entry(getString(R.string.back_to_tv), getDrawable(R.mipmap.ic_launcher)) { finish() }

        // Installierte Apps (normale und Android-TV-Apps)
        val seen = mutableSetOf(packageName)
        val apps = mutableListOf<Entry>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (!seen.add(pkg)) continue
                val launch = (if (category == Intent.CATEGORY_LEANBACK_LAUNCHER)
                    pm.getLeanbackLaunchIntentForPackage(pkg) else null)
                    ?: pm.getLaunchIntentForPackage(pkg)
                    ?: continue
                apps += Entry(info.loadLabel(pm).toString(), info.loadIcon(pm)) { open(launch) }
            }
        }
        apps.sortBy { it.label.lowercase() }
        entries += apps

        // Ursprünglicher Startbildschirm der Box
        val homeQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        for (info in pm.queryIntentActivities(homeQuery, 0)) {
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) continue
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClassName(pkg, info.activityInfo.name)
            entries += Entry(
                getString(R.string.original_home, info.loadLabel(pm).toString()),
                info.loadIcon(pm)
            ) { open(intent) }
        }

        entries += Entry(getString(R.string.choose_home), settingsIcon) {
            if (!open(Intent(Settings.ACTION_HOME_SETTINGS), quiet = true)) {
                open(Intent(Settings.ACTION_SETTINGS))
            }
        }
        entries += Entry(getString(R.string.android_settings), settingsIcon) {
            open(Intent(Settings.ACTION_SETTINGS))
        }
        return entries
    }

    private fun open(intent: Intent, quiet: Boolean = false): Boolean {
        return try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            if (!quiet) Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show()
            false
        } catch (e: SecurityException) {
            if (!quiet) Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show()
            false
        }
    }

    private class EntryAdapter(private val items: List<Entry>) :
        RecyclerView.Adapter<EntryAdapter.Holder>() {

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appIcon)
            val label: TextView = view.findViewById(R.id.appLabel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            val holder = Holder(view)
            view.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) items[pos].action()
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.icon.setImageDrawable(items[position].icon)
            holder.label.text = items[position].label
        }

        override fun getItemCount() = items.size
    }
}
