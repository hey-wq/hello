package com.signidesign.dailytasks.sync

import com.signidesign.dailytasks.data.DayNoteEntity
import com.signidesign.dailytasks.data.DeletedTaskEntity
import com.signidesign.dailytasks.data.SettingsRepository
import com.signidesign.dailytasks.data.TaskEntity
import com.signidesign.dailytasks.data.TaskRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Two-way sync against a Google Apps Script web app backed by a spreadsheet
 * (see googlesheets/Code.gs). Both sides merge with last-write-wins on the
 * per-row `updatedAt` epoch-millis timestamp; deletions travel as rows with
 * `deleted = true`. Each round trip pushes local changes since the last sync
 * and pulls everything the sheet has changed since then.
 */
class SyncEngine(
    private val repository: TaskRepository,
    private val settings: SettingsRepository
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    sealed interface SyncResult {
        data class Success(val pushed: Int, val pulled: Int) : SyncResult
        data class Error(val message: String) : SyncResult
        data object NotConfigured : SyncResult
    }

    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val url = settings.syncUrl.first().trim()
            val token = settings.syncToken.first().trim()
            if (url.isBlank() || token.isBlank()) return@withContext SyncResult.NotConfigured
            val since = settings.lastSyncAt.first()

            val localTasks = repository.tasksUpdatedSince(since)
            val tombstones = repository.tombstonesSince(since)
            val localNotes = repository.notesUpdatedSince(since)

            val payload = JSONObject().apply {
                put("token", token)
                put("since", since)
                put("tasks", JSONArray().also { arr ->
                    localTasks.forEach { arr.put(taskToJson(it)) }
                    tombstones.forEach { arr.put(tombstoneToJson(it)) }
                })
                put("dayNotes", JSONArray().also { arr ->
                    localNotes.forEach { arr.put(noteToJson(it)) }
                })
            }

            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val bodyText = response.use { it.body?.string().orEmpty() }
            if (!response.isSuccessful) {
                return@withContext SyncResult.Error("HTTP ${response.code}")
            }
            val json = try {
                JSONObject(bodyText)
            } catch (_: Exception) {
                return@withContext SyncResult.Error(
                    "Unexpected response — is the web app URL correct?"
                )
            }
            if (json.has("error")) {
                return@withContext SyncResult.Error(json.getString("error"))
            }

            var pulled = 0
            val remoteTasks = json.optJSONArray("tasks") ?: JSONArray()
            for (i in 0 until remoteTasks.length()) {
                if (applyRemoteTask(remoteTasks.getJSONObject(i))) pulled++
            }
            val remoteNotes = json.optJSONArray("dayNotes") ?: JSONArray()
            for (i in 0 until remoteNotes.length()) {
                if (applyRemoteNote(remoteNotes.getJSONObject(i))) pulled++
            }

            settings.setLastSyncAt(json.optLong("serverTime", System.currentTimeMillis()))
            SyncResult.Success(
                pushed = localTasks.size + tombstones.size + localNotes.size,
                pulled = pulled
            )
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    // -------------------------------------------------------------- outbound

    private fun taskToJson(task: TaskEntity): JSONObject = JSONObject().apply {
        put("uuid", task.uuid)
        put("dayDate", task.dayDate.toString())
        put("title", task.title)
        put("done", task.isDone)
        put("timed", task.isTimed)
        put("startTime", task.startTime?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "")
        put("durationMin", task.durationMinutes ?: 0)
        put("note", task.note ?: "")
        put("sortOrder", task.sortOrder)
        put("createdAt", task.createdAt.toEpochMilli())
        put("createdBy", task.createdBy)
        put("updatedAt", task.updatedAt)
        put("deleted", false)
    }

    private fun tombstoneToJson(tombstone: DeletedTaskEntity): JSONObject =
        JSONObject().apply {
            put("uuid", tombstone.uuid)
            put("updatedAt", tombstone.deletedAt)
            put("deleted", true)
        }

    private fun noteToJson(note: DayNoteEntity): JSONObject = JSONObject().apply {
        put("dayDate", note.dayDate.toString())
        put("content", note.content)
        put("updatedAt", note.updatedAt)
    }

    // --------------------------------------------------------------- inbound

    private suspend fun applyRemoteTask(obj: JSONObject): Boolean {
        val uuid = obj.optString("uuid")
        if (uuid.isBlank()) return false
        val updatedAt = obj.optLong("updatedAt")
        val local = repository.taskByUuid(uuid)

        if (parseBool(obj.opt("deleted"))) {
            if (local != null && updatedAt > local.updatedAt) {
                repository.deleteFromRemote(local)
                return true
            }
            return false
        }

        if (local != null && local.updatedAt >= updatedAt) return false
        val dayDate = parseDate(obj.optString("dayDate")) ?: return false
        val startTime = parseTime(obj.optString("startTime"))
        val duration = obj.optDouble("durationMin", 0.0).toInt().takeIf { it > 0 }

        val entity = TaskEntity(
            id = local?.id ?: 0L,
            uuid = uuid,
            title = obj.optString("title"),
            dayDate = dayDate,
            isDone = parseBool(obj.opt("done")),
            createdAt = Instant.ofEpochMilli(
                obj.optLong("createdAt", updatedAt.takeIf { it > 0 }
                    ?: System.currentTimeMillis())
            ),
            createdBy = obj.optString("createdBy").ifBlank { "sheet" },
            isTimed = parseBool(obj.opt("timed")) && startTime != null,
            startTime = startTime,
            durationMinutes = if (startTime != null) duration else null,
            note = obj.optString("note").ifBlank { null },
            sortOrder = obj.optLong("sortOrder"),
            updatedAt = updatedAt
        )
        if (local == null) repository.insertFromRemote(entity)
        else repository.updateFromRemote(entity)
        return true
    }

    private suspend fun applyRemoteNote(obj: JSONObject): Boolean {
        val date = parseDate(obj.optString("dayDate")) ?: return false
        val updatedAt = obj.optLong("updatedAt")
        val local = repository.dayNoteOnce(date)
        if (local != null && local.updatedAt >= updatedAt) return false
        repository.upsertNoteFromRemote(
            DayNoteEntity(
                dayDate = date,
                content = obj.optString("content"),
                updatedAt = updatedAt
            )
        )
        return true
    }

    // --------------------------------------------------------------- parsing

    private fun parseBool(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true) || value == "1"
        else -> false
    }

    private fun parseDate(value: String): LocalDate? = try {
        // The script normalizes date cells to yyyy-MM-dd, but be tolerant of
        // extra content (e.g. an ISO datetime).
        LocalDate.parse(value.trim().take(10))
    } catch (_: Exception) {
        null
    }

    private fun parseTime(value: String): LocalTime? = try {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) null else LocalTime.parse(trimmed.take(5))
    } catch (_: Exception) {
        null
    }
}
