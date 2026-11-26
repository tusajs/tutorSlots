package tutorslots.presentation

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tutorslots.core.samplesDir
import tutorslots.data.ScheduleRepository
import tutorslots.data.SheetsException
import tutorslots.data.UserSettingsStore
import tutorslots.domain.SlotsService
import tutorslots.domain.ZoneChoice
import java.io.File
import java.nio.file.Files

/** Records everything the bot "sends" instead of calling Telegram. */
class FakeBotApi : BotApi {
    val messages = mutableListOf<Pair<Long, String>>()
    val inlineKeyboards = mutableListOf<List<List<Button>>>()
    val photos = mutableListOf<Pair<Long, String>>()
    val callbacks = mutableListOf<String?>()

    override fun getUpdates(offset: Long, timeoutSeconds: Int) = emptyList<JsonObject>()
    override fun sendMessage(chatId: Long, text: String, inline: List<List<Button>>?, replyKeyboard: List<String>?) {
        messages += chatId to text
        inline?.let { inlineKeyboards += it }
    }
    override fun sendPhoto(chatId: Long, png: ByteArray, caption: String) {
        assertTrue(png.size > 1000 && png[1] == 'P'.code.toByte(), "not a PNG")
        photos += chatId to caption
    }
    override fun answerCallback(callbackId: String, text: String?) { callbacks += text }
    override fun sendTyping(chatId: Long) {}
    override fun setCommands(commands: List<Pair<String, String>>) {}
}

class TutorBotTest {
    private val tutor = 1000L
    private val client = 2000L
    private val dir = Files.createTempDirectory("bot")
    private val api = FakeBotApi()
    private val users = UserSettingsStore(dir.resolve("users.json"))
    private var googleUp = true

    private val bot = TutorBot(
        api = api,
        slots = SlotsService(ScheduleRepository(
            fetch = { if (googleUp) File(samplesDir(), "snapshot.tsv").readText() else throw SheetsException("offline") },
            snapshotFile = dir.resolve("snapshot.tsv"), log = {},
        ), log = {}),
        users = users,
        renderer = SlotsRenderer(),
        tutorId = tutor,
        tutorContact = "@tutor",
        log = {},
    )

    private fun text(from: Long, text: String) = bot.handle(Json.parseToJsonElement(
        """{"update_id":1,"message":{"chat":{"id":$from},"from":{"id":$from},"text":${Json.encodeToString(String.serializer(), text)}}}""",
    ).jsonObject)

    private fun press(from: Long, data: String) = bot.handle(Json.parseToJsonElement(
        """{"update_id":2,"callback_query":{"id":"cb","from":{"id":$from},"message":{"chat":{"id":$from}},"data":"$data"}}""",
    ).jsonObject)

    @Test fun startShowsAllZoneButtons() {
        text(client, "/start")
        val buttons = api.inlineKeyboards.single().flatten()
        assertEquals(12, buttons.size)  // 11 Russian zones + "other"
        assertEquals("Новосибирск (+7)", buttons.first { it.data == "tz:Asia/Novosibirsk" }.text)
    }

    @Test fun ac19_slotsWithoutZoneAsksForZone() {
        text(client, "/slots")
        assertTrue(api.photos.isEmpty())
        assertTrue(api.messages.single().second.startsWith("Сначала выберите часовой пояс"))
    }

    @Test fun choosingZoneSavesItAndSendsPicture() {
        press(client, "tz:Asia/Novosibirsk")
        assertEquals(ZoneChoice("Asia/Novosibirsk", "Новосибирск"), users.getZone(client))
        val caption = api.photos.single().second
        assertTrue(caption.contains("Новосибирск (UTC+07:00)"), caption)
        assertTrue(caption.contains("@tutor"))
    }

    @Test fun replyKeyboardButtonWorksLikeCommand() {
        users.setZone(client, ZoneChoice("Europe/Moscow", "Москва"))
        text(client, TutorBot.BTN_SLOTS)
        assertEquals(1, api.photos.size)
    }

    @Test fun customZoneFlow() {
        press(client, "tz:other")
        text(client, "марс")
        assertTrue(api.messages.last().second.startsWith("Не получилось распознать"))
        text(client, "+5:30")
        assertEquals("+05:30", users.getZone(client)?.id)
        assertTrue(api.photos.single().second.contains("UTC+05:30"))
    }

    @Test fun commandCancelsCustomZoneInput() {
        press(client, "tz:other")
        text(client, "/help")
        text(client, "+5")
        assertEquals(null, users.getZone(client))  // "+5" was not taken as a zone
    }

    @Test fun ac22_statsOnlyForTutor() {
        text(client, "/stats")
        assertEquals("Эта команда доступна только репетитору.", api.messages.single().second)
        text(tutor, "/stats")
        val stats = api.messages.last().second
        assertTrue(stats.contains("Загрузка недели: 59 %") && stats.contains("Свободно: 43 ч"), stats)
    }

    @Test fun ac21_googleDownShowsCachedDataWithWarning() {
        users.setZone(client, ZoneChoice("Europe/Moscow", "Москва"))
        text(client, "/slots")                              // fills the cache file
        googleUp = false
        val restarted = TutorBot(api, SlotsService(ScheduleRepository(
            { throw SheetsException("offline") }, dir.resolve("snapshot.tsv"), log = {}), log = {}),
            users, SlotsRenderer(), tutor, null, log = {})
        restarted.handle(Json.parseToJsonElement(
            """{"update_id":3,"message":{"chat":{"id":$client},"from":{"id":$client},"text":"/slots"}}""").jsonObject)
        assertTrue(api.photos.last().second.contains("Таблица сейчас недоступна"))
    }

    @Test fun noDataAtAllGivesFriendlyMessage() {
        googleUp = false
        users.setZone(client, ZoneChoice("Europe/Moscow", "Москва"))
        text(client, "/slots")
        assertEquals("Расписание временно недоступно, попробуйте чуть позже.", api.messages.last().second)
    }

    @Test fun malformedUpdateDoesNotCrash() {
        bot.handle(Json.parseToJsonElement("""{"update_id":9,"message":{"text":"/slots"}}""").jsonObject)
        assertTrue(api.messages.isEmpty())
    }
}
