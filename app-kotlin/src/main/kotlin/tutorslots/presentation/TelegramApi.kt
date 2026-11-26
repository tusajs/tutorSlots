package tutorslots.presentation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** One inline button: text shown to the user and data sent back in a callback query. */
data class Button(val text: String, val data: String)

/** The few Telegram Bot API methods the bot needs. An interface, so the bot logic can be tested with a fake. */
interface BotApi {
    fun getUpdates(offset: Long, timeoutSeconds: Int): List<JsonObject>
    fun sendMessage(chatId: Long, text: String, inline: List<List<Button>>? = null, replyKeyboard: List<String>? = null)
    fun sendPhoto(chatId: Long, png: ByteArray, caption: String)
    fun answerCallback(callbackId: String, text: String? = null)
    fun sendTyping(chatId: Long)
    fun setCommands(commands: List<Pair<String, String>>)
}

class TelegramApiException(message: String) : Exception(message)

/** Plain HTTP implementation of the Bot API (https://core.telegram.org/bots/api), no extra libraries. */
class TelegramBotApi(token: String, baseUrl: String = "https://api.telegram.org") : BotApi {
    private val base = "$baseUrl/bot$token"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override fun getUpdates(offset: Long, timeoutSeconds: Int): List<JsonObject> =
        call("getUpdates", obj("offset" to offset, "timeout" to timeoutSeconds), timeoutSeconds + 15L)
            .jsonArray.map { it.jsonObject }

    override fun sendMessage(chatId: Long, text: String, inline: List<List<Button>>?, replyKeyboard: List<String>?) {
        val markup: JsonElement? = when {
            inline != null -> obj("inline_keyboard" to JsonArray(inline.map { row ->
                JsonArray(row.map { obj("text" to it.text, "callback_data" to it.data) })
            }))
            replyKeyboard != null -> obj(
                "keyboard" to JsonArray(listOf(JsonArray(replyKeyboard.map { obj("text" to it) }))),
                "resize_keyboard" to true, "is_persistent" to true,
            )
            else -> null
        }
        val params = mutableMapOf<String, JsonElement>("chat_id" to JsonPrimitive(chatId), "text" to JsonPrimitive(text))
        if (markup != null) params["reply_markup"] = markup
        call("sendMessage", JsonObject(params))
    }

    override fun sendPhoto(chatId: Long, png: ByteArray, caption: String) {
        val boundary = "----tutorslots" + UUID.randomUUID()
        val body = ByteArrayOutputStream()
        fun part(header: String, content: ByteArray) {
            body.write("--$boundary\r\n$header\r\n\r\n".toByteArray())
            body.write(content)
            body.write("\r\n".toByteArray())
        }
        part("Content-Disposition: form-data; name=\"chat_id\"", chatId.toString().toByteArray())
        part("Content-Disposition: form-data; name=\"caption\"", caption.toByteArray(Charsets.UTF_8))
        part("Content-Disposition: form-data; name=\"photo\"; filename=\"slots.png\"\r\nContent-Type: image/png", png)
        body.write("--$boundary--\r\n".toByteArray())
        val request = HttpRequest.newBuilder(URI("$base/sendPhoto"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build()
        parse(http.send(request, HttpResponse.BodyHandlers.ofString()).body())
    }

    override fun answerCallback(callbackId: String, text: String?) {
        val params = mutableMapOf<String, JsonElement>("callback_query_id" to JsonPrimitive(callbackId))
        if (text != null) params["text"] = JsonPrimitive(text)
        call("answerCallbackQuery", JsonObject(params))
    }

    override fun sendTyping(chatId: Long) {
        call("sendChatAction", obj("chat_id" to chatId, "action" to "upload_photo"))
    }

    override fun setCommands(commands: List<Pair<String, String>>) {
        call("setMyCommands", obj("commands" to JsonArray(commands.map { (c, d) -> obj("command" to c, "description" to d) })))
    }

    private fun call(method: String, params: JsonObject, timeoutSeconds: Long = 30): JsonElement {
        val request = HttpRequest.newBuilder(URI("$base/$method"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(params.toString(), Charsets.UTF_8))
            .build()
        return parse(http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)).body())
    }

    private fun parse(body: String): JsonElement {
        val json = Json.parseToJsonElement(body).jsonObject
        if (json["ok"]?.jsonPrimitive?.boolean != true)
            throw TelegramApiException(json["description"]?.jsonPrimitive?.content ?: "Telegram error")
        return json["result"] ?: JsonPrimitive(true)
    }

    private fun obj(vararg pairs: Pair<String, Any>) = JsonObject(pairs.associate { (k, v) ->
        k to when (v) {
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> error("unsupported $v")
        }
    })
}
