package com.talkback.appprod.ui

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.talkback.appprod.R

/** 「+」展开：图片 / 文件（微信式宫格）。 */
object ConversationMorePanelBinder {
    var onPanelVisibilityChanged: (() -> Unit)? = null

    fun bind(root: View, actions: ConversationAttachmentActions) {
        val panel = root.findViewById<View>(R.id.conversationMorePanel)
        val btnPlus = root.findViewById<ImageView>(R.id.btnConversationAttach)

        bindTile(
            root.findViewById(R.id.conversationMorePhoto),
            R.drawable.ic_message_attach_photo,
            R.string.message_attach_pick_image
        ) {
            setPanelVisible(root, false)
            actions.pickImage()
        }

        bindTile(
            root.findViewById(R.id.conversationMoreFile),
            R.drawable.ic_message_attach_folder,
            R.string.message_attach_pick_file
        ) {
            setPanelVisible(root, false)
            actions.pickFile()
        }

        bindTile(
            root.findViewById(R.id.conversationMoreLocation),
            R.drawable.ic_message_attach_location,
            R.string.message_attach_pick_location
        ) {
            setPanelVisible(root, false)
            actions.shareLocation()
        }

        btnPlus.setOnClickListener {
            setPanelVisible(root, !panel.isVisible)
        }
    }

    fun isPanelVisible(root: View): Boolean =
        root.findViewById<View>(R.id.conversationMorePanel).isVisible

    /** @return true if back was consumed to collapse the attach panel */
    fun handleBack(root: View): Boolean {
        if (!isPanelVisible(root)) return false
        setPanelVisible(root, false)
        return true
    }

    fun collapsePanel(root: View) {
        setPanelVisible(root, false)
    }

    private fun setPanelVisible(root: View, visible: Boolean) {
        val panel = root.findViewById<View>(R.id.conversationMorePanel)
        val btnPlus = root.findViewById<ImageView>(R.id.btnConversationAttach)
        val ctx = root.context
        panel.isVisible = visible
        btnPlus.setImageResource(
            if (visible) R.drawable.ic_message_attach_plus_close else R.drawable.ic_message_attach_plus
        )
        btnPlus.contentDescription = ctx.getString(
            if (visible) R.string.message_attach_more_close_cd else R.string.message_attach_cd
        )
        onPanelVisibilityChanged?.invoke()
    }

    private fun bindTile(
        tileRoot: View,
        iconRes: Int,
        labelRes: Int,
        onClick: () -> Unit
    ) {
        tileRoot.findViewById<ImageView>(R.id.imgMoreActionIcon).setImageResource(iconRes)
        tileRoot.findViewById<TextView>(R.id.txtMoreActionLabel).setText(labelRes)
        tileRoot.setOnClickListener { onClick() }
    }
}
