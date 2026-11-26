package tutorslots.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class ZoneResolverTest {
    private val winter = Instant.parse("2026-01-15T12:00:00Z")
    private val summer = Instant.parse("2026-07-15T12:00:00Z")

    @Test fun presetsCoverRussiaFromPlusTwoToPlusTwelve() {
        val offsets = ZoneResolver.presets.map { ZoneResolver.offsetMinutes(it, winter) / 60 }
        assertEquals((2..12).toList(), offsets)
    }

    @Test fun parsesCityNames() {
        assertEquals("Asia/Novosibirsk", ZoneResolver.parse("новосибирск")?.id)
        assertEquals("Europe/Moscow", ZoneResolver.parse("  Санкт Петербург ")?.id)
        assertEquals("Asia/Almaty", ZoneResolver.parse("Алматы")?.id)
    }

    @Test fun parsesOffsets() {
        assertEquals(ZoneChoice("+05:30", "UTC+05:30"), ZoneResolver.parse("+5:30"))
        assertEquals(ZoneChoice("-04:00", "UTC-04:00"), ZoneResolver.parse("GMT-4"))
        assertEquals(420, ZoneResolver.offsetMinutes(ZoneResolver.parse("UTC+7")!!, winter))
    }

    @Test fun parsesIanaNames() {
        assertEquals("Asia/Tokyo", ZoneResolver.parse("Asia/Tokyo")?.id)
    }

    @Test fun ac12_rejectsGarbage() {
        for (s in listOf("", "абракадабра", "+15", "Mars/Olympus", "7")) assertNull(ZoneResolver.parse(s), s)
    }

    @Test fun daylightSavingIsTakenIntoAccount() {
        val berlin = ZoneResolver.parse("Берлин")!!
        assertEquals(60, ZoneResolver.offsetMinutes(berlin, winter))
        assertEquals(120, ZoneResolver.offsetMinutes(berlin, summer))
        val moscow = ZoneResolver.parse("Москва")!!
        assertEquals(180, ZoneResolver.offsetMinutes(moscow, summer))  // no DST in Moscow
    }
}
