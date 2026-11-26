package tutorslots.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tutorslots.core.IntervalBuilder
import tutorslots.core.ReportWriter
import tutorslots.core.SnapshotReader
import tutorslots.core.StatsCalculator
import tutorslots.core.samplesDir
import tutorslots.domain.ZoneChoice
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private fun resource(name: String): String =
    listOf(File("src/test/resources/$name"), File("app-kotlin/src/test/resources/$name")).first { it.exists() }.readText()

/** A clock the test can move forward by hand. */
class MutableClock(var now: Instant) : Clock() {
    override fun instant() = now
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
}

class SheetsParserTest {
    @Test fun convertsApiResponseToSameReportAsReferenceSnapshot() {
        val tsv = SheetsParser.toTsv(resource("sheets-response.json"))
        val parsed = SnapshotReader.read(tsv)
        assertTrue(parsed.diagnostics.isEmpty(), parsed.diagnostics.toString())
        assertEquals(105, parsed.slots.size)
        val report = ReportWriter.write(IntervalBuilder.build(parsed.slots, 420, 480), StatsCalculator.compute(parsed.slots), 420, 480)
        assertEquals(File(samplesDir(), "expected_utc+07.txt").readText(), report)
    }

    @Test fun missingChannelsMeanZero() {
        assertEquals("#00FF00", SheetsParser.toHex(Json.parseToJsonElement("""{"green": 1}""").jsonObject))
        assertEquals("#000000", SheetsParser.toHex(Json.parseToJsonElement("{}").jsonObject))
        assertEquals("#B7E1CD", SheetsParser.toHex(
            Json.parseToJsonElement("""{"red":0.7176471,"green":0.88235295,"blue":0.8039216}""").jsonObject))
    }

    @Test fun normalizesTime() {
        assertEquals("9:00", SheetsParser.normalizeTime("09:00:00"))
        assertEquals("23:00", SheetsParser.normalizeTime(" 23:00 "))
        assertNull(SheetsParser.normalizeTime("24:00"))
        assertNull(SheetsParser.normalizeTime("итого"))
        assertNull(SheetsParser.normalizeTime(""))
    }

    @Test fun recognizesDayNamesInAnyCase() {
        assertEquals("MON", SheetsParser.dayCode(" Понедельник "))
        assertEquals("SUN", SheetsParser.dayCode("вс"))
        assertNull(SheetsParser.dayCode("выходной"))
    }

    @Test fun columnsAreMatchedByHeaderNotByPosition() {
        val json = """{"sheets":[{"data":[{"rowData":[
            {"values":[{},{"formattedValue":"среда"},{"formattedValue":"понедельник"},{"formattedValue":"вторник"}]},
            {"values":[{"formattedValue":"10:00"},
                       {"effectiveFormat":{"backgroundColor":{"red":1}}},
                       {"effectiveFormat":{"backgroundColor":{"green":1}}},
                       {"formattedValue":"Аня\tЕГЭ\nдоп"}]}
        ]}]}]}"""
        val slots = SnapshotReader.read(SheetsParser.toTsv(json)).slots.associateBy { it.day }
        assertEquals(tutorslots.core.Status.BUSY, slots[2]!!.status)   // Wednesday is red
        assertEquals(tutorslots.core.Status.FREE, slots[0]!!.status)   // Monday is green
        assertEquals("Аня ЕГЭ доп", slots[1]!!.text)                   // tab and newline removed
    }

    @Test fun failsWithoutHeaderRow() {
        val error = runCatching { SheetsParser.toTsv("""{"sheets":[{"data":[{"rowData":[]}]}]}""") }.exceptionOrNull()
        assertTrue(error is SheetsException)
    }
}

class ConfigTest {
    @Test fun extractsIdFromLink() {
        assertEquals("1AbC-d_9", Config.extractSpreadsheetId("https://docs.google.com/spreadsheets/d/1AbC-d_9/edit#gid=0"))
        assertEquals("1AbC-d_9", Config.extractSpreadsheetId("1AbC-d_9"))
    }

    @Test fun readsEnvFileAndEnvironmentWins() {
        val file = Files.createTempFile("bot", ".env").toFile()
        file.writeText("# comment\nBOT_TOKEN=from_file\nGOOGLE_API_KEY=\"key\"\nSPREADSHEET=abc\nTUTOR_ID=42\n")
        val config = Config.load(file, mapOf("BOT_TOKEN" to "from_env"))
        assertEquals("from_env", config.botToken)
        assertEquals("key", config.googleApiKey)
        assertEquals(42L, config.tutorTelegramId)
        assertEquals("https://api.telegram.org", config.telegramApiUrl)
        assertEquals(480, config.earliestStart)
    }

    @Test fun earliestStartCanBeChanged() {
        val base = mapOf("BOT_TOKEN" to "t", "GOOGLE_API_KEY" to "k", "SPREADSHEET" to "s")
        assertEquals(9 * 60, Config.fromMap(base + ("EARLIEST_START" to "9:00")).earliestStart)
        assertTrue(runCatching { Config.fromMap(base + ("EARLIEST_START" to "утро")) }.isFailure)
    }

    @Test fun missingRequiredValueIsReported() {
        val error = runCatching { Config.fromMap(mapOf("BOT_TOKEN" to "x")) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("GOOGLE_API_KEY"))
    }
}

class ScheduleRepositoryTest {
    private val start = Instant.parse("2026-11-19T12:00:00Z")

    @Test fun cachesForFiveMinutes() {
        var calls = 0
        val clock = MutableClock(start)
        val repo = ScheduleRepository({ calls++; "tsv$calls" }, Files.createTempDirectory("ts").resolve("s.tsv"), clock = clock)
        assertEquals("tsv1", repo.get().tsv)
        clock.now = start.plus(Duration.ofMinutes(4))
        assertEquals("tsv1", repo.get().tsv)
        clock.now = start.plus(Duration.ofMinutes(6))
        assertEquals("tsv2", repo.get().tsv)
        assertEquals(2, calls)
    }

    @Test fun ac21_fallsBackToMemoryThenDisk() {
        val file = Files.createTempDirectory("ts").resolve("snapshot.tsv")
        val clock = MutableClock(start)
        var fail = false
        val fetch = { if (fail) throw SheetsException("offline") else "MON\t9:00\t#00FF00\n" }
        ScheduleRepository(fetch, file, clock = clock, log = {}).get()

        fail = true
        clock.now = start.plus(Duration.ofHours(1))
        // A fresh repository (bot restarted) has nothing in memory and must read the file.
        val restarted = ScheduleRepository(fetch, file, clock = clock, log = {})
        val snapshot = restarted.get()
        assertTrue(snapshot.fromCache)
        assertEquals(start, snapshot.loadedAt)
        assertEquals(1, SnapshotReader.read(snapshot.tsv).slots.size)
    }

    @Test fun throwsWhenNothingAvailable() {
        val repo = ScheduleRepository({ throw SheetsException("offline") }, Files.createTempDirectory("ts").resolve("x.tsv"), log = {})
        assertTrue(runCatching { repo.get() }.exceptionOrNull() is SheetsException)
    }
}

class UserSettingsStoreTest {
    @Test fun ac20_zoneSurvivesRestart() {
        val file = Files.createTempDirectory("ts").resolve("users.json")
        UserSettingsStore(file).setZone(123L, ZoneChoice("Asia/Novosibirsk", "Новосибирск"))
        val restarted = UserSettingsStore(file)
        assertEquals(ZoneChoice("Asia/Novosibirsk", "Новосибирск"), restarted.getZone(123L))
        assertNull(restarted.getZone(456L))
        assertFalse(Files.exists(file.resolveSibling("users.json.tmp")))
    }
}
