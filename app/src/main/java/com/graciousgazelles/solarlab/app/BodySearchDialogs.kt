package com.graciousgazelles.solarlab.app

import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.addTextChangedListener
import com.graciousgazelles.solarlab.app.databinding.DialogBodySearchBinding
import com.graciousgazelles.solarlab.core.model.BodyState

internal object BodySearchDialogs {

    fun show(
        activity: MainActivity,
        bodies: List<BodyState>,
        onPick: (BodyState) -> Unit,
        onDismiss: (() -> Unit)? = null,
    ) {
        val binding = DialogBodySearchBinding.inflate(activity.layoutInflater)
        val adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_list_item_1,
            mutableListOf<String>(),
        )
        val allEntries = bodies.map { body ->
            SearchEntry(
                body = body,
                label = buildLabel(activity, body),
            )
        }
        var visibleEntries = allEntries

        binding.listBodySearchResults.adapter = adapter
        lateinit var dialog: AlertDialog
        binding.listBodySearchResults.setOnItemClickListener { _, _, position, _ ->
            visibleEntries.getOrNull(position)?.let { entry ->
                onPick(entry.body)
                dialog.dismiss()
            }
        }
        binding.editBodySearchQuery.addTextChangedListener { editable ->
            val query = editable?.toString().orEmpty().trim().lowercase()
            visibleEntries = if (query.isEmpty()) {
                allEntries
            } else {
                allEntries.filter { entry ->
                    entry.matches(query)
                }
            }
            adapter.clear()
            adapter.addAll(visibleEntries.map { it.label })
            binding.textBodySearchSummary.text = summaryText(activity, visibleEntries.size, bodies.size)
        }

        adapter.addAll(allEntries.map { it.label })
        binding.textBodySearchSummary.text = summaryText(activity, allEntries.size, bodies.size)

        dialog = AlertDialog.Builder(activity)
            .setTitle("Find object")
            .setView(binding.root)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnDismissListener {
            onDismiss?.invoke()
        }

        dialog.show()
    }

    private fun buildLabel(activity: MainActivity, body: BodyState): String {
        val category = activity.prettyCategoryLabel(body.category)
        val role = activity.prettyRoleLabel(body.gravitationalRole)
        return "${body.name} - $category - $role"
    }

    private fun summaryText(activity: MainActivity, visibleCount: Int, totalCount: Int): String {
        return if (totalCount == 0) {
            activity.getString(R.string.search_summary_none)
        } else if (visibleCount == totalCount) {
            activity.getString(R.string.search_summary_all, totalCount)
        } else {
            activity.getString(R.string.search_summary_filtered, visibleCount, totalCount)
        }
    }

    private data class SearchEntry(
        val body: BodyState,
        val label: String,
    ) {
        fun matches(query: String): Boolean {
            return body.name.lowercase().contains(query) ||
                body.id.lowercase().contains(query) ||
                body.category.name.lowercase().contains(query) ||
                body.gravitationalRole.name.lowercase().contains(query)
        }
    }
}
