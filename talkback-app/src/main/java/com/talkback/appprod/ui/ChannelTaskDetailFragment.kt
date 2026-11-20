package com.talkback.appprod.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.talkback.appprod.R
import com.talkback.core.channeltask.ChannelTaskAckStatus
import com.talkback.core.channeltask.ChannelTaskSnapshot
import com.talkback.core.model.ChannelTaskPhase
import kotlinx.coroutines.launch

class ChannelTaskDetailFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val uiContext = AppLocaleController.localizedContext(requireContext())
        return inflater.cloneInContext(uiContext).inflate(R.layout.fragment_channel_task_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.btnTaskBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btnTaskAck).setOnClickListener {
            currentTaskId()?.let { viewModel.ackChannelTask(it) }
        }
        view.findViewById<View>(R.id.btnTaskClose).setOnClickListener {
            currentTaskId()?.let { viewModel.closeChannelTask(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.channelTasks.collect { bind(view) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selectedChannelTaskId.collect { bind(view) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun currentTaskId(): String? =
        viewModel.selectedChannelTaskId.value ?: arguments?.getString(ARG_TASK_ID)

    private fun bind(view: View) {
        if (!isAdded) return
        val taskId = currentTaskId() ?: return
        val task = viewModel.channelTasks.value.firstOrNull { it.taskId == taskId } ?: return
        val local = viewModel.localModuleId()
        bindDetail(view, task, local)
    }

    private fun bindDetail(view: View, task: ChannelTaskSnapshot, local: String) {
        val phaseLabel = view.findViewById<TextView>(R.id.txtTaskPhase)
        phaseLabel.text = when (task.phase) {
            ChannelTaskPhase.OPEN -> getString(R.string.task_open_badge)
            ChannelTaskPhase.CLOSED -> getString(R.string.task_collection_closed)
            ChannelTaskPhase.EXPIRED -> getString(R.string.task_collection_expired)
        }
        phaseLabel.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (task.phase == ChannelTaskPhase.OPEN) R.color.tb_primary else R.color.tb_text_muted
            )
        )

        view.findViewById<TextView>(R.id.txtTaskTitle).text = task.title
        view.findViewById<TextView>(R.id.txtTaskIssuer).text =
            getString(R.string.task_issued_by, task.issuerModuleId)

        val deadline = view.findViewById<TextView>(R.id.txtTaskDeadline)
        if (task.phase == ChannelTaskPhase.OPEN) {
            val remain = (task.deadlineEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val mm = remain / 60_000L
            val ss = (remain % 60_000L) / 1000L
            deadline.text = getString(R.string.task_due_in, String.format("%02d:%02d", mm, ss))
            deadline.isVisible = true
        } else {
            deadline.isVisible = false
        }

        view.findViewById<TextView>(R.id.txtTaskBody).text = task.body
        view.findViewById<TextView>(R.id.txtTaskAckHeader).text =
            getString(R.string.task_confirmed_count, task.ackedCount, task.targetCount)

        val members = view.findViewById<LinearLayout>(R.id.containerTaskMembers)
        members.removeAllViews()
        task.members.forEach { member ->
            val row = layoutInflater.inflate(R.layout.item_channel_task_member, members, false)
            row.findViewById<TextView>(R.id.txtMemberId).text = member.moduleId
            val (label, color) = when (member.status) {
                ChannelTaskAckStatus.ACKED ->
                    getString(R.string.task_status_confirmed) to R.color.tb_success
                ChannelTaskAckStatus.PENDING ->
                    getString(R.string.task_status_pending) to R.color.tb_text_muted
                ChannelTaskAckStatus.UNACKED ->
                    getString(R.string.task_status_unacked) to R.color.tb_text_muted
            }
            row.findViewById<View>(R.id.viewMemberDot).setBackgroundColor(
                ContextCompat.getColor(requireContext(), color)
            )
            row.findViewById<TextView>(R.id.txtMemberStatus).apply {
                text = label
                setTextColor(ContextCompat.getColor(requireContext(), color))
            }
            members.addView(row)
        }

        val ack = view.findViewById<TextView>(R.id.btnTaskAck)
        val close = view.findViewById<TextView>(R.id.btnTaskClose)
        val closeHint = view.findViewById<View>(R.id.txtTaskCloseHint)
        val confirmHint = view.findViewById<View>(R.id.txtTaskConfirmHint)

        ack.isVisible = task.needsLocalAck
        close.isVisible = task.issuerModuleId == local && task.phase == ChannelTaskPhase.OPEN
        closeHint.isVisible = close.isVisible
        confirmHint.isVisible = task.phase == ChannelTaskPhase.OPEN
    }

    companion object {
        const val TAG = "channel_task_detail"
        private const val ARG_TASK_ID = "taskId"

        fun newInstance(taskId: String): ChannelTaskDetailFragment {
            return ChannelTaskDetailFragment().apply {
                arguments = Bundle().apply { putString(ARG_TASK_ID, taskId) }
            }
        }
    }
}