package com.talkback.appprod.ui

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.talkback.appprod.R

/** Recent documents over the conversation; system browse runs from host after sheet closes. */
class AttachFileBottomSheet : BottomSheetDialogFragment() {

    interface Callback {
        fun onPicked(uri: Uri)
        fun onDismissed()
        /** Close sheet and open system document UI from the host fragment (sheet must not stay underneath). */
        fun onBrowseRequested()
    }

    var callback: Callback? = null
    private var contentRoot: View? = null
    private var suppressDismissCallback = false

    private val requestReadStorage = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val root = contentRoot ?: return@registerForActivityResult
        if (granted) bindList(root) else showEmpty(root)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_attach_file, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        contentRoot = view
        view.findViewById<ImageView>(R.id.btnAttachFileSheetClose).setOnClickListener {
            dismiss()
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAttachFileSheetChoose)
            .setOnClickListener { launchDocumentPicker() }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            requestReadStorage.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            bindList(view)
        }
    }

    override fun onDestroyView() {
        contentRoot = null
        super.onDestroyView()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        if (!suppressDismissCallback) {
            callback?.onDismissed()
        }
        suppressDismissCallback = false
        super.onDismiss(dialog)
    }

    private fun bindList(root: View) {
        val list = root.findViewById<RecyclerView>(R.id.listAttachFileSheet)
        val empty = root.findViewById<TextView>(R.id.txtAttachFileSheetEmpty)
        val items = RecentAttachFileIndex.load(requireContext())
        if (items.isEmpty()) {
            showEmpty(root)
            return
        }
        empty.isVisible = false
        list.isVisible = true
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = AttachFileCandidateAdapter(items) { candidate ->
            suppressDismissCallback = true
            callback?.onPicked(candidate.uri)
            dismiss()
        }
    }

    private fun showEmpty(root: View) {
        root.findViewById<RecyclerView>(R.id.listAttachFileSheet).isVisible = false
        root.findViewById<TextView>(R.id.txtAttachFileSheetEmpty).isVisible = true
    }

    private fun launchDocumentPicker() {
        suppressDismissCallback = true
        callback?.onBrowseRequested()
        dismiss()
    }

    companion object {
        const val TAG = "attach_file_sheet"

        fun show(fragment: androidx.fragment.app.Fragment, callback: Callback) {
            AttachFileBottomSheet().apply {
                this.callback = callback
            }.show(fragment.parentFragmentManager, TAG)
        }
    }
}
