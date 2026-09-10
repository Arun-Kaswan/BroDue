package com.abk.brodue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ArchiveBottomSheet : BaseSheet() {

    private var onOpenPerson: ((Person) -> Unit)? = null
    private var connectivityListener: ((Boolean) -> Unit)? = null
    private val archivedList = mutableListOf<Person>()
    private var refreshAction: (() -> Unit)? = null
    private val adapter = ArchivedPersonAdapter(
        onItemClick = { person ->
            dismiss()
            onOpenPerson?.invoke(person)
        },
        onUnarchiveClick = { person ->
            // Local-first
            LocalStore.updatePersonFields(
                requireContext(), person.id, mapOf("archived" to false)
            )
            Toast.makeText(requireContext(), R.string.unarchived_toast, Toast.LENGTH_SHORT).show()
            (requireActivity() as? MainActivity)?.refreshLocalData()
            refreshAction?.invoke()
        },
        onDeleteClick = { person ->
            DeletePersonSheet.newInstance(person.id, person.name)
                .show(parentFragmentManager, DeletePersonSheet.TAG)
        }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_archive, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.rvArchived)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        val empty = view.findViewById<TextView>(R.id.tvArchivedEmpty)

        fun refreshArchived() {
            // Local-first: read archived people from local storage
            archivedList.clear()
            val peopleObj = LocalStore.people(requireContext())
            val keys = peopleObj.keys()
            while (keys.hasNext()) {
                val pid = keys.next()
                val o = peopleObj.optJSONObject(pid) ?: continue
                if (!o.optBoolean("archived", false)) continue
                archivedList.add(
                    Person(
                        pid,
                        o.optString("name", ""),
                        o.optString("mobile", ""),
                        0,
                        o.optLong("createdAt", 0L)
                    )
                )
            }
            archivedList.sortBy { it.name.lowercase() }
            adapter.submitList(archivedList.toList())
            empty.isVisible = archivedList.isEmpty()
            rv.isVisible = archivedList.isNotEmpty()
        }
        refreshAction = { refreshArchived() }
        parentFragmentManager.setFragmentResultListener(DeletePersonSheet.REQ_DELETED, this) { _, _ ->
            refreshArchived()
        }
        refreshArchived()

        if (!ConnectivityMonitor.online) adapter.setGreyed(true)
        connectivityListener = { online -> adapter.setGreyed(!online) }
        ConnectivityMonitor.addListener(connectivityListener!!)
    }

    override fun onDestroyView() {
        connectivityListener?.let { ConnectivityMonitor.removeListener(it) }
        connectivityListener = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "ArchiveBottomSheet"

        fun newInstance(onOpenPerson: (Person) -> Unit): ArchiveBottomSheet =
            ArchiveBottomSheet().apply {
                this.onOpenPerson = onOpenPerson
            }
    }
}