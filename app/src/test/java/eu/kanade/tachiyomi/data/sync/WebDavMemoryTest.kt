package eu.kanade.tachiyomi.data.sync

// KMK -->
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncCheckpoint
import eu.kanade.tachiyomi.data.sync.service.WebDavSyncMerger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class WebDavMemoryTest {
    private val codec = ProtoBuf
    private val merger = WebDavSyncMerger()

    private fun copy(backup: Backup): Backup = codec.decodeFromByteArray(
        Backup.serializer(),
        codec.encodeToByteArray(Backup.serializer(), backup),
    )

    @Test
    fun `large independent offline snapshots merge within a constrained worker heap`() {
        // Replay with -Xmx256m as well as the regular unit suite. This has enough chapters to
        // expose whole-library JSON duplication, with four distinct snapshots as on Android.
        val baseline = Backup(
            List(100) { series ->
                BackupManga(
                    source = 1,
                    url = "/series/$series",
                    title = "Series $series",
                    chapters = List(600) { chapter ->
                        BackupChapter(
                            url = "/series/$series/chapter/$chapter",
                            name = "Chapter $chapter - A synthetic chapter for large-library regression",
                            chapterNumber = chapter.toFloat(),
                            sourceOrder = chapter.toLong(),
                        )
                    },
                )
            },
        )
        val local = copy(baseline)
        val remote = copy(baseline)
        local.backupManga.first().chapters.first().apply {
            read = true
            version++
        }
        remote.backupManga.last().chapters.last().apply {
            bookmark = true
            version++
        }
        val checkpoint = WebDavSyncCheckpoint(baseline, copy(baseline), "\"previous\"")
        val merged = merger.merge(local, remote, checkpoint)
        assertEquals(60_000, merged.backupManga.sumOf { it.chapters.size })
        assertTrue(merged.backupManga.first().chapters.first().read)
        assertTrue(merged.backupManga.last().chapters.last().bookmark)
        assertFalse(local.backupManga.last().chapters.last().bookmark)
        assertFalse(remote.backupManga.first().chapters.first().read)
        val revision = merger.revision(merged)
        assertNotEquals(merger.revision(local), revision)
        assertEquals(revision, merger.revision(merged.copy(backupManga = merged.backupManga.reversed())))
        assertEquals(revision, merger.revision(merger.forRestore(merger.forUpload(merged))))
    }

    @Test
    fun `legacy JSON ancestry migrates to binary without losing either side`() {
        val local = Backup(listOf(BackupManga(1, "/one", chapters = listOf(BackupChapter("/chapter", "Chapter", read = true)))))
        val remote = copy(local).apply { backupManga.single().chapters.single().bookmark = true }
        val legacy = WebDavSyncCheckpoint(local, remote, "\"etag\"")
        val json = Json { encodeDefaults = true }
        val bytes = json.encodeToString(WebDavSyncCheckpoint.serializer(), legacy).toByteArray()
        val migrated = ByteArrayInputStream(bytes).use { json.decodeFromStream(WebDavSyncCheckpoint.serializer(), it) }
        val binary = codec.encodeToByteArray(WebDavSyncCheckpoint.serializer(), migrated)
        val reopened = codec.decodeFromByteArray(WebDavSyncCheckpoint.serializer(), binary)
        assertEquals(legacy.etag, reopened.etag)
        assertEquals(merger.revision(local), merger.revision(reopened.local))
        assertEquals(merger.revision(remote), merger.revision(reopened.remote))
        assertTrue(binary.size < bytes.size)
    }

    @Test
    fun `binary checkpoints support a server without an ETag`() {
        val backup = Backup(emptyList())
        val checkpoint = WebDavSyncCheckpoint(backup, backup, null)
        val bytes = codec.encodeToByteArray(WebDavSyncCheckpoint.serializer(), checkpoint)
        assertEquals(checkpoint, codec.decodeFromByteArray(WebDavSyncCheckpoint.serializer(), bytes))
    }
}
// KMK <--
