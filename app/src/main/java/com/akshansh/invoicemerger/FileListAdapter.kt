package com.akshansh.invoicemerger

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FileListAdapter(
    private val files: MutableList<Pair<Uri, String>>,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<FileListAdapter.FileViewHolder>() {

    class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.fileNameText)
        val removeText: TextView = view.findViewById(R.id.removeText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.nameText.text = files[position].second
        holder.removeText.setOnClickListener { onRemove(position) }
    }

    override fun getItemCount(): Int = files.size
}
