package tutorslots.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.roundToInt

class SheetsException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads the schedule sheet through Google Sheets API v4 and converts it to snapshot TSV (FR-01, FR-04). */
class SheetsClient(
    private val apiKey: String,
    private val spreadsheetId: String,
    private val range: String?,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    fun fetchSnapshot(): String = SheetsParser.toTsv(fetchJson())

    fun fetchJson(): String {
        val fields = "sheets.data(startRow,startColumn,rowData.values(formattedValue," +
            "effectiveFormat(backgroundColor,backgroundColorStyle)))"
        val query = buildList {
            add("includeGridData=true")
            add("fields=" + enc(fields))
            if (range != null) add("ranges=" + enc(range))
            add("key=" + enc(apiKey))
        }.joinToString("&")
        val request = HttpRequest.newBuilder(URI("https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId?$query"))
            .timeout(Duration.ofSeconds(20)).GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw SheetsException("Cannot reach Google Sheets: ${e.message}", e)
        }
        when (response.statusCode()) {
            200 -> return response.body()
            400 -> throw SheetsException("Google rejected the request (400): check SHEET_RANGE and the API key")
            403 -> throw SheetsException("Access denied (403): share the sheet as \"Anyone with the link\" and enable Sheets API for the key")
            404 -> throw SheetsException("Spreadsheet not found (404): check SPREADSHEET")
            else -> throw SheetsException("Google Sheets returned HTTP ${response.statusCode()}")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}

/**
 * Pure conversion of the API JSON into snapshot TSV. Columns are found by the day names in the
 * header row (so their order does not matter), time is taken from the first column of each row.
 */
object SheetsParser {
    private val dayAliases = mapOf(
        "MON" to listOf("понедельник", "пн", "monday", "mon"),
        "TUE" to listOf("вторник", "вт", "tuesday", "tue"),
        "WED" to listOf("среда", "ср", "wednesday", "wed"),
        "THU" to listOf("четверг", "чт", "thursday", "thu"),
        "FRI" to listOf("пятница", "пт", "friday", "fri"),
        "SAT" to listOf("суббота", "сб", "saturday", "sat"),
        "SUN" to listOf("воскресенье", "вс", "sunday", "sun"),
    )
    private val timeRegex = Regex("""^(\d{1,2}):(\d{2})(:\d{2})?$""")

    private class Cell(val text: String, val color: String)

    fun toTsv(json: String): String {
        val root = Json.parseToJsonElement(json).jsonObject
        val grid = root["sheets"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("data")?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw SheetsException("No sheet data in the Google response")
        val rows: List<List<Cell>> = (grid["rowData"] as? JsonArray ?: JsonArray(emptyList())).map { row ->
            (row.jsonObject["values"] as? JsonArray ?: JsonArray(emptyList())).map { parseCell(it.jsonObject) }
        }

        val headerIndex = rows.indexOfFirst { row -> row.count { dayCode(it.text) != null } >= 3 }
        if (headerIndex < 0) throw SheetsException("Header row with weekday names (Monday ... Sunday in Russian) not found")
        val columns: Map<Int, String> = rows[headerIndex].withIndex()
            .mapNotNull { (i, cell) -> dayCode(cell.text)?.let { i to it } }.toMap()

        return buildString {
            append("# Snapshot from Google Sheets (source timezone: Europe/Moscow)\n")
            append("day\ttime\tcolor\ttext\n")
            for (row in rows.drop(headerIndex + 1)) {
                val time = row.firstOrNull()?.let { normalizeTime(it.text) } ?: continue
                for ((col, day) in columns) {
                    val cell = row.getOrNull(col) ?: Cell("", "")
                    append(day).append('\t').append(time).append('\t').append(cell.color).append('\t')
                        .append(cell.text).append('\n')
                }
            }
        }
    }

    fun dayCode(text: String): String? {
        val t = text.trim().lowercase()
        return dayAliases.entries.firstOrNull { t in it.value }?.key
    }

    /** "9:00", "09:00", "9:00:00" -> "9:00"; anything else -> null. */
    fun normalizeTime(text: String): String? {
        val m = timeRegex.matchEntire(text.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        if (h > 23 || m.groupValues[2].toInt() > 59) return null
        return "$h:${m.groupValues[2]}"
    }

    private fun parseCell(cell: JsonObject): Cell {
        // Tabs and line breaks inside a cell would break the TSV format.
        val text = cell["formattedValue"]?.jsonPrimitive?.contentOrNull.orEmpty()
            .replace(Regex("[\t\r\n]+"), " ").trim()
        val format = cell["effectiveFormat"] as? JsonObject
        val rgb = (format?.get("backgroundColorStyle") as? JsonObject)?.get("rgbColor") as? JsonObject
            ?: format?.get("backgroundColor") as? JsonObject
        return Cell(text, rgb?.let(::toHex).orEmpty())
    }

    /** Google omits zero channels: pure green is {"green": 1}. */
    fun toHex(color: JsonObject): String {
        fun channel(name: String): Int {
            val v = color[name]?.jsonPrimitive?.doubleOrNull ?: 0.0
            return (v.coerceIn(0.0, 1.0) * 255).roundToInt()
        }
        return "#%02X%02X%02X".format(channel("red"), channel("green"), channel("blue"))
    }
}
