package com.appatalks.lcars_translator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class LanguageSelectorSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_IS_SOURCE = "is_source"

        fun newInstance(isSource: Boolean, onSelected: (LanguageEntry) -> Unit): LanguageSelectorSheet {
            return LanguageSelectorSheet().apply {
                arguments = Bundle().apply { putBoolean(ARG_IS_SOURCE, isSource) }
                this.onSelected = onSelected
            }
        }
    }

    var onSelected: ((LanguageEntry) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_language, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val isSource = arguments?.getBoolean(ARG_IS_SOURCE, true) ?: true
        val languages = if (isSource) LanguageData.sourceLanguages else LanguageData.targetLanguages

        view.findViewById<TextView>(R.id.tvSheetTitle).text =
            if (isSource) "Select Source Language" else "Select Target Language"

        val rv = view.findViewById<RecyclerView>(R.id.rvLanguages)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = LanguageAdapter(languages) { entry ->
            onSelected?.invoke(entry)
            dismiss()
        }
    }
}

// ── Language RecyclerView Adapter ──────────────────────────────────────────

private class LanguageAdapter(
    private val items: List<LanguageEntry>,
    private val onClick: (LanguageEntry) -> Unit
) : RecyclerView.Adapter<LanguageAdapter.VH>() {

    // LCARS accent colours cycling through entries
    private val accentColors = intArrayOf(
        0xFFFF7700.toInt(),
        0xFF9999FF.toInt(),
        0xFFCC44CC.toInt(),
        0xFFFF9966.toInt(),
        0xFFFFCC99.toInt(),
    )

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val colorBar: View = view.findViewById(R.id.viewLangColor)
        val tvName: TextView = view.findViewById(R.id.tvLangName)
        val tvCode: TextView = view.findViewById(R.id.tvLangCode)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_language, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.tvName.text = entry.displayName
        holder.tvCode.text = entry.bcp47Tag.uppercase()
        holder.colorBar.setBackgroundColor(accentColors[position % accentColors.size])
        holder.itemView.setOnClickListener { onClick(entry) }
    }
}


