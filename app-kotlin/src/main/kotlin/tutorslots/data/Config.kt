package tutorslots.data

import java.io.File
import java.nio.file.Path

/**
 * Settings come from environment variables, or from a `bot.env` file (KEY=VALUE per line)
 * in the working directory. Environment variables win. Secrets never live in the repo (NFR-02).
 */
data class Config(
    val botToken: String,
    val googleApiKey: String,
    val spreadsheetId: String,
    val sheetRange: String?,       // e.g. "Лист1!A1:H16"; null = first sheet
    val tutorTelegramId: Long?,    // who may use /stats
    val tutorContact: String?,     // shown under the picture, e.g. "@tutor_username"
    val telegramApiUrl: String,    // can point to a proxy/mirror of api.telegram.org
    val dataDir: Path,             // snapshot cache and users.json
    val proxy: String? = null,     // "host:port" HTTP proxy for Telegram and Google, if needed
    val earliestStart: Int = 8 * 60, // hide slots that start earlier for the client
) {
    /** Makes java.net.http use the proxy from bot.env (it reads the standard JVM properties). */
    fun applyProxy() {
        val (host, port) = proxy?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return
        for (scheme in listOf("http", "https")) {
            System.setProperty("$scheme.proxyHost", host.trim())
            System.setProperty("$scheme.proxyPort", port.trim())
        }
    }

    companion object {
        fun load(envFile: File = File("bot.env"), env: Map<String, String> = System.getenv()): Config =
            fromMap(readEnvFile(envFile) + env)

        fun fromMap(values: Map<String, String>): Config {
            fun opt(key: String) = values[key]?.trim()?.takeIf { it.isNotEmpty() }
            fun req(key: String) = opt(key) ?: throw IllegalStateException("Missing setting $key (see bot.env.example)")
            return Config(
                botToken = req("BOT_TOKEN"),
                googleApiKey = req("GOOGLE_API_KEY"),
                spreadsheetId = extractSpreadsheetId(req("SPREADSHEET")),
                sheetRange = opt("SHEET_RANGE"),
                tutorTelegramId = opt("TUTOR_ID")?.toLongOrNull(),
                tutorContact = opt("TUTOR_CONTACT"),
                telegramApiUrl = (opt("TELEGRAM_API_URL") ?: "https://api.telegram.org").trimEnd('/'),
                dataDir = Path.of(opt("DATA_DIR") ?: "data"),
                proxy = opt("PROXY"),
                earliestStart = opt("EARLIEST_START")?.let {
                    tutorslots.core.TimeShift.parseClock(it)
                        ?: throw IllegalStateException("EARLIEST_START must look like 08:00, got '$it'")
                } ?: 8 * 60,
            )
        }

        /** Accepts a full link (https://docs.google.com/spreadsheets/d/<id>/edit#gid=0) or a bare id. */
        fun extractSpreadsheetId(value: String): String =
            Regex("/spreadsheets/d/([a-zA-Z0-9_-]+)").find(value)?.groupValues?.get(1) ?: value.trim()

        fun readEnvFile(file: File): Map<String, String> {
            if (!file.exists()) return emptyMap()
            return file.readLines(Charsets.UTF_8)
                .map { it.removePrefix("﻿").trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .associate { line ->
                    val (k, v) = line.split('=', limit = 2)
                    k.trim() to v.trim().removeSurrounding("\"")
                }
        }
    }
}
