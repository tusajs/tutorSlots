package tutorslots.domain

import tutorslots.core.ReportWriter
import tutorslots.core.TimeShift
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/** [id] is either an IANA zone ("Asia/Novosibirsk") or a fixed offset ("+05:30"). */
data class ZoneChoice(val id: String, val label: String)

/** Turns user input into a time zone and computes its UTC offset on a given date (FR-08, FR-09, FR-15). */
object ZoneResolver {
    /** Buttons shown by /start and /tz: Russian time zones from Kaliningrad to Kamchatka. */
    val presets = listOf(
        ZoneChoice("Europe/Kaliningrad", "Калининград"),
        ZoneChoice("Europe/Moscow", "Москва"),
        ZoneChoice("Europe/Samara", "Самара"),
        ZoneChoice("Asia/Yekaterinburg", "Екатеринбург"),
        ZoneChoice("Asia/Omsk", "Омск"),
        ZoneChoice("Asia/Novosibirsk", "Новосибирск"),
        ZoneChoice("Asia/Irkutsk", "Иркутск"),
        ZoneChoice("Asia/Yakutsk", "Якутск"),
        ZoneChoice("Asia/Vladivostok", "Владивосток"),
        ZoneChoice("Asia/Magadan", "Магадан"),
        ZoneChoice("Asia/Kamchatka", "Камчатка"),
    )

    /** Extra cities a user may type by name. */
    private val cities: Map<String, ZoneChoice> = (presets + listOf(
        ZoneChoice("Europe/Moscow", "Санкт-Петербург"),
        ZoneChoice("Europe/Moscow", "Казань"),
        ZoneChoice("Europe/Moscow", "Нижний Новгород"),
        ZoneChoice("Europe/Volgograd", "Волгоград"),
        ZoneChoice("Asia/Yekaterinburg", "Челябинск"),
        ZoneChoice("Asia/Yekaterinburg", "Уфа"),
        ZoneChoice("Asia/Yekaterinburg", "Пермь"),
        ZoneChoice("Asia/Yekaterinburg", "Тюмень"),
        ZoneChoice("Asia/Krasnoyarsk", "Красноярск"),
        ZoneChoice("Asia/Novosibirsk", "Томск"),
        ZoneChoice("Asia/Barnaul", "Барнаул"),
        ZoneChoice("Asia/Vladivostok", "Хабаровск"),
        ZoneChoice("Europe/Minsk", "Минск"),
        ZoneChoice("Asia/Almaty", "Алматы"),
        ZoneChoice("Asia/Tashkent", "Ташкент"),
        ZoneChoice("Asia/Bishkek", "Бишкек"),
        ZoneChoice("Asia/Tbilisi", "Тбилиси"),
        ZoneChoice("Asia/Yerevan", "Ереван"),
        ZoneChoice("Asia/Baku", "Баку"),
        ZoneChoice("Europe/Istanbul", "Стамбул"),
        ZoneChoice("Asia/Dubai", "Дубай"),
        ZoneChoice("Europe/Berlin", "Берлин"),
        ZoneChoice("Europe/London", "Лондон"),
        ZoneChoice("America/New_York", "Нью-Йорк"),
    )).associateBy { normalize(it.label) }

    private fun normalize(s: String) = s.trim().lowercase().replace('ё', 'е').replace(' ', '-')

    fun presetById(id: String): ZoneChoice? = presets.firstOrNull { it.id == id }

    fun parse(input: String): ZoneChoice? {
        val text = input.trim()
        if (text.isEmpty()) return null
        cities[normalize(text)]?.let { return it }
        TimeShift.parseOffset(text.replace("GMT", "UTC"))?.let {
            val formatted = ReportWriter.formatOffset(it)
            return ZoneChoice(formatted.removePrefix("UTC"), formatted)
        }
        if ('/' in text) {
            try {
                val zone = ZoneId.of(text)
                if (TimeShift.isValidOffset(offsetMinutes(zone, Instant.now()))) return ZoneChoice(zone.id, zone.id)
            } catch (_: DateTimeException) {
            }
        }
        return null
    }

    fun offsetMinutes(choice: ZoneChoice, at: Instant): Int =
        TimeShift.parseOffset(choice.id) ?: offsetMinutes(ZoneId.of(choice.id), at)

    private fun offsetMinutes(zone: ZoneId, at: Instant) = zone.rules.getOffset(at).totalSeconds / 60
}
