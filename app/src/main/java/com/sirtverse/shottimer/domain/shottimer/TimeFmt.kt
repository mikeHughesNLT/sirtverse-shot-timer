package com.sirtverse.shottimer.domain.shottimer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Display formatting helpers — keep number→string rules in one place. */
object TimeFmt {

    /** 1234.5 ms → "1.23 s". null → "—". */
    fun seconds(ms: Double?): String =
        if (ms == null) "—" else String.format(Locale.US, "%.2f s", ms / 1000.0)

    /** 234.0 ms → "0.23". Used for compact split columns. null → "—". */
    fun secondsBare(ms: Double?): String =
        if (ms == null) "—" else String.format(Locale.US, "%.2f", ms / 1000.0)

    private val dateFmt = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US)

    fun dateTime(epochMs: Long): String = dateFmt.format(Date(epochMs))
}
