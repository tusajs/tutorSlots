// TutorSlots core: parses a schedule snapshot, finds free slots and
// converts them into the client's time zone.
#pragma once

#include <array>
#include <istream>
#include <optional>
#include <ostream>
#include <string>
#include <vector>

namespace ts {

constexpr int kMinutesPerDay = 24 * 60;
constexpr int kMinutesPerWeek = 7 * kMinutesPerDay;
constexpr int kSlotMinutes = 60;

enum class Status { Free, Busy };
enum class ColorClass { Green, Red, White, Other };

struct Rgb {
    int r = 0, g = 0, b = 0;
    // Accepts "#RRGGBB" (case-insensitive). Returns nullopt for anything else.
    static std::optional<Rgb> parseHex(const std::string& s);
};

struct Slot {
    int day = 0;          // 0 = Monday ... 6 = Sunday
    int startMinute = 0;  // minutes from 00:00, source time zone (Moscow)
    Status status = Status::Free;
    std::string text;
    int weekMinute() const { return day * kMinutesPerDay + startMinute; }
};

struct Diagnostic {
    int line = 0;
    std::string message;
};

struct ParseResult {
    std::vector<Slot> slots;
    std::vector<Diagnostic> diagnostics;
};

class CellClassifier {
public:
    static ColorClass classifyColor(const Rgb& c);
    // No color means "no fill" and is treated like white.
    static Status classify(const std::string& text, const std::optional<Rgb>& color);
};

class SnapshotReader {
public:
    static ParseResult read(std::istream& in);
};

class TimeShift {
public:
    static constexpr int kSourceOffset = 180;  // Moscow, UTC+3, no DST
    static constexpr int kMinOffset = -12 * 60;
    static constexpr int kMaxOffset = 14 * 60;
    static bool isValidOffset(int minutes);
    // Parses a clock time "H:MM" / "HH:MM" into minutes from midnight.
    static std::optional<int> parseClock(const std::string& s);
    // Parses "+7", "-4", "+05:30", "UTC+3". Returns nullopt if invalid.
    static std::optional<int> parseOffset(const std::string& s);
    static int toClient(int weekMinute, int clientOffset);
};

struct Interval {
    int startMinute = 0;
    int endMinute = 0;  // 1440 means 00:00 of the next day
};

struct DayPlan {
    int day = 0;
    std::vector<Interval> intervals;
};

using WeekPlan = std::array<DayPlan, 7>;

struct Stats {
    int free = 0;
    int busy = 0;
    int loadPercent() const;
};

class IntervalBuilder {
public:
    // Free slots that start before earliestStart (client time, minutes from midnight)
    // are not shown: nobody wants a lesson at 3 a.m. 0 = no limit.
    static WeekPlan build(const std::vector<Slot>& slots, int clientOffset, int earliestStart = 0);
};

class StatsCalculator {
public:
    static Stats compute(const std::vector<Slot>& slots);
};

class ReportWriter {
public:
    static void write(std::ostream& out, const WeekPlan& plan, const Stats& stats, int clientOffset,
                      int earliestStart = 0);
    static std::string formatOffset(int minutes);
    static std::string formatTime(int minutes);
};

}  // namespace ts
