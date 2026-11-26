package tutorslots.presentation

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/** Runs TelegramBotApi against a local fake server to check the exact HTTP requests. */
class TelegramBotApiTest {
    private class Recorded(val path: String, val contentType: String, val body: ByteArray)

    private fun withServer(reply: (String) -> String, block: (TelegramBotApi, MutableList<Recorded>) -> Unit) {
        val recorded = mutableListOf<Recorded>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            recorded += Recorded(ex.requestURI.path, ex.requestHeaders.getFirst("Content-Type"), ex.requestBody.readBytes())
            val bytes = reply(ex.requestURI.path).toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(TelegramBotApi("123:ABC", "http://127.0.0.1:${server.address.port}"), recorded)
        } finally {
            server.stop(0)
        }
    }

    @Test fun sendMessageWithInlineKeyboard() = withServer({ """{"ok":true,"result":{}}""" }) { api, rec ->
        api.sendMessage(42, "Привет", inline = listOf(listOf(Button("Москва", "tz:Europe/Moscow"))))
        val req = rec.single()
        assertEquals("/bot123:ABC/sendMessage", req.path)
        val json = Json.parseToJsonElement(req.body.decodeToString()).jsonObject
        assertEquals("Привет", json["text"]!!.jsonPrimitive.content)
        val button = json["reply_markup"]!!.jsonObject["inline_keyboard"]!!.jsonArray[0].jsonArray[0].jsonObject
        assertEquals("tz:Europe/Moscow", button["callback_data"]!!.jsonPrimitive.content)
    }

    @Test fun sendPhotoIsMultipart() = withServer({ """{"ok":true,"result":{}}""" }) { api, rec ->
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        api.sendPhoto(42, png, "Окна")
        val req = rec.single()
        assertTrue(req.contentType.startsWith("multipart/form-data; boundary="))
        val body = String(req.body, Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"photo\"; filename=\"slots.png\""))
        assertTrue(body.contains(String(png, Charsets.ISO_8859_1)))
        assertTrue(String(req.body, Charsets.UTF_8).contains("Окна"))
    }

    @Test fun getUpdatesReturnsResultArray() = withServer({ """{"ok":true,"result":[{"update_id":7}]}""" }) { api, _ ->
        assertEquals(7, api.getUpdates(0, 0).single()["update_id"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun telegramErrorBecomesException() =
        withServer({ """{"ok":false,"error_code":401,"description":"Unauthorized"}""" }) { api, _ ->
            val error = runCatching { api.sendMessage(1, "x") }.exceptionOrNull()
            assertTrue(error is TelegramApiException && error.message == "Unauthorized")
        }
}
