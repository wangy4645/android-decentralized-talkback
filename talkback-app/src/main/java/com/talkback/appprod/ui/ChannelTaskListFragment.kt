package com.talkback.appprod.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.talkback.appprod.R
import com.talkback.core.channeltask.ChannelTaskSnapshot
import com.talkback.core.model.ChannelTaskPhase
import kotlinx.coroutines.launch

class ChannelTaskListFragment : Fragment() {
    private val viewModel: TalkViewModel by activityViewModels { TalkViewModelFactory(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val uiContext = AppLocaleController.localizedContext(requireContext())
        return inflater.cloneInContext(uiContext).inflate(R.layout.fragment_channel_task_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.btnTaskNew).setOnClickListener { showIssueDialog() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.channelTasks.collect { bind(view) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    view.findViewById<TextView>(R.id.txtTaskListChannel).text = state.channelTitle
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun bind(view: View) {
        if (!isAdded) return
        val tasks = viewModel.channelTasks.value
        val open = tasks.filter { it.phase == ChannelTaskPhase.OPEN }
        val history = tasks.filter { it.phase != ChannelTaskPhase.OPEN }

        view.findViewById<View>(R.id.txtTaskListEmpty).isVisible = tasks.isEmpty()
        view.findViewById<View>(R.id.txtSectionOpen).isVisible = open.isNotEmpty()
        view.findViewById<View>(R.id.txtSectionHistory).isVisible = history.isNotEmpty()

        bindSection(view.findViewById(R.id.containerOpenTasks), open, openRowBackground = true)
        bindSection(view.findViewById(R.id.containerHistoryTasks), history, openRowBackground = false)
    }

    private fun bindSection(
        container: LinearLayout,
        tasks: List<ChannelTaskSnapshot>,
        openRowBackground: Boolean
    ) {
        container.removeAllViews()
        tasks.forEach { task ->
            val row = layoutInflater.inflate(R.layout.item_channel_task_row, container, false)
            row.findViewById<TextView>(R.id.txtTaskRowTitle).text = task.title
            row.findViewById<TextView>(R.id.txtTaskRowMeta).text = rowMeta(task)
            if (openRowBackground) {
                row.setBackgroundResource(R.drawable.bg_task_row_open)
            }
            row.setOnClickListener {
                (activity as? MainActivity)?.showChannelTaskDetail(task.taskId)
            }
            container.addView(row)
        }
    }

    private fun rowMeta(task: ChannelTaskSnapshot): String = when (task.phase) {
        ChannelTaskPhase.OPEN -> {
            val remain = (task.deadlineEpochMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val mm = remain / 60_000L
            val ss = (remain % 60_000L) / 1000L
            getString(
                R.string.task_row_open_due,
                String.format("%02d:%02d", mm, ss),
                task.ackedCount,
                task.targetCount
            )
        }
        ChannelTaskPhase.CLOSED ->
            getString(R.string.task_row_closed, task.ackedCount, task.targetCount)
        ChannelTaskPhase.EXPIRED ->
            getString(R.string.task_row_expired)
    }

    private fun showIssueDialog() {
        val title = EditText(requireContext()).apply { hint = getString(R.string.task_title_hint) }
        val body = EditText(requireContext()).apply { hint = getString(R.string.task_body_hint) }
        val box = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(title)
            addView(body)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.task_new)
            .setView(box)
            .setPositiveButton(R.string.task_send) { _, _ ->
                when (val err = viewModel.issueChannelTask(title.text.toString(), body.text.toString())) {
                    null -> Unit
                    "TASK_TITLE_REQUIRED" ->
                        Toast.makeText(requireContext(), R.string.task_title_required, Toast.LENGTH_SHORT).show()
                    "NO_MEMBERS" ->
                        Toast.makeText(requireContext(), R.string.task_no_members, Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(requireContext(), err, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    companion object {
        const val TAG = "channel_task_list"
        const val BACK_STACK_NAME = "channel_task_list"
    }
}