// Kotlin port of core-cpp (include/tutor_slots/core.hpp + src/core.cpp).
// Logic and report format must stay identical to the C++ version (NFR-07).
package tutorslots.core

import kotlin.math.abs

const val MINUTES_PER_DAY = 24 * 60
const val MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY
const val SLOT_MINUTES = 60

enum class Status { FREE, BUSY }
enum class ColorClass { GREEN, RED, WHITE, OTHER }

data class Rgb(val r: Int, val g: Int, val b: Int) {
    companion object {
        /** Accepts "#RRGGBB" (case-insensitive), otherwise null. */
        fun parseHex(s: String): Rgb? {
            if (s.length != 7 || s[0] != '#' || !s.drop(1).all(::isHexDigit)) return null
            return Rgb(s.substring(1, 3).toInt(16), s.substring(3, 5).toInt(16), s.substring(5, 7).toInt(16))
        }
    }
}

data class Slot(
    val day: Int,          // 0 = Monday ... 6 = Sunday
    val startMinute: Int,  // minutes from 00:00, Moscow time
    val status: Status,
    val text: String = "",
) {
    val weekMinute: Int get() = day * MINUTES_PER_DAY + startMinute
}

data class Diagnostic(val line: Int, val message: String)
data class ParseResult(val slots: List<Slot>, val diagnostics: List<Diagnostic>)

data class Interval(val startMinute: Int, val endMinute: Int)  // 1440 = 00:00 next day
data class DayPlan(val day: Int, val intervals: List<Interval>)

data class Stats(val free: Int, val busy: Int) {
    val loadPercent: Int
        get() {
            val total = free + busy
            return if (total == 0) 0 else (busy * 100 + total / 2) / total
        }
}

private val DAY_CODES = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

// Same character sets as the C++ version (ASCII only), so both behave identically.
private fun isAsciiDigit(c: Char) = c in '0'..'9'
private fun isHexDigit(c: Char) = isAsciiDigit(c) || c in 'a'..'f' || c in 'A'..'F'
private fun String.allAsciiDigits() = isNotEmpty() && all(::isAsciiDigit)
internal fun String.trimAscii() = trim { it == ' ' || it == '\t' || it == '\r' || it == '\n' }

private fun parseTime(s: String): Int? {
    val colon = s.indexOf(':')
    if (colon < 0) return null
    val h = s.substring(0, colon)
    val m = s.substring(colon + 1)
    if (!h.allAsciiDigits() || !m.allAsciiDigits() || h.length > 2 || m.length != 2) return null
    val hours = h.toInt()
    val minutes = m.toInt()
    if (hours > 23 || minutes > 59) return null
    return hours * 60 + minutes
}

private fun parseDay(s: String): Int? = DAY_CODES.indexOf(s.uppercase()).takeIf { it >= 0 }

object CellClassifier {
    fun classifyColor(c: Rgb): ColorClass {
        val mx = maxOf(c.r, c.g, c.b)
        val mn = minOf(c.r, c.g, c.b)
        val delta = mx - mn
        if (mn >= 240) return ColorClass.WHITE
        if (delta == 0 || delta * 100 < mx * 15) return ColorClass.OTHER  // saturation < 15%
        val d = delta.toDouble()
        val hue = when (mx) {
            c.r -> 60.0 * (((c.g - c.b) / d + 6.0) % 6.0)
            c.g -> 60.0 * ((c.b - c.r) / d + 2.0)
            else -> 60.0 * ((c.r - c.g) / d + 4.0)
        }
        return when {
            hue <= 20.0 || hue >= 340.0 -> ColorClass.RED
            hue in 75.0..165.0 -> ColorClass.GREEN
            else -> ColorClass.OTHER
        }
    }

    /** null color = no fill, treated like white. */
    fun classify(text: String, color: Rgb?): Status {
        if (text.trimAscii().isNotEmpty()) return Status.BUSY  // a student's name always wins
        if (color == null) return Status.FREE
        val cls = classifyColor(color)
        return if (cls == ColorClass.GREEN || cls == ColorClass.WHITE) Status.FREE else Status.BUSY
    }
}

object SnapshotReader {
    fun read(input: String): ParseResult {
        val slots = mutableListOf<Slot>()
        val diagnostics = mutableListOf<Diagnostic>()
        val seen = HashSet<Int>()
        var headerSkipped = false
        input.split('\n').forEachIndexed { index, raw ->
            val n = index + 1
            var line = if (n == 1) raw.removePrefix("﻿") else raw
            line = line.removeSuffix("\r")
            if (line.trimAscii().isEmpty() || line[0] == '#') return@forEachIndexed
            val f = line.split('\t').toMutableList()
            if (!headerSkipped && f[0].trimAscii() == "day") {
                headerSkipped = true
                return@forEachIndexed
            }
            fun fail(msg: String) = diagnostics.add(Diagnostic(n, msg))
            if (f.size < 2 || f.size > 4) { fail("expected 2-4 tab-separated fields"); return@forEachIndexed }
            while (f.size < 4) f.add("")
            val day = parseDay(f[0].trimAscii())
                ?: run { fail("unknown day '${f[0].trimAscii()}'"); return@forEachIndexed }
            val start = parseTime(f[1].trimAscii())
                ?: run { fail("invalid time '${f[1].trimAscii()}'"); return@forEachIndexed }
            val colorText = f[2].trimAscii()
            var color: Rgb? = null
            if (colorText.isNotEmpty()) {
                color = Rgb.parseHex(colorText)
                    ?: run { fail("invalid color '$colorText'"); return@forEachIndexed }
            }
            val slot = Slot(day, start, CellClassifier.classify(f[3], color), f[3].trimAscii())
            if (!seen.add(slot.weekMinute)) { fail("duplicate slot, ignored"); return@forEachIndexed }
            if (color != null && slot.text.isEmpty() &&
                CellClassifier.classifyColor(color) == ColorClass.OTHER
            ) fail("unknown color $colorText, treated as busy")
            slots.add(slot)
        }
        return ParseResult(slots, diagnostics)
    }
}

object TimeShift {
    const val SOURCE_OFFSET = 180  // Moscow, UTC+3, no DST
    const val MIN_OFFSET = -12 * 60
    const val MAX_OFFSET = 14 * 60

    fun isValidOffset(minutes: Int) = minutes in MIN_OFFSET..MAX_OFFSET && minutes % 15 == 0

    /** Parses a clock time "H:MM" / "HH:MM" into minutes from midnight. */
    fun parseClock(s: String): Int? = parseTime(s.trimAscii())

    /** Parses "+7", "-4", "+05:30", "UTC+3". Returns null if invalid. */
    fun parseOffset(raw: String): Int? {
        val s = raw.trimAscii().removePrefix("UTC")
        if (s.length < 2 || (s[0] != '+' && s[0] != '-')) return null
        val sign = if (s[0] == '-') -1 else 1
        val body = s.substring(1)
        val colon = body.indexOf(':')
        val h = if (colon < 0) body else body.substring(0, colon)
        val m = if (colon < 0) "00" else body.substring(colon + 1)
        if (!h.allAsciiDigits() || h.length > 2 || !m.allAsciiDigits() || m.length != 2) return null
        val minutes = m.toInt()
        if (minutes > 59) return null
        val total = sign * (h.toInt() * 60 + minutes)
        return total.takeIf { isValidOffset(it) }
    }

    fun toClient(weekMinute: Int, clientOffset: Int): Int =
        Math.floorMod(weekMinute - SOURCE_OFFSET + clientOffset, MINUTES_PER_WEEK)
}

object IntervalBuilder {
    /**
     * Free slots that start before [earliestStart] (client time, minutes from midnight)
     * are not shown: nobody wants a lesson at 3 a.m. 0 = no limit.
     */
    fun build(slots: List<Slot>, clientOffset: Int, earliestStart: Int = 0): List<DayPlan> {
        val pieces = List(7) { mutableListOf<Interval>() }
        for (slot in slots) {
            if (slot.status != Status.FREE) continue
            val m = TimeShift.toClient(slot.weekMinute, clientOffset)
            val day = m / MINUTES_PER_DAY
            val start = m % MINUTES_PER_DAY
            if (start < earliestStart) continue  // too early for the client
            val end = start + SLOT_MINUTES
            if (end <= MINUTES_PER_DAY) {
                pieces[day].add(Interval(start, end))
            } else {  // crosses the client's midnight: split into two days
                pieces[day].add(Interval(start, MINUTES_PER_DAY))
                pieces[(day + 1) % 7].add(Interval(0, end - MINUTES_PER_DAY))
            }
        }
        return pieces.mapIndexed { day, list ->
            val merged = mutableListOf<Interval>()
            for (iv in list.sortedBy { it.startMinute }) {
                val last = merged.lastOrNull()
                if (last != null && iv.startMinute <= last.endMinute)
                    merged[merged.lastIndex] = last.copy(endMinute = maxOf(last.endMinute, iv.endMinute))
                else
                    merged.add(iv)
            }
            DayPlan(day, merged)
        }
    }
}

object StatsCalculator {
    fun compute(slots: List<Slot>) =
        Stats(free = slots.count { it.status == Status.FREE }, busy = slots.count { it.status == Status.BUSY })
}

object ReportWriter {
    fun formatTime(minutes: Int): String =
        "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

    fun formatOffset(minutes: Int): String = "UTC" + (if (minutes < 0) "-" else "+") + formatTime(abs(minutes))

    fun write(plan: List<DayPlan>, stats: Stats, clientOffset: Int, earliestStart: Int = 0): String = buildString {
        append("TutorSlots report\n")
        append("Client offset: ${formatOffset(clientOffset)}\n")
        append("Earliest start: ${formatTime(earliestStart)}\n")
        for (dayPlan in plan) {
            append(DAY_NAMES[dayPlan.day]).append(": ")
            if (dayPlan.intervals.isEmpty()) append("no free slots")
            append(dayPlan.intervals.joinToString(", ") { "${formatTime(it.startMinute)}-${formatTime(it.endMinute)}" })
            append('\n')
        }
        append("Free: ${stats.free}\nBusy: ${stats.busy}\n")
        append("Load: ${stats.loadPercent}%\n")
    }
}
