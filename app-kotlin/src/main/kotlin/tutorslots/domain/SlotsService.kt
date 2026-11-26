package tutorslots.domain

import tutorslots.core.IntervalBuilder
import tutorslots.core.SnapshotReader
import tutorslots.core.StatsCalculator
import tutorslots.data.ScheduleRepository
import java.time.Clock

/** Glues data and core together. Knows nothing about Telegram. */
class SlotsService(
    private val repository: ScheduleRepository,
    private val earliestStart: Int = DEFAULT_EARLIEST_START,
    private val clock: Clock = Clock.systemUTC(),
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    fun viewFor(zone: ZoneChoice): SlotsView {
        val snapshot = repository.get()
        val parsed = SnapshotReader.read(snapshot.tsv)
        parsed.diagnostics.forEach { log("snapshot line ${it.line}: ${it.message}") }
        check(parsed.slots.isNotEmpty()) { "No valid slots in the schedule" }
        val offset = ZoneResolver.offsetMinutes(zone, clock.instant())
        return SlotsView(
            plan = IntervalBuilder.build(parsed.slots, offset, earliestStart),
            stats = StatsCalculator.compute(parsed.slots),
            offsetMinutes = offset,
            earliestStart = earliestStart,
            zoneLabel = zone.label,
            loadedAt = snapshot.loadedAt,
            fromCache = snapshot.fromCache,
        )
    }

    companion object {
        const val DEFAULT_EARLIEST_START = 8 * 60  // 08:00 client time
    }
}
