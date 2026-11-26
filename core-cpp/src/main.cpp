// CLI: tutor_slots <snapshot.tsv> [--offset +07:00] [--earliest 08:00] [--out report.txt]
#include <fstream>
#include <iostream>
#include <string>

#include "tutor_slots/core.hpp"

namespace {

int usage() {
    std::cerr << "Usage: tutor_slots <snapshot.tsv> [--offset +07:00] [--earliest 08:00] [--out report.txt]\n"
                 "  --offset    client UTC offset, from -12:00 to +14:00 (default +03:00)\n"
                 "  --earliest  hide free slots starting earlier, client time (default 08:00)\n";
    return 1;
}

}  // namespace

int main(int argc, char* argv[]) {
    std::string input, output;
    int offset = ts::TimeShift::kSourceOffset;
    int earliest = 8 * 60;
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        if ((arg == "--offset" || arg == "--out" || arg == "--earliest") && i + 1 >= argc) return usage();
        if (arg == "--offset") {
            const auto parsed = ts::TimeShift::parseOffset(argv[++i]);
            if (!parsed) {
                std::cerr << "Error: invalid offset '" << argv[i] << "'\n";
                return usage();
            }
            offset = *parsed;
        } else if (arg == "--earliest") {
            const auto parsed = ts::TimeShift::parseClock(argv[++i]);
            if (!parsed) {
                std::cerr << "Error: invalid time '" << argv[i] << "'\n";
                return usage();
            }
            earliest = *parsed;
        } else if (arg == "--out") {
            output = argv[++i];
        } else if (input.empty() && arg.rfind("--", 0) != 0) {
            input = arg;
        } else {
            return usage();
        }
    }
    if (input.empty()) return usage();

    std::ifstream in(input, std::ios::binary);
    if (!in) {
        std::cerr << "Error: cannot open '" << input << "'\n";
        return 2;
    }
    const ts::ParseResult parsed = ts::SnapshotReader::read(in);
    for (const auto& d : parsed.diagnostics)
        std::cerr << "Warning: line " << d.line << ": " << d.message << "\n";
    if (parsed.slots.empty()) {
        std::cerr << "Error: no valid slots in '" << input << "'\n";
        return 3;
    }

    const ts::WeekPlan plan = ts::IntervalBuilder::build(parsed.slots, offset, earliest);
    const ts::Stats stats = ts::StatsCalculator::compute(parsed.slots);
    if (output.empty()) {
        ts::ReportWriter::write(std::cout, plan, stats, offset, earliest);
        return 0;
    }
    std::ofstream out(output, std::ios::binary);
    if (!out) {
        std::cerr << "Error: cannot write '" << output << "'\n";
        return 2;
    }
    ts::ReportWriter::write(out, plan, stats, offset, earliest);
    return 0;
}
