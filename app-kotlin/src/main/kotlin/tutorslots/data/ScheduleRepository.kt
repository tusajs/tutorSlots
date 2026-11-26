package tutorslots.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class Snapshot(val tsv: String, val loadedAt: Instant, val fromCache: Boolean)

/**
 * Caches the schedule for [ttl] (FR-03). If Google is unavailable, falls back to the last
 * successful snapshot — first from memory, then from [snapshotFile] on disk.
 */
class ScheduleRepository(
    private val fetch: () -> String,
    private val snapshotFile: Path,
    private val ttl: Duration = Duration.ofMinutes(5),
    private val clock: Clock = Clock.systemUTC(),
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    private var cached: Snapshot? = null

    @Synchronized
    fun get(): Snapshot {
        val now = clock.instant()
        cached?.let { if (Duration.between(it.loadedAt, now) < ttl) return it }
        return try {
            val tsv = fetch()
            save(tsv, now)
            Snapshot(tsv, now, fromCache = false).also { cached = it }
        } catch (e: Exception) {
            log("Schedule fetch failed: ${e.message}")
            cached?.copy(fromCache = true) ?: loadFromDisk() ?: throw e
        }
    }

    private fun save(tsv: String, at: Instant) {
        Files.createDirectories(snapshotFile.toAbsolutePath().parent)
        val tmp = snapshotFile.resolveSibling(snapshotFile.fileName.toString() + ".tmp")
        Files.writeString(tmp, "# loadedAt=$at\n$tsv")
        Files.move(tmp, snapshotFile, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun loadFromDisk(): Snapshot? {
        if (!Files.exists(snapshotFile)) return null
        val text = Files.readString(snapshotFile)
        val loadedAt = Regex("^# loadedAt=(\\S+)").find(text)?.groupValues?.get(1)
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: Files.getLastModifiedTime(snapshotFile).toInstant()
        return Snapshot(text, loadedAt, fromCache = true).also { cached = it }
    }
}
