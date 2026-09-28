package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppDrawerItem(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

class AppDrawerFragment : Fragment() {

    companion object {
        private var cachedApps: List<AppDrawerItem>? = null

        fun preload(context: Context) {
            if (cachedApps != null) return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val appContext = context.applicationContext
                    val pm = appContext.packageManager
                    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
                    } else {
                        pm.queryIntentActivities(mainIntent, 0)
                    }

                    val ownPackage = appContext.packageName
                    val appsList = resolved.mapNotNull { resolveInfo ->
                        val pkg = resolveInfo.activityInfo.packageName
                        if (pkg == ownPackage) null
                        else AppDrawerItem(resolveInfo.loadLabel(pm).toString(), pkg, resolveInfo.loadIcon(pm))
                    }.sortedBy { it.label.lowercase() }

                    cachedApps = appsList
                } catch (_: Exception) {}
            }
        }

        fun invalidateCache() {
            cachedApps = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_app_drawer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        val rvApps = view.findViewById<RecyclerView>(R.id.rv_apps)
        val progressBar = view.findViewById<ProgressBar>(R.id.progress_bar)

        toolbar.setNavigationOnClickListener { findNavController().popBackStack() }

        val screenWidthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        rvApps.layoutManager = GridLayoutManager(requireContext(), (screenWidthDp / 120).toInt().coerceAtLeast(3))

        val pm = requireContext().packageManager

        fun displayApps(apps: List<AppDrawerItem>) {
            progressBar.visibility = View.GONE
            rvApps.adapter = AppDrawerAdapter(apps) { app ->
                val intent = pm.getLaunchIntentForPackage(app.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent != null) startActivity(intent)
            }
        }

        // Jeśli lista jest w cache, wyświetlamy natychmiast bez ponownego skanowania systemu
        val cached = cachedApps
        if (cached != null) {
            progressBar.visibility = View.GONE
            displayApps(cached)
            return
        }

        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                pm.queryIntentActivities(mainIntent, 0)
            }

            val ownPackage = requireContext().packageName
            val appsList = resolved.mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg == ownPackage) null
                else AppDrawerItem(resolveInfo.loadLabel(pm).toString(), pkg, resolveInfo.loadIcon(pm))
            }.sortedBy { it.label.lowercase() }

            cachedApps = appsList

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                displayApps(appsList)
            }
        }
    }
}

class AppDrawerAdapter(
    private val items: List<AppDrawerItem>,
    private val onItemClick: (AppDrawerItem) -> Unit
) : RecyclerView.Adapter<AppDrawerAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_app_drawer, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_app_icon)
        private val tvName: TextView = itemView.findViewById(R.id.tv_app_name)

        fun bind(item: AppDrawerItem) {
            tvName.text = item.label
            ivIcon.setImageDrawable(item.icon)
            itemView.setOnClickListener { onItemClick(item) }
        }
    }
}
