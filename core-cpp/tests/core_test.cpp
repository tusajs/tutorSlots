// Unit tests for the TutorSlots core. Test names reference acceptance
// criteria (AC-xx) from docs/requirements.md.
#include <gtest/gtest.h>

#include <fstream>
#include <sstream>
#include <string>

#include "tutor_slots/core.hpp"

using namespace ts;

namespace {

constexpr int kMsk = 180;

std::optional<Rgb> hex(const std::string& s) { return Rgb::parseHex(s); }

ParseResult parse(const std::string& text) {
    std::istringstream in(text);
    return SnapshotReader::read(in);
}

constexpr int kEightAm = 8 * 60;

std::string report(const std::string& snapshot, int offset, int earliest = kEightAm) {
    const auto parsed = parse(snapshot);
    std::ostringstream out;
    ReportWriter::write(out, IntervalBuilder::build(parsed.slots, offset, earliest),
                        StatsCalculator::compute(parsed.slots), offset, earliest);
    return out.str();
}

std::string readFile(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    std::ostringstream ss;
    ss << in.rdbuf();
    return ss.str();
}

int weekMinute(int day, int hour, int minute = 0) {
    return day * kMinutesPerDay + hour * 60 + minute;
}

}  // namespace

// ---------- Cell classification (FR-05, FR-06) ----------

TEST(CellClassifier, AC01_GreenEmptyIsFree) {
    EXPECT_EQ(CellClassifier::classify("", hex("#00FF00")), Status::Free);
}

TEST(CellClassifier, AC02_WhiteWithStudentIsBusy) {
    EXPECT_EQ(CellClassifier::classify("Дмитрий ЕГЭ", hex("#FFFFFF")), Status::Busy);
}

TEST(CellClassifier, AC03_TextBeatsGreen) {
    EXPECT_EQ(CellClassifier::classify("Иван", hex("#00FF00")), Status::Busy);
}

TEST(CellClassifier, AC04_RedEmptyIsBusy) {
    EXPECT_EQ(CellClassifier::classify("", hex("#FF0000")), Status::Busy);
}

TEST(CellClassifier, AC05_WhiteOrNoFillEmptyIsFree) {
    EXPECT_EQ(CellClassifier::classify("", hex("#FFFFFF")), Status::Free);
    EXPECT_EQ(CellClassifier::classify("", std::nullopt), Status::Free);
}

TEST(CellClassifier, AC06_UnknownColorIsBusy) {
    EXPECT_EQ(CellClassifier::classifyColor(*hex("#FFFF00")), ColorClass::Other);
    EXPECT_EQ(CellClassifier::classify("", hex("#FFFF00")), Status::Busy);
}

TEST(CellClassifier, AC07_PaleGreenIsGreen) {
    EXPECT_EQ(CellClassifier::classifyColor(*hex("#B7E1CD")), ColorClass::Green);
    EXPECT_EQ(CellClassifier::classifyColor(*hex("#F4CCCC")), ColorClass::Red);
}

TEST(CellClassifier, ColorEdgeCases) {
    EXPECT_EQ(CellClassifier::classifyColor(*hex("#F0F0F0")), ColorClass::White);  // threshold
    EXPECT_EQ(CellClassifier::classifyColor(*hex("#EFEFEF")), ColorClass::Other);  // light gray
    EXPECT_EQ(CellClassifier::classifyColor(*hex("#000000")), ColorClass::Other);  // black
    EXPECT_EQ(CellClassifier::classifyColor(*hex("#6AA84F")), ColorClass::Green);  // dark green 1
    EXPECT_EQ(CellClassifier::classifyColor(*hex("#CC0000")), ColorClass::Red);    // dark red 1
    EXPECT_EQ(CellClassifier::classifyColor(*hex("#FF00FF")), ColorClass::Other);  // magenta
    EXPECT_EQ(CellClassifier::classifyColor(*hex("#0000FF")), ColorClass::Other);  // blue
}

TEST(CellClassifier, WhitespaceOnlyTextIsEmpty) {
    EXPECT_EQ(CellClassifier::classify("  \t ", hex("#00FF00")), Status::Free);
}

TEST(Rgb, ParseHex) {
    const auto c = hex("#1a2B3c");
    ASSERT_TRUE(c.has_value());
    EXPECT_EQ(c->r, 0x1A);
    EXPECT_EQ(c->g, 0x2B);
    EXPECT_EQ(c->b, 0x3C);
    EXPECT_FALSE(hex("00FF00").has_value());
    EXPECT_FALSE(hex("#00FF0").has_value());
    EXPECT_FALSE(hex("#GGFF00").has_value());
}

// ---------- Time shift (FR-07, FR-08) ----------

TEST(TimeShift, AC08_MoscowToNovosibirsk) {
    EXPECT_EQ(TimeShift::toClient(weekMinute(0, 9), 7 * 60), weekMinute(0, 13));
}

TEST(TimeShift, AC09_WrapsForwardOverWeekEnd) {
    EXPECT_EQ(TimeShift::toClient(weekMinute(6, 23), 5 * 60), weekMinute(0, 1));
}

TEST(TimeShift, AC10_WrapsBackwardOverWeekStart) {
    EXPECT_EQ(TimeShift::toClient(weekMinute(0, 1), 0), weekMinute(6, 22));
}

TEST(TimeShift, AC11_HalfHourOffset) {
    EXPECT_EQ(TimeShift::toClient(weekMinute(0, 9), 5 * 60 + 30), weekMinute(0, 11, 30));
}

TEST(TimeShift, SameZoneIsIdentity) {
    EXPECT_EQ(TimeShift::toClient(weekMinute(3, 15), kMsk), weekMinute(3, 15));
}

TEST(TimeShift, ParseOffsetFormats) {
    EXPECT_EQ(TimeShift::parseOffset("+7"), 420);
    EXPECT_EQ(TimeShift::parseOffset("-4"), -240);
    EXPECT_EQ(TimeShift::parseOffset("+05:30"), 330);
    EXPECT_EQ(TimeShift::parseOffset("UTC+3"), 180);
    EXPECT_EQ(TimeShift::parseOffset("+5:45"), 345);
    EXPECT_EQ(TimeShift::parseOffset("-12:00"), -720);
    EXPECT_EQ(TimeShift::parseOffset("+14"), 840);
}

TEST(TimeShift, AC12_RejectsInvalidOffsets) {
    EXPECT_FALSE(TimeShift::parseOffset("+15").has_value());
    EXPECT_FALSE(TimeShift::parseOffset("-13").has_value());
    EXPECT_FALSE(TimeShift::parseOffset("+5:20").has_value());  // not a 15-minute step
    EXPECT_FALSE(TimeShift::parseOffset("7").has_value());      // sign is required
    EXPECT_FALSE(TimeShift::parseOffset("+abc").has_value());
    EXPECT_FALSE(TimeShift::parseOffset("").has_value());
}

// ---------- Intervals (FR-10) ----------

TEST(IntervalBuilder, AC13_MergesConsecutiveSlots) {
    const auto parsed = parse(
        "MON\t12:00\t#00FF00\nMON\t13:00\t#00FF00\nMON\t14:00\t#00FF00\n"
        "MON\t15:00\t#00FF00\nMON\t16:00\t#00FF00\n");
    const auto plan = IntervalBuilder::build(parsed.slots, kMsk);
    ASSERT_EQ(plan[0].intervals.size(), 1u);
    EXPECT_EQ(plan[0].intervals[0].startMinute, 12 * 60);
    EXPECT_EQ(plan[0].intervals[0].endMinute, 17 * 60);
}

TEST(IntervalBuilder, AC14_SplitsAtClientMidnight) {
    const auto parsed = parse("SUN\t22:00\t#00FF00\nSUN\t23:00\t#00FF00\n");
    const auto plan = IntervalBuilder::build(parsed.slots, 4 * 60);
    ASSERT_EQ(plan[6].intervals.size(), 1u);
    EXPECT_EQ(plan[6].intervals[0].startMinute, 23 * 60);
    EXPECT_EQ(plan[6].intervals[0].endMinute, 24 * 60);
    ASSERT_EQ(plan[0].intervals.size(), 1u);
    EXPECT_EQ(plan[0].intervals[0].startMinute, 0);
    EXPECT_EQ(plan[0].intervals[0].endMinute, 60);
}

TEST(IntervalBuilder, SlotCrossingMidnightWithHalfHourOffset) {
    // 21:00 MSK = 23:30 in UTC+5:30 -> 23:30-24:00 and 00:00-00:30 next day
    const auto parsed = parse("WED\t21:00\t#00FF00\n");
    const auto plan = IntervalBuilder::build(parsed.slots, 5 * 60 + 30);
    ASSERT_EQ(plan[2].intervals.size(), 1u);
    EXPECT_EQ(plan[2].intervals[0].startMinute, 23 * 60 + 30);
    ASSERT_EQ(plan[3].intervals.size(), 1u);
    EXPECT_EQ(plan[3].intervals[0].endMinute, 30);
}

TEST(IntervalBuilder, BusySlotsBreakIntervals) {
    const auto parsed = parse(
        "TUE\t10:00\t#00FF00\nTUE\t11:00\t#FF0000\nTUE\t12:00\t#00FF00\n");
    const auto plan = IntervalBuilder::build(parsed.slots, kMsk);
    EXPECT_EQ(plan[1].intervals.size(), 2u);
}

TEST(IntervalBuilder, AllBusyGivesEmptyPlan) {
    const auto parsed = parse("MON\t9:00\t#FF0000\nMON\t10:00\t\tАня\n");
    for (const auto& day : IntervalBuilder::build(parsed.slots, kMsk))
        EXPECT_TRUE(day.intervals.empty());
}

// ---------- Earliest start for the client (FR-24) ----------

TEST(IntervalBuilder, AC24_HidesNightSlotsForNovosibirsk) {
    const std::string snapshot = readFile(std::string(TS_SAMPLES_DIR) + "/snapshot.tsv");
    const auto parsed = parse(snapshot);
    const auto all = IntervalBuilder::build(parsed.slots, 7 * 60);
    const auto day = IntervalBuilder::build(parsed.slots, 7 * 60, kEightAm);
    EXPECT_EQ(all[0].intervals.front().startMinute, 2 * 60);  // 22:00 MSK Sunday = 02:00 Monday
    ASSERT_EQ(day[0].intervals.size(), 2u);                   // 13:00-15:00, 16:00-21:00 remain
    EXPECT_EQ(day[0].intervals.front().startMinute, 13 * 60);
    for (const auto& d : day)
        for (const auto& iv : d.intervals) EXPECT_TRUE(iv.startMinute >= kEightAm);
}

TEST(IntervalBuilder, AC25_EarliestStartBoundaryIsInclusive) {
    const auto parsed = parse("MON\t9:00\t#00FF00\n");
    // Kaliningrad, UTC+2: 9:00 MSK = 8:00 -> shown
    EXPECT_EQ(IntervalBuilder::build(parsed.slots, 2 * 60, kEightAm)[0].intervals.size(), 1u);
    // UTC+1:45: 9:00 MSK = 7:45 -> hidden
    EXPECT_TRUE(IntervalBuilder::build(parsed.slots, 105, kEightAm)[0].intervals.empty());
}

TEST(IntervalBuilder, EarliestStartDoesNotChangeStats) {
    const auto parsed = parse("MON\t1:00\t#00FF00\nMON\t9:00\t#00FF00\n");
    EXPECT_EQ(StatsCalculator::compute(parsed.slots).free, 2);  // tutor's hours, not the client's view
}

TEST(TimeShift, ParseClock) {
    EXPECT_EQ(TimeShift::parseClock("08:00"), 480);
    EXPECT_EQ(TimeShift::parseClock(" 7:30 "), 450);
    EXPECT_FALSE(TimeShift::parseClock("25:00").has_value());
    EXPECT_FALSE(TimeShift::parseClock("8").has_value());
}

// ---------- Stats (FR-11) ----------

TEST(Stats, AC15_SampleSnapshot) {
    std::ifstream in(std::string(TS_SAMPLES_DIR) + "/snapshot.tsv", std::ios::binary);
    ASSERT_TRUE(in.good());
    const auto parsed = SnapshotReader::read(in);
    EXPECT_TRUE(parsed.diagnostics.empty());
    ASSERT_EQ(parsed.slots.size(), 105u);
    const auto stats = StatsCalculator::compute(parsed.slots);
    EXPECT_EQ(stats.free, 43);
    EXPECT_EQ(stats.busy, 62);
    EXPECT_EQ(stats.loadPercent(), 59);
}

TEST(Stats, EmptyScheduleHasZeroLoad) {
    EXPECT_EQ(Stats{}.loadPercent(), 0);
}

// ---------- Snapshot reader (FR-21 - FR-23) ----------

TEST(SnapshotReader, AC16_BadLineIsSkippedWithLineNumber) {
    const auto parsed = parse("day\ttime\tcolor\ttext\nXYZ\t9:00\t#00FF00\nMON\t9:00\t#00FF00\n");
    ASSERT_EQ(parsed.diagnostics.size(), 1u);
    EXPECT_EQ(parsed.diagnostics[0].line, 2);
    EXPECT_EQ(parsed.slots.size(), 1u);
}

TEST(SnapshotReader, AC17_DuplicateKeepsFirst) {
    const auto parsed = parse("MON\t9:00\t#00FF00\nMON\t09:00\t#FF0000\n");
    ASSERT_EQ(parsed.slots.size(), 1u);
    EXPECT_EQ(parsed.slots[0].status, Status::Free);
    ASSERT_EQ(parsed.diagnostics.size(), 1u);
    EXPECT_EQ(parsed.diagnostics[0].line, 2);
}

TEST(SnapshotReader, AC18_EmptyInputHasNoSlots) {
    EXPECT_TRUE(parse("").slots.empty());
    EXPECT_TRUE(parse("# only a comment\n\n").slots.empty());
}

TEST(SnapshotReader, RejectsInvalidValues) {
    const auto parsed = parse(
        "MON\t24:00\t#00FF00\n"   // hour out of range
        "MON\t9:5\t#00FF00\n"     // minutes must have two digits
        "MON\t9:00\tgreen\n"      // not a hex color
        "MON\n"                   // too few fields
        "MON\t9:00\t#00FF00\tA\tB\n");  // too many fields
    EXPECT_TRUE(parsed.slots.empty());
    EXPECT_EQ(parsed.diagnostics.size(), 5u);
}

TEST(SnapshotReader, AcceptsShortLinesCrlfAndBom) {
    const auto parsed = parse("\xEF\xBB\xBFMON\t9:00\r\nsun\t14:00\t#00ff00\r\n");
    ASSERT_EQ(parsed.slots.size(), 2u);
    EXPECT_TRUE(parsed.diagnostics.empty());
    EXPECT_EQ(parsed.slots[0].status, Status::Free);
    EXPECT_EQ(parsed.slots[1].day, 6);
}

TEST(SnapshotReader, UnknownColorProducesWarning) {
    const auto parsed = parse("FRI\t10:00\t#FFFF00\n");
    ASSERT_EQ(parsed.slots.size(), 1u);
    EXPECT_EQ(parsed.slots[0].status, Status::Busy);
    EXPECT_EQ(parsed.diagnostics.size(), 1u);
}

// ---------- Report (FR-12, NFR-07) ----------

TEST(ReportWriter, FormatsOffsetAndTime) {
    EXPECT_EQ(ReportWriter::formatOffset(420), "UTC+07:00");
    EXPECT_EQ(ReportWriter::formatOffset(-570), "UTC-09:30");
    EXPECT_EQ(ReportWriter::formatOffset(0), "UTC+00:00");
    EXPECT_EQ(ReportWriter::formatTime(1440), "24:00");
}

TEST(ReportWriter, AC23_MatchesReferenceReport) {
    const std::string snapshot = readFile(std::string(TS_SAMPLES_DIR) + "/snapshot.tsv");
    const std::string expected = readFile(std::string(TS_SAMPLES_DIR) + "/expected_utc+07.txt");
    ASSERT_FALSE(expected.empty());
    EXPECT_EQ(report(snapshot, 7 * 60), expected);
}

TEST(ReportWriter, AC23_MatchesReferenceReportForHalfHourZone) {
    const std::string snapshot = readFile(std::string(TS_SAMPLES_DIR) + "/snapshot.tsv");
    const std::string expected = readFile(std::string(TS_SAMPLES_DIR) + "/expected_utc+05-30.txt");
    ASSERT_FALSE(expected.empty());
    EXPECT_EQ(report(snapshot, 5 * 60 + 30), expected);
}
