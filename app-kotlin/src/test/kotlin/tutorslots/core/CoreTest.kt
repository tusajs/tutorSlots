// Port of core-cpp/tests/core_test.cpp. Test names reference acceptance criteria (AC-xx).
package tutorslots.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

private const val MSK = 180

internal fun samplesDir(): File =
    listOf(File("../samples"), File("samples")).first { File(it, "snapshot.tsv").exists() }

private fun hex(s: String) = Rgb.parseHex(s)
private fun parse(text: String) = SnapshotReader.read(text)
private fun weekMinute(day: Int, hour: Int, minute: Int = 0) = day * MINUTES_PER_DAY + hour * 60 + minute

private const val EIGHT_AM = 8 * 60

private fun report(snapshot: String, offset: Int, earliest: Int = EIGHT_AM): String {
    val parsed = parse(snapshot)
    return ReportWriter.write(IntervalBuilder.build(parsed.slots, offset, earliest),
        StatsCalculator.compute(parsed.slots), offset, earliest)
}

class CellClassifierTest {
    @Test fun ac01_greenEmptyIsFree() = assertEquals(Status.FREE, CellClassifier.classify("", hex("#00FF00")))
    @Test fun ac02_whiteWithStudentIsBusy() = assertEquals(Status.BUSY, CellClassifier.classify("Дмитрий ЕГЭ", hex("#FFFFFF")))
    @Test fun ac03_textBeatsGreen() = assertEquals(Status.BUSY, CellClassifier.classify("Иван", hex("#00FF00")))
    @Test fun ac04_redEmptyIsBusy() = assertEquals(Status.BUSY, CellClassifier.classify("", hex("#FF0000")))

    @Test fun ac05_whiteOrNoFillEmptyIsFree() {
        assertEquals(Status.FREE, CellClassifier.classify("", hex("#FFFFFF")))
        assertEquals(Status.FREE, CellClassifier.classify("", null))
    }

    @Test fun ac06_unknownColorIsBusy() {
        assertEquals(ColorClass.OTHER, CellClassifier.classifyColor(hex("#FFFF00")!!))
        assertEquals(Status.BUSY, CellClassifier.classify("", hex("#FFFF00")))
    }

    @Test fun ac07_paleGreenIsGreen() {
        assertEquals(ColorClass.GREEN, CellClassifier.classifyColor(hex("#B7E1CD")!!))
        assertEquals(ColorClass.RED, CellClassifier.classifyColor(hex("#F4CCCC")!!))
    }

    @Test fun colorEdgeCases() {
        val cases = mapOf(
            "#F0F0F0" to ColorClass.WHITE, "#EFEFEF" to ColorClass.OTHER, "#000000" to ColorClass.OTHER,
            "#6AA84F" to ColorClass.GREEN, "#CC0000" to ColorClass.RED, "#FF00FF" to ColorClass.OTHER,
            "#0000FF" to ColorClass.OTHER,
        )
        for ((color, expected) in cases) assertEquals(expected, CellClassifier.classifyColor(hex(color)!!), color)
    }

    @Test fun whitespaceOnlyTextIsEmpty() = assertEquals(Status.FREE, CellClassifier.classify("  \t ", hex("#00FF00")))

    @Test fun parseHex() {
        assertEquals(Rgb(0x1A, 0x2B, 0x3C), hex("#1a2B3c"))
        assertNull(hex("00FF00"))
        assertNull(hex("#00FF0"))
        assertNull(hex("#GGFF00"))
    }
}

class TimeShiftTest {
    @Test fun ac08_moscowToNovosibirsk() = assertEquals(weekMinute(0, 13), TimeShift.toClient(weekMinute(0, 9), 420))
    @Test fun ac09_wrapsForwardOverWeekEnd() = assertEquals(weekMinute(0, 1), TimeShift.toClient(weekMinute(6, 23), 300))
    @Test fun ac10_wrapsBackwardOverWeekStart() = assertEquals(weekMinute(6, 22), TimeShift.toClient(weekMinute(0, 1), 0))
    @Test fun ac11_halfHourOffset() = assertEquals(weekMinute(0, 11, 30), TimeShift.toClient(weekMinute(0, 9), 330))
    @Test fun sameZoneIsIdentity() = assertEquals(weekMinute(3, 15), TimeShift.toClient(weekMinute(3, 15), MSK))

    @Test fun parseOffsetFormats() {
        assertEquals(420, TimeShift.parseOffset("+7"))
        assertEquals(-240, TimeShift.parseOffset("-4"))
        assertEquals(330, TimeShift.parseOffset("+05:30"))
        assertEquals(180, TimeShift.parseOffset("UTC+3"))
        assertEquals(345, TimeShift.parseOffset("+5:45"))
        assertEquals(-720, TimeShift.parseOffset("-12:00"))
        assertEquals(840, TimeShift.parseOffset("+14"))
    }

    @Test fun ac12_rejectsInvalidOffsets() {
        for (s in listOf("+15", "-13", "+5:20", "7", "+abc", "")) assertNull(TimeShift.parseOffset(s), s)
    }
}

class IntervalBuilderTest {
    @Test fun ac13_mergesConsecutiveSlots() {
        val parsed = parse((12..16).joinToString("") { "MON\t$it:00\t#00FF00\n" })
        val plan = IntervalBuilder.build(parsed.slots, MSK)
        assertEquals(listOf(Interval(12 * 60, 17 * 60)), plan[0].intervals)
    }

    @Test fun ac14_splitsAtClientMidnight() {
        val plan = IntervalBuilder.build(parse("SUN\t22:00\t#00FF00\nSUN\t23:00\t#00FF00\n").slots, 240)
        assertEquals(listOf(Interval(23 * 60, 24 * 60)), plan[6].intervals)
        assertEquals(listOf(Interval(0, 60)), plan[0].intervals)
    }

    @Test fun slotCrossingMidnightWithHalfHourOffset() {
        val plan = IntervalBuilder.build(parse("WED\t21:00\t#00FF00\n").slots, 330)
        assertEquals(listOf(Interval(23 * 60 + 30, 24 * 60)), plan[2].intervals)
        assertEquals(listOf(Interval(0, 30)), plan[3].intervals)
    }

    @Test fun busySlotsBreakIntervals() {
        val plan = IntervalBuilder.build(parse("TUE\t10:00\t#00FF00\nTUE\t11:00\t#FF0000\nTUE\t12:00\t#00FF00\n").slots, MSK)
        assertEquals(2, plan[1].intervals.size)
    }

    @Test fun allBusyGivesEmptyPlan() {
        val plan = IntervalBuilder.build(parse("MON\t9:00\t#FF0000\nMON\t10:00\t\tАня\n").slots, MSK)
        assertTrue(plan.all { it.intervals.isEmpty() })
    }
}

class EarliestStartTest {
    @Test fun ac24_hidesNightSlotsForNovosibirsk() {
        val slots = SnapshotReader.read(File(samplesDir(), "snapshot.tsv").readText()).slots
        val all = IntervalBuilder.build(slots, 420)
        val day = IntervalBuilder.build(slots, 420, EIGHT_AM)
        assertEquals(2 * 60, all[0].intervals.first().startMinute)  // 22:00 MSK Sunday = 02:00 Monday
        assertEquals(listOf(Interval(13 * 60, 15 * 60), Interval(16 * 60, 21 * 60)), day[0].intervals)
        assertTrue(day.all { d -> d.intervals.all { it.startMinute >= EIGHT_AM } })
    }

    @Test fun ac25_earliestStartBoundaryIsInclusive() {
        val slots = parse("MON\t9:00\t#00FF00\n").slots
        assertEquals(1, IntervalBuilder.build(slots, 120, EIGHT_AM)[0].intervals.size)  // UTC+2: 8:00 shown
        assertTrue(IntervalBuilder.build(slots, 105, EIGHT_AM)[0].intervals.isEmpty())  // UTC+1:45: 7:45 hidden
    }

    @Test fun earliestStartDoesNotChangeStats() {
        assertEquals(2, StatsCalculator.compute(parse("MON\t1:00\t#00FF00\nMON\t9:00\t#00FF00\n").slots).free)
    }

    @Test fun parseClock() {
        assertEquals(480, TimeShift.parseClock("08:00"))
        assertEquals(450, TimeShift.parseClock(" 7:30 "))
        assertNull(TimeShift.parseClock("25:00"))
        assertNull(TimeShift.parseClock("8"))
    }
}

class StatsTest {
    @Test fun ac15_sampleSnapshot() {
        val parsed = SnapshotReader.read(File(samplesDir(), "snapshot.tsv").readText())
        assertTrue(parsed.diagnostics.isEmpty())
        assertEquals(105, parsed.slots.size)
        val stats = StatsCalculator.compute(parsed.slots)
        assertEquals(Stats(43, 62), stats)
        assertEquals(59, stats.loadPercent)
    }

    @Test fun emptyScheduleHasZeroLoad() = assertEquals(0, Stats(0, 0).loadPercent)
}

class SnapshotReaderTest {
    @Test fun ac16_badLineIsSkippedWithLineNumber() {
        val parsed = parse("day\ttime\tcolor\ttext\nXYZ\t9:00\t#00FF00\nMON\t9:00\t#00FF00\n")
        assertEquals(listOf(2), parsed.diagnostics.map { it.line })
        assertEquals(1, parsed.slots.size)
    }

    @Test fun ac17_duplicateKeepsFirst() {
        val parsed = parse("MON\t9:00\t#00FF00\nMON\t09:00\t#FF0000\n")
        assertEquals(1, parsed.slots.size)
        assertEquals(Status.FREE, parsed.slots[0].status)
        assertEquals(listOf(2), parsed.diagnostics.map { it.line })
    }

    @Test fun ac18_emptyInputHasNoSlots() {
        assertTrue(parse("").slots.isEmpty())
        assertTrue(parse("# only a comment\n\n").slots.isEmpty())
    }

    @Test fun rejectsInvalidValues() {
        val parsed = parse("MON\t24:00\t#00FF00\nMON\t9:5\t#00FF00\nMON\t9:00\tgreen\nMON\nMON\t9:00\t#00FF00\tA\tB\n")
        assertTrue(parsed.slots.isEmpty())
        assertEquals(5, parsed.diagnostics.size)
    }

    @Test fun acceptsShortLinesCrlfAndBom() {
        val parsed = parse("﻿MON\t9:00\r\nsun\t14:00\t#00ff00\r\n")
        assertEquals(2, parsed.slots.size)
        assertTrue(parsed.diagnostics.isEmpty())
        assertEquals(Status.FREE, parsed.slots[0].status)
        assertEquals(6, parsed.slots[1].day)
    }

    @Test fun unknownColorProducesWarning() {
        val parsed = parse("FRI\t10:00\t#FFFF00\n")
        assertEquals(Status.BUSY, parsed.slots.single().status)
        assertEquals(1, parsed.diagnostics.size)
    }
}

class ReportWriterTest {
    @Test fun formatsOffsetAndTime() {
        assertEquals("UTC+07:00", ReportWriter.formatOffset(420))
        assertEquals("UTC-09:30", ReportWriter.formatOffset(-570))
        assertEquals("UTC+00:00", ReportWriter.formatOffset(0))
        assertEquals("24:00", ReportWriter.formatTime(1440))
    }

    @Test fun ac23_matchesReferenceReport() {
        val snapshot = File(samplesDir(), "snapshot.tsv").readText()
        val expected = File(samplesDir(), "expected_utc+07.txt").readText()
        assertEquals(expected, report(snapshot, 420))
    }

    @Test fun ac23_matchesReferenceReportForHalfHourZone() {
        val snapshot = File(samplesDir(), "snapshot.tsv").readText()
        val expected = File(samplesDir(), "expected_utc+05-30.txt").readText()
        assertEquals(expected, report(snapshot, 330))
    }
}
