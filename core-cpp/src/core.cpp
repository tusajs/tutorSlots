#include "tutor_slots/core.hpp"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <set>
#include <sstream>

namespace ts {
namespace {

const char* const kDayCodes[7] = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};
const char* const kDayNames[7] = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

std::string trim(const std::string& s) {
    const char* ws = " \t\r\n";
    const auto first = s.find_first_not_of(ws);
    if (first == std::string::npos) return "";
    return s.substr(first, s.find_last_not_of(ws) - first + 1);
}

std::vector<std::string> splitTabs(const std::string& line) {
    std::vector<std::string> fields;
    std::string field;
    std::istringstream stream(line);
    while (std::getline(stream, field, '\t')) fields.push_back(field);
    if (!line.empty() && line.back() == '\t') fields.emplace_back();
    return fields;
}

bool allDigits(const std::string& s) {
    return !s.empty() && std::all_of(s.begin(), s.end(),
                                     [](unsigned char c) { return std::isdigit(c) != 0; });
}

// "H:MM" or "HH:MM" -> minutes from midnight.
std::optional<int> parseTime(const std::string& s) {
    const auto colon = s.find(':');
    if (colon == std::string::npos) return std::nullopt;
    const std::string h = s.substr(0, colon), m = s.substr(colon + 1);
    if (!allDigits(h) || !allDigits(m) || h.size() > 2 || m.size() != 2) return std::nullopt;
    const int hours = std::stoi(h), minutes = std::stoi(m);
    if (hours > 23 || minutes > 59) return std::nullopt;
    return hours * 60 + minutes;
}

std::optional<int> parseDay(std::string s) {
    for (auto& c : s) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
    for (int i = 0; i < 7; ++i)
        if (s == kDayCodes[i]) return i;
    return std::nullopt;
}

}  // namespace

std::optional<Rgb> Rgb::parseHex(const std::string& s) {
    if (s.size() != 7 || s[0] != '#') return std::nullopt;
    if (!std::all_of(s.begin() + 1, s.end(),
                     [](unsigned char c) { return std::isxdigit(c) != 0; }))
        return std::nullopt;
    const auto channel = [&](int pos) { return std::stoi(s.substr(pos, 2), nullptr, 16); };
    return Rgb{channel(1), channel(3), channel(5)};
}

ColorClass CellClassifier::classifyColor(const Rgb& c) {
    // HSV-based: pale sheet colors like #B7E1CD still count as green.
    const int mx = std::max({c.r, c.g, c.b}), mn = std::min({c.r, c.g, c.b});
    const int delta = mx - mn;
    if (mn >= 240) return ColorClass::White;
    if (delta == 0 || delta * 100 < mx * 15) return ColorClass::Other;  // saturation < 15%
    double hue;
    if (mx == c.r) hue = 60.0 * std::fmod((c.g - c.b) / static_cast<double>(delta) + 6.0, 6.0);
    else if (mx == c.g) hue = 60.0 * ((c.b - c.r) / static_cast<double>(delta) + 2.0);
    else hue = 60.0 * ((c.r - c.g) / static_cast<double>(delta) + 4.0);
    if (hue <= 20.0 || hue >= 340.0) return ColorClass::Red;
    if (hue >= 75.0 && hue <= 165.0) return ColorClass::Green;
    return ColorClass::Other;
}

Status CellClassifier::classify(const std::string& text, const std::optional<Rgb>& color) {
    if (!trim(text).empty()) return Status::Busy;  // a student's name always wins
    if (!color) return Status::Free;                // no fill = white
    const ColorClass cls = classifyColor(*color);
    return (cls == ColorClass::Green || cls == ColorClass::White) ? Status::Free : Status::Busy;
}

ParseResult SnapshotReader::read(std::istream& in) {
    ParseResult result;
    std::set<int> seen;
    std::string line;
    bool headerSkipped = false;
    for (int n = 1; std::getline(in, line); ++n) {
        if (n == 1 && line.rfind("\xEF\xBB\xBF", 0) == 0) line.erase(0, 3);  // UTF-8 BOM
        if (!line.empty() && line.back() == '\r') line.pop_back();          // CRLF files
        if (trim(line).empty() || line[0] == '#') continue;
        auto f = splitTabs(line);
        if (!headerSkipped && !f.empty() && trim(f[0]) == "day") {
            headerSkipped = true;
            continue;
        }
        const auto fail = [&](const std::string& msg) { result.diagnostics.push_back({n, msg}); };
        if (f.size() < 2 || f.size() > 4) { fail("expected 2-4 tab-separated fields"); continue; }
        f.resize(4);
        const auto day = parseDay(trim(f[0]));
        if (!day) { fail("unknown day '" + trim(f[0]) + "'"); continue; }
        const auto start = parseTime(trim(f[1]));
        if (!start) { fail("invalid time '" + trim(f[1]) + "'"); continue; }
        std::optional<Rgb> color;
        if (!trim(f[2]).empty()) {
            color = Rgb::parseHex(trim(f[2]));
            if (!color) { fail("invalid color '" + trim(f[2]) + "'"); continue; }
        }
        Slot slot{*day, *start, CellClassifier::classify(f[3], color), trim(f[3])};
        if (!seen.insert(slot.weekMinute()).second) { fail("duplicate slot, ignored"); continue; }
        if (color && slot.text.empty() &&
            CellClassifier::classifyColor(*color) == ColorClass::Other)
            fail("unknown color " + trim(f[2]) + ", treated as busy");
        result.slots.push_back(std::move(slot));
    }
    return result;
}

std::optional<int> TimeShift::parseClock(const std::string& s) { return parseTime(trim(s)); }

bool TimeShift::isValidOffset(int minutes) {
    return minutes >= kMinOffset && minutes <= kMaxOffset && minutes % 15 == 0;
}

std::optional<int> TimeShift::parseOffset(const std::string& raw) {
    std::string s = trim(raw);
    if (s.rfind("UTC", 0) == 0) s = s.substr(3);
    if (s.size() < 2 || (s[0] != '+' && s[0] != '-')) return std::nullopt;
    const int sign = s[0] == '-' ? -1 : 1;
    const std::string body = s.substr(1);
    const auto colon = body.find(':');
    const std::string h = body.substr(0, colon);
    const std::string m = colon == std::string::npos ? "00" : body.substr(colon + 1);
    if (!allDigits(h) || h.size() > 2 || !allDigits(m) || m.size() != 2) return std::nullopt;
    const int minutes = std::stoi(m);
    if (minutes > 59) return std::nullopt;
    const int total = sign * (std::stoi(h) * 60 + minutes);
    return isValidOffset(total) ? std::optional<int>(total) : std::nullopt;
}

int TimeShift::toClient(int weekMinute, int clientOffset) {
    const int shifted = weekMinute - kSourceOffset + clientOffset;
    return ((shifted % kMinutesPerWeek) + kMinutesPerWeek) % kMinutesPerWeek;
}

WeekPlan IntervalBuilder::build(const std::vector<Slot>& slots, int clientOffset, int earliestStart) {
    WeekPlan plan;
    for (int d = 0; d < 7; ++d) plan[d].day = d;
    for (const auto& slot : slots) {
        if (slot.status != Status::Free) continue;
        const int m = TimeShift::toClient(slot.weekMinute(), clientOffset);
        const int day = m / kMinutesPerDay, start = m % kMinutesPerDay;
        if (start < earliestStart) continue;  // too early for the client
        const int end = start + kSlotMinutes;
        if (end <= kMinutesPerDay) {
            plan[day].intervals.push_back({start, end});
        } else {  // crosses the client's midnight: split into two days
            plan[day].intervals.push_back({start, kMinutesPerDay});
            plan[(day + 1) % 7].intervals.push_back({0, end - kMinutesPerDay});
        }
    }
    for (auto& dayPlan : plan) {
        auto& v = dayPlan.intervals;
        std::sort(v.begin(), v.end(),
                  [](const Interval& a, const Interval& b) { return a.startMinute < b.startMinute; });
        std::vector<Interval> merged;
        for (const auto& iv : v) {
            if (!merged.empty() && iv.startMinute <= merged.back().endMinute)
                merged.back().endMinute = std::max(merged.back().endMinute, iv.endMinute);
            else
                merged.push_back(iv);
        }
        v = std::move(merged);
    }
    return plan;
}

int Stats::loadPercent() const {
    const int total = free + busy;
    return total == 0 ? 0 : (busy * 100 + total / 2) / total;
}

Stats StatsCalculator::compute(const std::vector<Slot>& slots) {
    Stats stats;
    for (const auto& s : slots) (s.status == Status::Free ? stats.free : stats.busy)++;
    return stats;
}

std::string ReportWriter::formatTime(int minutes) {
    const auto two = [](int v) { return std::string(v < 10 ? "0" : "") + std::to_string(v); };
    return two(minutes / 60) + ":" + two(minutes % 60);
}

std::string ReportWriter::formatOffset(int minutes) {
    return std::string("UTC") + (minutes < 0 ? "-" : "+") + formatTime(std::abs(minutes));
}

void ReportWriter::write(std::ostream& out, const WeekPlan& plan, const Stats& stats,
                         int clientOffset, int earliestStart) {
    out << "TutorSlots report\n";
    out << "Client offset: " << formatOffset(clientOffset) << "\n";
    out << "Earliest start: " << formatTime(earliestStart) << "\n";
    for (const auto& dayPlan : plan) {
        out << kDayNames[dayPlan.day] << ": ";
        if (dayPlan.intervals.empty()) out << "no free slots";
        for (std::size_t i = 0; i < dayPlan.intervals.size(); ++i) {
            const auto& iv = dayPlan.intervals[i];
            out << (i ? ", " : "") << formatTime(iv.startMinute) << "-" << formatTime(iv.endMinute);
        }
        out << "\n";
    }
    out << "Free: " << stats.free << "\nBusy: " << stats.busy << "\n";
    out << "Load: " << stats.loadPercent() << "%\n";
}

}  // namespace ts
