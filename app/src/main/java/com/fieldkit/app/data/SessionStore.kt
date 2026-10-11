package com.fieldkit.app.data

import android.content.Context
import android.graphics.Bitmap
import com.fieldkit.app.core.Fmt
import org.json.JSONObject
import java.io.File

/**
 * Plain-file storage, all inside the app's private directory:
 *
 *   files/sessions/<id>/session.json   everything logged in the session
 *   files/sessions/<id>/spectrogram-<time>.png   snapshots
 *
 * Chosen over Room because a session is one small document that is always
 * read and written whole. Writes go to a temp file first and are then renamed,
 * so a crash mid-write cannot corrupt the previous version.
 */
class SessionStore(context: Context) {
    private val root = File(context.filesDir, "sessions")
    private val prefs = context.getSharedPreferences("fieldkit", Context.MODE_PRIVATE)

    private fun dir(id: String) = File(root, id)
    private fun jsonFile(id: String) = File(dir(id), "session.json")

    fun snapshotFile(sessionId: String, fileName: String) = File(dir(sessionId), fileName)

    @Synchronized
    fun save(session: Session) {
        val folder = dir(session.id)
        folder.mkdirs()
        val tmp = File(folder, "session.json.tmp")
        tmp.writeText(SessionJson.toJson(session).toString())
        val target = jsonFile(session.id)
        if (!tmp.renameTo(target)) {
            // renameTo can refuse to overwrite on some filesystems.
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    @Synchronized
    fun load(id: String): Session? = try {
        val file = jsonFile(id)
        if (file.exists()) SessionJson.fromJson(JSONObject(file.readText())) else null
    } catch (e: Exception) {
        null // unreadable file: treated as missing rather than crashing the app
    }

    @Synchronized
    fun list(): List<SessionInfo> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { load(it.name) }
            .map { SessionInfo(it.id, it.name, it.createdMs, it.entryCount) }
            .sortedByDescending { it.createdMs }

    @Synchronized
    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    fun create(name: String? = null): Session {
        val now = System.currentTimeMillis()
        val session = Session(
            id = "s$now",
            name = name?.trim()?.takeIf { it.isNotEmpty() } ?: "Session ${Fmt.dateTime(now).dropLast(3)}",
            createdMs = now,
        )
        save(session)
        return session
    }

    /** Writes a spectrogram image into the session folder. Returns the file name, or null on failure. */
    fun saveSnapshot(sessionId: String, bitmap: Bitmap, timeMs: Long): String? = try {
        val name = "spectrogram-${Fmt.fileStamp(timeMs)}.png"
        val file = snapshotFile(sessionId, name)
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        name
    } catch (e: Exception) {
        null
    }

    var lastSessionId: String?
        get() = prefs.getString("lastSessionId", null)
        set(value) = prefs.edit().putString("lastSessionId", value).apply()

    var darkTheme: Boolean
        get() = prefs.getBoolean("darkTheme", true)
        set(value) = prefs.edit().putBoolean("darkTheme", value).apply()

    var safetyAcknowledged: Boolean
        get() = prefs.getBoolean("safetyAcknowledged", false)
        set(value) = prefs.edit().putBoolean("safetyAcknowledged", value).apply()
}
