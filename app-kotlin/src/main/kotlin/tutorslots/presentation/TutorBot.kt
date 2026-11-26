package tutorslots.presentation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import tutorslots.core.ReportWriter
import tutorslots.data.UserSettingsStore
import tutorslots.domain.SlotsService
import tutorslots.domain.SlotsView
import tutorslots.domain.ZoneChoice
import tutorslots.domain.ZoneResolver
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Telegram dialog: commands, time zone buttons, sending the picture (FR-14 … FR-20). */
class TutorBot(
    private val api: BotApi,
    private val slots: SlotsService,
    private val users: UserSettingsStore,
    private val renderer: SlotsRenderer,
    private val tutorId: Long?,
    private val tutorContact: String?,
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    private val awaitingCustomZone = mutableSetOf<Long>()

    companion object {
        const val BTN_SLOTS = "🗓 Свободные окна"
        const val BTN_ZONE = "🌍 Часовой пояс"
        private const val ZONE_PREFIX = "tz:"
        private const val ZONE_OTHER = "tz:other"
        val COMMANDS = listOf(
            "slots" to "Свободные окна по моему времени",
            "tz" to "Выбрать часовой пояс",
            "help" to "Как пользоваться ботом",
        )
    }

    /** Long polling loop. Never returns; errors are logged and retried. */
    fun run() {
        runCatching { api.setCommands(COMMANDS) }.onFailure { log("setMyCommands: ${it.message}") }
        var offset = 0L
        log("Bot started")
        while (true) {
            try {
                for (update in api.getUpdates(offset, 30)) {
                    offset = update["update_id"]!!.jsonPrimitive.long + 1
                    handle(update)
                }
            } catch (e: Exception) {
                log("Telegram connection error: ${e.message}; retrying in 5 s")
                Thread.sleep(5000)
            }
        }
    }

    fun handle(update: JsonObject) {
        try {
            update["message"]?.jsonObject?.let(::onMessage)
            update["callback_query"]?.jsonObject?.let(::onCallback)
        } catch (e: Exception) {
            log("Failed to handle update ${update["update_id"]}: $e")
        }
    }

    private fun onMessage(message: JsonObject) {
        val chatId = message["chat"]!!.jsonObject["id"]!!.jsonPrimitive.long
        val userId = message["from"]?.jsonObject?.get("id")?.jsonPrimitive?.long ?: chatId
        val text = message["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: return
        val command = text.takeIf { it.startsWith("/") }?.substringBefore(' ')?.substringBefore('@')?.lowercase()

        if (command == null && chatId in awaitingCustomZone && text != BTN_SLOTS && text != BTN_ZONE) {
            onCustomZone(chatId, userId, text)
            return
        }
        awaitingCustomZone.remove(chatId)
        when {
            command == "/start" -> {
                api.sendMessage(chatId,
                    "Привет! Я покажу свободные часы репетитора по вашему местному времени.\n\n" +
                        "Сначала выберите свой часовой пояс:", inline = zoneKeyboard())
                api.sendMessage(chatId, "Кнопки внизу всегда под рукой 👇", replyKeyboard = listOf(BTN_SLOTS, BTN_ZONE))
            }
            command == "/slots" || text == BTN_SLOTS -> sendSlots(chatId, userId)
            command == "/tz" || text == BTN_ZONE -> askZone(chatId, userId)
            command == "/stats" -> sendStats(chatId, userId)
            command == "/id" -> api.sendMessage(chatId, "Ваш Telegram ID: $userId")
            else -> sendHelp(chatId)
        }
    }

    private fun onCallback(callback: JsonObject) {
        val id = callback["id"]!!.jsonPrimitive.content
        val data = callback["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val userId = callback["from"]!!.jsonObject["id"]!!.jsonPrimitive.long
        val chatId = callback["message"]?.jsonObject?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.long ?: userId
        when {
            data == ZONE_OTHER -> {
                api.answerCallback(id)
                awaitingCustomZone.add(chatId)
                api.sendMessage(chatId,
                    "Напишите город (например, «Алматы»), смещение от UTC (+5:30, -4) " +
                        "или название пояса (Asia/Tokyo).")
            }
            data.startsWith(ZONE_PREFIX) -> {
                val zone = ZoneResolver.presetById(data.removePrefix(ZONE_PREFIX))
                if (zone == null) {
                    api.answerCallback(id, "Неизвестный пояс")
                    return
                }
                api.answerCallback(id, "Пояс: ${zone.label}")
                saveZoneAndShow(chatId, userId, zone)
            }
            else -> api.answerCallback(id)
        }
    }

    private fun onCustomZone(chatId: Long, userId: Long, text: String) {
        val zone = ZoneResolver.parse(text)
        if (zone == null) {
            api.sendMessage(chatId,
                "Не получилось распознать «$text». Примеры: Казань, +5:30, UTC-4, Asia/Tokyo. " +
                    "Смещение должно быть от -12 до +14 часов.")
            return
        }
        awaitingCustomZone.remove(chatId)
        saveZoneAndShow(chatId, userId, zone)
    }

    private fun saveZoneAndShow(chatId: Long, userId: Long, zone: ZoneChoice) {
        users.setZone(userId, zone)
        api.sendMessage(chatId, "Часовой пояс сохранён: ${zone.label}. Готовлю расписание…",
            replyKeyboard = listOf(BTN_SLOTS, BTN_ZONE))
        sendSlots(chatId, userId)
    }

    private fun askZone(chatId: Long, userId: Long) {
        val current = users.getZone(userId)?.let { "Сейчас выбран: ${it.label}.\n" }.orEmpty()
        api.sendMessage(chatId, "${current}Выберите часовой пояс:", inline = zoneKeyboard())
    }

    private fun sendSlots(chatId: Long, userId: Long) {
        val zone = users.getZone(userId)
        if (zone == null) {  // AC-19
            api.sendMessage(chatId, "Сначала выберите часовой пояс — тогда я покажу время по-вашему:", inline = zoneKeyboard())
            return
        }
        val view = loadView(chatId, zone) ?: return
        api.sendTyping(chatId)
        api.sendPhoto(chatId, renderer.render(view), caption(view))
    }

    private fun sendStats(chatId: Long, userId: Long) {
        if (tutorId == null || userId != tutorId) {  // AC-22
            api.sendMessage(chatId, "Эта команда доступна только репетитору.")
            return
        }
        val view = loadView(chatId, ZoneResolver.presets[1]) ?: return
        val s = view.stats
        api.sendMessage(chatId,
            "📊 Загрузка недели: ${s.loadPercent} %\n" +
                "Занято: ${s.busy} ч\nСвободно: ${s.free} ч\nВсего слотов: ${s.free + s.busy}\n" +
                "Данные от ${formatMoscow(view)}" + if (view.fromCache) " (таблица сейчас недоступна)" else "")
    }

    private fun loadView(chatId: Long, zone: ZoneChoice): SlotsView? = try {
        slots.viewFor(zone)
    } catch (e: Exception) {  // FR-23
        log("Schedule unavailable: ${e.message}")
        api.sendMessage(chatId, "Расписание временно недоступно, попробуйте чуть позже.")
        null
    }

    private fun caption(view: SlotsView): String = buildString {
        append("Свободные окна по времени: ${view.zoneLabel} (${ReportWriter.formatOffset(view.offsetMinutes)}).")
        if (view.fromCache) append("\n⚠️ Таблица сейчас недоступна, показаны данные от ${formatMoscow(view)} МСК.")
        if (tutorContact != null) append("\nЧтобы записаться, напишите $tutorContact")
    }

    private fun formatMoscow(view: SlotsView) =
        DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.of("Europe/Moscow")).format(view.loadedAt)

    private fun sendHelp(chatId: Long) {
        api.sendMessage(chatId,
            "Я показываю свободные часы репетитора по вашему местному времени.\n\n" +
                "/slots — картинка со свободными окнами\n" +
                "/tz — сменить часовой пояс\n" +
                "/help — эта справка",
            replyKeyboard = listOf(BTN_SLOTS, BTN_ZONE))
    }

    private fun zoneKeyboard(): List<List<Button>> {
        val buttons = ZoneResolver.presets.map { zone ->
            val hours = ReportWriter.formatOffset(ZoneResolver.offsetMinutes(zone, java.time.Instant.now()))
                .removePrefix("UTC").removeSuffix(":00").replace("+0", "+")
            Button("${zone.label} ($hours)", ZONE_PREFIX + zone.id)
        } + Button("Другой…", ZONE_OTHER)
        return buttons.chunked(2)
    }
}
