package eu.kanade.tachiyomi.data.sync.service

// KMK -->
import eu.kanade.domain.sync.models.SyncSettings
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import java.security.MessageDigest

/**
 * Record versions remain the legacy bootstrap rule. Once a common checkpoint exists, compare
 * individual fields against each side's acknowledged state, so independent offline edits survive.
 * Absence is never a deletion: this backup format has no general tombstones.
 */
class WebDavSyncMerger(private val settings: SyncSettings = SyncSettings()) {
    private val json = Json { encodeDefaults = true }

    fun revision(backup: Backup): String {
        // Keep at most one series' chapter/history trees live. A whole-library JSON tree,
        // its canonical copy and sort keys can exceed the Android heap during normal sync.
        val value = header(backup)
        val categories = array(value["backupCategories"])
        val nameById = categories.associate {
            it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("name")
        }
        val nameByOrder = categories.associate { it.jsonObject["order"] to it.jsonObject.getValue("name") }
        val semantic = value.toMutableMap()
        semantic["backupCategories"] = JsonArray(categories.map { JsonObject(it.jsonObject - "id") })
        semantic["backupPreferences"] = remapCategoryPreferences(value["backupPreferences"], nameById)
        semantic["backupManga"] = JsonArray(
            backup.backupManga.map { manga ->
                val item = json.encodeToJsonElement(BackupManga.serializer(), manga).jsonObject
                val normalized = JsonObject(
                    item + (
                        "categories" to JsonArray(
                            array(item["categories"]).map { order ->
                                nameByOrder[order] ?: order
                            },
                        )
                        ),
                )
                JsonPrimitive(digest(canonical(normalized).toString()))
            },
        )
        return digest(canonical(JsonObject(semantic)).toString())
    }

    fun forUpload(backup: Backup): Backup = backup.copy(
        backupPreferences = backup.backupPreferences.filterNot { isDevicePreference(it.key) },
    )

    fun forRestore(backup: Backup): Backup {
        val value = header(backup).toMutableMap()
        fun exclude(key: String, enabled: Boolean) {
            if (!enabled) value[key] = JsonArray(emptyList())
        }
        exclude("backupCategories", settings.categories)
        exclude("backupSources", settings.libraryEntries)
        exclude("backupPreferences", settings.appSettings)
        exclude("backupSourcePreferences", settings.sourceSettings)
        exclude("backupExtensionStores", settings.extensionStores)
        exclude("backupSavedSearches", settings.savedSearchesFeeds)
        exclude("backupFeeds", settings.savedSearchesFeeds)
        if (!settings.privateSettings) {
            value["backupPreferences"] = JsonArray(
                array(value["backupPreferences"]).filter {
                    !isPrivatePreference(it.jsonObject.getValue("key").jsonPrimitive.content)
                },
            )
            value["backupSourcePreferences"] = JsonArray(
                array(value["backupSourcePreferences"]).map { source ->
                    JsonObject(
                        source.jsonObject + (
                            "prefs" to JsonArray(
                                array(source.jsonObject["prefs"]).filter {
                                    !isPrivatePreference(it.jsonObject.getValue("key").jsonPrimitive.content)
                                },
                            )
                            ),
                    )
                },
            )
        }
        return decode(JsonObject(value)).copy(
            backupManga = if (settings.libraryEntries) {
                backup.backupManga.map { manga ->
                    if (settings.chapters && settings.categories && settings.tracking && settings.history && settings.customInfo) {
                        manga
                    } else {
                        // Only clone records whose selected sections need filtering; never mutate the
                        // remote snapshot held by the checkpoint or its private recovery copy.
                        val record = json.encodeToJsonElement(BackupManga.serializer(), manga).jsonObject.toMutableMap()
                        if (!settings.chapters) record["chapters"] = JsonArray(emptyList())
                        if (!settings.categories) record["categories"] = JsonArray(emptyList())
                        if (!settings.tracking) record["tracking"] = JsonArray(emptyList())
                        if (!settings.history) record["history"] = JsonArray(emptyList())
                        if (!settings.customInfo) record.keys.filter { it.startsWith("custom") }.toList().forEach(record::remove)
                        json.decodeFromJsonElement(BackupManga.serializer(), JsonObject(record))
                    }
                }
            } else {
                emptyList()
            },
        )
    }

    fun merge(local: Backup, remote: Backup, checkpoint: WebDavSyncCheckpoint?): Backup {
        val l = header(local)
        val r = header(remote)
        val bl = checkpoint?.local?.let(::header)
        val br = checkpoint?.remote?.let(::header)
        // Reconcile category order collisions by name, rather than assigning two names one order.
        var nextOrder = array(l["backupCategories"]).maxOfOrNull {
            it.jsonObject["order"]?.jsonPrimitive?.longOrNull ?: 0L
        }?.plus(1) ?: 0L
        val localNames = array(l["backupCategories"]).map { it.jsonObject["name"] }.toSet()
        val categories = (array(l["backupCategories"]) + array(r["backupCategories"]))
            .distinctBy { it.jsonObject["name"] }
            .mapIndexed { index, category ->
                val order = if (category.jsonObject["name"] in localNames) {
                    category.jsonObject.getValue("order")
                } else {
                    JsonPrimitive(nextOrder++)
                }
                JsonObject(category.jsonObject + mapOf("order" to order, "id" to JsonPrimitive(index + 1)))
            }
        val orderByName = categories.associate { it.jsonObject["name"] to it.jsonObject.getValue("order") }
        val idByName = categories.associate { it.jsonObject["name"] to it.jsonObject.getValue("id") }
        fun remap(value: JsonObject?): JsonObject? {
            if (value == null) return null
            val nameById = array(value["backupCategories"]).associate {
                it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("name")
            }
            val ids = nameById.mapNotNull { (id, name) -> idByName[name]?.let { id to it } }.toMap()
            return JsonObject(
                value + mapOf(
                    "backupCategories" to JsonArray(
                        array(value["backupCategories"]).filter {
                            it.jsonObject["name"] in idByName
                        }.map {
                            JsonObject(
                                it.jsonObject + mapOf(
                                    "id" to idByName.getValue(it.jsonObject["name"]),
                                    "order" to orderByName.getValue(it.jsonObject["name"]),
                                ),
                            )
                        },
                    ),
                    "backupPreferences" to remapCategoryPreferences(value["backupPreferences"], ids),
                ),
            )
        }
        val ml = remap(l)!!
        val mr = remap(r)!!
        val mbl = remap(bl)
        val mbr = remap(br)
        val merged = l.toMutableMap()
        collectionKeys.filterKeys { it in l || it in r }.forEach { (field, keys) ->
            merged[field] = mergeList(field, ml[field], mr[field], mbl?.get(field), mbr?.get(field), keys)
        }
        // IDs are device local; use the chosen category's ID for preference restoration mapping.
        val mergedCategories = array(merged["backupCategories"]).associateBy { it.jsonObject["name"] }
        merged["backupCategories"] = JsonArray(
            categories.map {
                JsonObject(
                    mergedCategories.getValue(it.jsonObject["name"]).jsonObject +
                        ("order" to it.jsonObject.getValue("order")),
                )
            },
        )
        // Retain legacy device-control entries already on the server, but never import them.
        val controls = remote.backupPreferences.filter { isDevicePreference(it.key) }
        val mergedBackup = decode(JsonObject(merged))
        fun index(backup: Backup?) = backup?.backupManga.orEmpty().associateBy { it.source to it.url }
        val localManga = index(local)
        val remoteManga = index(remote)
        val baseLocalManga = index(checkpoint?.local)
        val baseRemoteManga = index(checkpoint?.remote)
        fun record(manga: BackupManga?, backup: Backup?): JsonObject? {
            if (manga == null || backup == null) return null
            val nameByOrder = backup.backupCategories.associate { JsonPrimitive(it.order) to JsonPrimitive(it.name) }
            val value = json.encodeToJsonElement(BackupManga.serializer(), manga).jsonObject
            return JsonObject(
                value + ("categories" to JsonArray(array(value["categories"]).mapNotNull { orderByName[nameByOrder[it]] })),
            )
        }
        val mangas = (localManga.keys + remoteManga.keys).map { key ->
            val left = record(localManga[key], local)
            val right = record(remoteManga[key], remote)
            val value = when {
                left == null -> right!!
                right == null -> left
                else -> mergeRecord(
                    "backupManga",
                    left,
                    right,
                    record(baseLocalManga[key], checkpoint?.local),
                    record(baseRemoteManga[key], checkpoint?.remote),
                )
            }
            json.decodeFromJsonElement(BackupManga.serializer(), value)
        }
        return mergedBackup.copy(backupManga = mangas, backupPreferences = mergedBackup.backupPreferences + controls)
    }

    private fun mergeList(
        field: String,
        local: JsonElement?,
        remote: JsonElement?,
        baseLocal: JsonElement?,
        baseRemote: JsonElement?,
        keys: List<String>,
    ): JsonArray {
        fun index(value: JsonElement?) = array(value).associateBy { item ->
            keys.map { item.jsonObject[it] }
        }
        val l = index(local)
        val r = index(remote)
        val bl = index(baseLocal)
        val br = index(baseRemote)
        return JsonArray(
            (l.keys + r.keys).map { key ->
                val left = l[key]?.jsonObject
                val right = r[key]?.jsonObject
                when {
                    left == null -> right!!
                    right == null -> left
                    else -> mergeRecord(field, left, right, bl[key]?.jsonObject, br[key]?.jsonObject)
                }
            },
        )
    }

    private fun mergeRecord(
        kind: String,
        local: JsonObject,
        remote: JsonObject,
        baseLocal: JsonObject?,
        baseRemote: JsonObject?,
    ): JsonObject {
        val localVersion = local["version"]?.jsonPrimitive?.longOrNull ?: 0L
        val remoteVersion = remote["version"]?.jsonPrimitive?.longOrNull ?: 0L
        if (kind == "chapters" && baseRemote != null &&
            remoteVersion <= (baseRemote["version"]?.jsonPrimitive?.longOrNull ?: 0L) &&
            hasUnversionedRegression(local, remote, baseRemote)
        ) {
            // Original Komikku selects a whole chapter on a version tie. An offline bookmark
            // edit can therefore publish read=false at the same version as our acknowledged
            // read=true. This is indistinguishable from an edit made with a stale counter;
            // preserve the Duo's state and both snapshots instead of silently rolling it back.
            throw WebDavConflictException("chapters.reading-state")
        }
        val preferred = if (localVersion >= remoteVersion) local else remote
        val merged = (local.keys + remote.keys).associateWith { field ->
            val l = local[field]
            val r = remote[field]
            val bl = baseLocal?.get(field)
            val br = baseRemote?.get(field)
            when {
                field in collectionKeys -> mergeList(field, l, r, bl, br, collectionKeys.getValue(field))
                field == "id" && kind == "backupCategories" -> l ?: r!!
                field == "order" && kind == "backupCategories" -> l ?: r!!
                l == JsonNull && r == JsonNull -> JsonNull
                field == "version" || field == "lastModifiedAt" || field == "favoriteModifiedAt" ||
                    (kind == "history" && (field == "lastRead" || field == "readDuration")) ->
                    JsonPrimitive(maxOf(l?.jsonPrimitive?.longOrNull ?: 0L, r?.jsonPrimitive?.longOrNull ?: 0L))
                field == "dateFetch" -> l ?: r!! // Local download/fetch bookkeeping is device specific.
                same(l, r) -> l ?: JsonNull
                baseLocal != null || baseRemote != null -> when {
                    same(l, bl) -> r ?: l ?: JsonNull
                    same(r, br) -> l ?: r ?: JsonNull
                    else -> throw WebDavConflictException("$kind.$field") // Retain both sides rather than guessing.
                }
                kind == "chapters" && localVersion == remoteVersion && field in setOf("read", "bookmark") ->
                    JsonPrimitive(l?.jsonPrimitive?.booleanOrNull == true || r?.jsonPrimitive?.booleanOrNull == true)
                kind == "chapters" && localVersion == remoteVersion && field == "lastPageRead" ->
                    JsonPrimitive(maxOf(l?.jsonPrimitive?.longOrNull ?: 0L, r?.jsonPrimitive?.longOrNull ?: 0L))
                // Unversioned legacy settings cannot establish recency. Keep local on bootstrap;
                // the original remote backup has already been retained as a private recovery file.
                kind == "backupPreferences" || kind == "prefs" -> l ?: r ?: JsonNull
                else -> preferred[field] ?: l ?: r ?: JsonNull
            }
        }.toMutableMap()
        // A merged record must beat either ancestor on legacy clients as well.
        if ("version" in merged && !same(JsonObject(merged), local) && !same(JsonObject(merged), remote)) {
            merged["version"] = JsonPrimitive(maxOf(localVersion, remoteVersion) + 1)
        }
        return JsonObject(merged)
    }

    private fun header(backup: Backup): JsonObject {
        val value = json.encodeToJsonElement(Backup.serializer(), backup.copy(backupManga = emptyList())).jsonObject.toMutableMap()
        value["backupPreferences"] = JsonArray(
            array(value["backupPreferences"]).filter {
                val key = it.jsonObject.getValue("key").jsonPrimitive.content
                !isDevicePreference(key)
            },
        )
        return JsonObject(value)
    }

    private fun decode(value: JsonObject) = json.decodeFromJsonElement(Backup.serializer(), value)
    private fun same(a: JsonElement?, b: JsonElement?) = a == b || canonical(a ?: JsonNull) == canonical(b ?: JsonNull)

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(
            value.filterKeys { it != "lastModifiedAt" && it != "dateFetch" }
                .toSortedMap().mapValues { (key, item) ->
                    val normalized = canonical(item)
                    if (normalized is JsonArray && (
                            key in collectionKeys || key in setOf("categories", "excludedScanlators") ||
                                (key == "value" && value["type"]?.jsonPrimitive?.content?.endsWith("StringSetPreferenceValue") == true)
                            )
                    ) {
                        // Materialize a sort key once per item, rather than serializing entire
                        // chapter/history records for every comparator invocation.
                        JsonArray(normalized.map { it.toString() to it }.sortedBy { it.first }.map { it.second })
                    } else {
                        normalized
                    }
                },
        )
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

    private fun array(value: JsonElement?) = (value as? JsonArray).orEmpty()

    private fun hasUnversionedRegression(local: JsonObject, remote: JsonObject, base: JsonObject): Boolean {
        if (listOf("read", "bookmark").any { field ->
                base[field]?.jsonPrimitive?.booleanOrNull == true &&
                    remote[field]?.jsonPrimitive?.booleanOrNull == false &&
                    local[field]?.jsonPrimitive?.booleanOrNull == true
            }
        ) {
            return true
        }
        fun page(value: JsonObject) = value["lastPageRead"]?.jsonPrimitive?.longOrNull ?: 0L
        return page(remote) < page(base) && page(remote) < page(local)
    }

    private fun remapCategoryPreferences(preferences: JsonElement?, ids: Map<String, JsonElement>): JsonArray =
        JsonArray(
            array(preferences).map { preference ->
                val entry = preference.jsonObject
                if (entry.getValue("key").jsonPrimitive.content !in categoryPreferenceKeys) return@map preference
                val wrapped = entry.getValue("value").jsonObject
                val old = wrapped.getValue("value")
                fun mapped(value: JsonElement): JsonElement = ids[value.jsonPrimitive.content]?.let {
                    if (value.jsonPrimitive.isString && it is JsonPrimitive && !it.isString) JsonPrimitive(it.content) else it
                } ?: value
                val replacement = if (old is JsonArray) JsonArray(old.map(::mapped)) else mapped(old)
                JsonObject(entry + ("value" to JsonObject(wrapped + ("value" to replacement))))
            },
        )

    companion object {
        private val categoryPreferenceKeys = LibraryPreferences.categoryPreferenceKeys + DownloadPreferences.categoryPreferenceKeys
        private val collectionKeys = mapOf(
            "backupManga" to listOf("source", "url"),
            "chapters" to listOf("url"),
            "tracking" to listOf("syncId"),
            "history" to listOf("url"),
            "backupCategories" to listOf("name"),
            "backupSources" to listOf("sourceId"),
            "backupPreferences" to listOf("key"),
            "backupSourcePreferences" to listOf("sourceKey"),
            "prefs" to listOf("key"),
            "backupExtensionStores" to listOf("indexUrl"),
            "backupSavedSearches" to listOf("source", "name", "query", "filterList"),
            "backupFeeds" to listOf("source", "global", "savedSearch"),
            "mergedMangaReferences" to listOf("mangaSourceId", "mangaUrl", "mergeUrl"),
        )

        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        // Sync connection/control state must never migrate from a different device or endpoint.
        fun isDevicePreference(key: String): Boolean =
            key.startsWith("connection_") || key.startsWith("sync_") || key == "last_sync_timestamp" ||
                key.startsWith("__APP_STATE_")

        private fun isPrivatePreference(key: String): Boolean = key.startsWith("__PRIVATE_")
    }
}
// KMK <--
