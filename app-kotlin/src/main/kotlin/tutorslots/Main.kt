package tutorslots

import tutorslots.core.IntervalBuilder
import tutorslots.core.ReportWriter
import tutorslots.core.SnapshotReader
import tutorslots.core.StatsCalculator
import tutorslots.core.TimeShift
import tutorslots.data.Config
import tutorslots.data.ScheduleRepository
import tutorslots.data.SheetsClient
import tutorslots.data.UserSettingsStore
import tutorslots.domain.SlotsService
import tutorslots.domain.SlotsView
import tutorslots.domain.ZoneResolver
import tutorslots.presentation.SlotsRenderer
import tutorslots.presentation.TelegramBotApi
import tutorslots.presentation.TutorBot
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  bot                                         run the Telegram bot (settings from bot.env)
  fetch [--out snapshot.tsv]                  download the schedule once and print/save it
  report <snapshot.tsv> [--offset +07:00] [--earliest 08:00]   same text report as the C++ tutor_slots
  render <snapshot.tsv> [--zone Asia/Novosibirsk] [--out slots.png]"""

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    // Use the Windows/macOS system proxy (e.g. a VPN client in "system proxy" mode) unless PROXY is set
    System.setProperty("java.net.useSystemProxies", "true")
    val mode = args.firstOrNull() ?: "bot"
    val opts = parseOptions(args.drop(1))
    val code = try {
        when (mode) {
            "bot" -> runBot()
            "fetch" -> fetch(opts)
            "report" -> report(opts)
            "render" -> render(opts)
            else -> { System.err.println(USAGE); 1 }
        }
    } catch (e: Exception) {  // missing settings, Google or network errors
        System.err.println("Error: ${e.message}")
        1
    }
    exitProcess(code)
}

private class Options(val positional: List<String>, val named: Map<String, String>)

private fun parseOptions(args: List<String>): Options {
    val positional = mutableListOf<String>()
    val named = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        if (args[i].startsWith("--") && i + 1 < args.size) { named[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
        else positional.add(args[i++])
    }
    return Options(positional, named)
}

private fun runBot(): Int {
    val config = Config.load().also { it.applyProxy() }
    val sheets = SheetsClient(config.googleApiKey, config.spreadsheetId, config.sheetRange)
    val repository = ScheduleRepository(sheets::fetchSnapshot, config.dataDir.resolve("snapshot.tsv"))
    TutorBot(
        api = TelegramBotApi(config.botToken, config.telegramApiUrl),
        slots = SlotsService(repository, config.earliestStart),
        users = UserSettingsStore(config.dataDir.resolve("users.json")),
        renderer = SlotsRenderer(),
        tutorId = config.tutorTelegramId,
        tutorContact = config.tutorContact,
    ).run()
    return 0
}

private fun fetch(opts: Options): Int {
    val config = Config.load().also { it.applyProxy() }
    val tsv = SheetsClient(config.googleApiKey, config.spreadsheetId, config.sheetRange).fetchSnapshot()
    val parsed = SnapshotReader.read(tsv)
    parsed.diagnostics.forEach { System.err.println("Warning: line ${it.line}: ${it.message}") }
    val out = opts.named["out"]
    if (out != null) File(out).writeText(tsv) else print(tsv)
    System.err.println("Slots: ${parsed.slots.size}, free: ${StatsCalculator.compute(parsed.slots).free}")
    return 0
}

/** Mirrors the C++ CLI exactly (same output, same exit codes) for the NFR-07 comparison. */
private fun report(opts: Options): Int {
    val input = opts.positional.firstOrNull() ?: run { System.err.println(USAGE); return 1 }
    val offset = opts.named["offset"]?.let { TimeShift.parseOffset(it) ?: run {
        System.err.println("Error: invalid offset '$it'"); return 1 } } ?: TimeShift.SOURCE_OFFSET
    val earliest = opts.named["earliest"]?.let { TimeShift.parseClock(it) ?: run {
        System.err.println("Error: invalid time '$it'"); return 1 } } ?: SlotsService.DEFAULT_EARLIEST_START
    val file = File(input)
    if (!file.canRead()) { System.err.println("Error: cannot open '$input'"); return 2 }
    val parsed = SnapshotReader.read(file.readText(Charsets.UTF_8))
    parsed.diagnostics.forEach { System.err.println("Warning: line ${it.line}: ${it.message}") }
    if (parsed.slots.isEmpty()) { System.err.println("Error: no valid slots in '$input'"); return 3 }
    val text = ReportWriter.write(IntervalBuilder.build(parsed.slots, offset, earliest),
        StatsCalculator.compute(parsed.slots), offset, earliest)
    val out = opts.named["out"]
    if (out != null) File(out).writeBytes(text.toByteArray()) else { System.out.write(text.toByteArray()); System.out.flush() }
    return 0
}

private fun render(opts: Options): Int {
    val input = opts.positional.firstOrNull() ?: run { System.err.println(USAGE); return 1 }
    val zone = ZoneResolver.parse(opts.named["zone"] ?: "Europe/Moscow")
        ?: run { System.err.println("Error: unknown zone"); return 1 }
    val parsed = SnapshotReader.read(File(input).readText(Charsets.UTF_8))
    val offset = ZoneResolver.offsetMinutes(zone, Instant.now())
    val earliest = SlotsService.DEFAULT_EARLIEST_START
    val view = SlotsView(IntervalBuilder.build(parsed.slots, offset, earliest), StatsCalculator.compute(parsed.slots),
        offset, earliest, zone.label, Instant.now(), fromCache = false)
    val out = opts.named["out"] ?: "slots.png"
    File(out).writeBytes(SlotsRenderer().render(view))
    println("Saved: $out")
    return 0
}
