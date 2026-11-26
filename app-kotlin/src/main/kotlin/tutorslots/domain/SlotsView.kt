package tutorslots.domain

import tutorslots.core.DayPlan
import tutorslots.core.Stats
import java.time.Instant

/** Everything the renderer needs: free intervals already in the client's time. */
data class SlotsView(
    val plan: List<DayPlan>,
    val stats: Stats,
    val offsetMinutes: Int,
    val earliestStart: Int,    // slots starting earlier (client time) are hidden
    val zoneLabel: String,     // e.g. "Новосибирск"
    val loadedAt: Instant,     // when the schedule was fetched from Google Sheets
    val fromCache: Boolean,    // true if Google was unavailable and an old snapshot was used
)
