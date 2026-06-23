package com.sirtverse.shottimer.storage

import android.content.Context
import com.sirtverse.shottimer.domain.shottimer.ShotSession
import org.json.JSONObject
import java.io.File

/**
 * Dead-simple local persistence: one JSON file per session in
 * filesDir/sessions/<id>.json, using Android's built-in org.json (zero extra
 * dependencies — no Room, no kotlinx.serialization, no build risk).
 *
 * Sessions are small (dozens of shots) and few, so file-per-session is more than
 * fast enough and trivially debuggable. Serialisation lives in [SessionJson].
 */
class SessionStorage(context: Context) {

    private val dir: File =
        File(context.filesDir, "sessions").apply { if (!exists()) mkdirs() }

    fun save(session: ShotSession) {
        File(dir, "${session.id}.json").writeText(SessionJson.encode(session))
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    /** All saved sessions, newest first. Corrupt files are skipped, not fatal. */
    fun loadAll(): List<ShotSession> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { f -> runCatching { SessionJson.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.startedAtEpochMs }

    fun load(id: String): ShotSession? =
        File(dir, "$id.json").takeIf { it.exists() }
            ?.let { f -> runCatching { SessionJson.fromJson(JSONObject(f.readText())) }.getOrNull() }
}
