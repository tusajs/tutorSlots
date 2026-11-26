package tutorslots.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tutorslots.domain.ZoneChoice
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Remembers each user's time zone in users.json so it survives restarts (FR-16). */
class UserSettingsStore(private val file: Path) {
    private val zones: MutableMap<Long, ZoneChoice> = load()

    @Synchronized
    fun getZone(userId: Long): ZoneChoice? = zones[userId]

    @Synchronized
    fun setZone(userId: Long, zone: ZoneChoice) {
        zones[userId] = zone
        save()
    }

    private fun load(): MutableMap<Long, ZoneChoice> {
        if (!Files.exists(file)) return mutableMapOf()
        val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
        return root.mapNotNull { (key, value) ->
            val obj = value.jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            key.toLongOrNull()?.let { it to ZoneChoice(id, obj["label"]?.jsonPrimitive?.content ?: id) }
        }.toMap().toMutableMap()
    }

    private fun save() {
        val json = JsonObject(zones.entries.associate { (user, zone) ->
            user.toString() to JsonObject(mapOf("id" to JsonPrimitive(zone.id), "label" to JsonPrimitive(zone.label)))
        })
        Files.createDirectories(file.toAbsolutePath().parent)
        // Write to a temp file first, so a crash mid-write cannot corrupt users.json.
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
