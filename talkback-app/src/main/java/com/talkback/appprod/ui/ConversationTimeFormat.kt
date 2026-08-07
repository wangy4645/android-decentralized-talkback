package com.talkback.appprod.ui

import android.content.Context
import com.talkback.appprod.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object ConversationTimeFormat {

    fun formatListTime(context: Context, timestampMs: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = timestampMs }
        if (isSameDay(now, then)) {
            return timeFormat().format(Date(timestampMs))
        }
        val yesterday = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -1)
        }
        if (isSameDay(yesterday, then)) {
            return context.getString(R.string.conversation_time_yesterday)
        }
        val days = TimeUnit.MILLISECONDS.toDays(
            now.timeInMillisAtStartOfDay() - then.timeInMillisAtStartOfDay()
        )
        if (days in 2..6) {
            return "${days}d"
        }
        return dateFormat().format(Date(timestampMs))
    }

    fun formatMessageTime(timestampMs: Long): String =
        timeFormat().format(Date(timestampMs))

    private fun timeFormat() = SimpleDateFormat("HH:mm", Locale.getDefault())

    private fun dateFormat() = SimpleDateFormat("MM/dd", Locale.getDefault())

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    private fun Calendar.timeInMillisAtStartOfDay(): Long {
        val copy = clone() as Calendar
        copy.set(Calendar.HOUR_OF_DAY, 0)
        copy.set(Calendar.MINUTE, 0)
        copy.set(Calendar.SECOND, 0)
        copy.set(Calendar.MILLISECOND, 0)
        return copy.timeInMillis
    }
}
