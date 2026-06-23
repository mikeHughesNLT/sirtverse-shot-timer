package com.sirtverse.shottimer.storage

import com.sirtverse.shottimer.domain.shottimer.Shot
import com.sirtverse.shottimer.domain.shottimer.ShotSession
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for ShotSession <-> JSON. Used both by [SessionStorage]
 * (disk) and for passing a not-yet-saved session between activities via an Intent
 * string extra — avoids Parcelable boilerplate and keeps one serialisation path.
 */
object SessionJson {

    fun encode(s: ShotSession): String = toJson(s).toString()

    fun decode(text: String): ShotSession = fromJson(JSONObject(text))

    fun toJson(s: ShotSession): JSONObject {
        val shots = JSONArray()
        s.shots.forEach { shot ->
            shots.put(
                JSONObject()
                    .put("number", shot.number)
                    .put("timeMs", shot.timeMs)
                    .put("splitMs", shot.splitMs)
            )
        }
        return JSONObject()
            .put("id", s.id)
            .put("startedAtEpochMs", s.startedAtEpochMs)
            .put("durationMs", s.durationMs)
            .put("notes", s.notes)
            .put("shots", shots)
    }

    fun fromJson(o: JSONObject): ShotSession {
        val arr = o.getJSONArray("shots")
        val shots = ArrayList<Shot>(arr.length())
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            shots.add(
                Shot(
                    number = j.getInt("number"),
                    timeMs = j.getDouble("timeMs"),
                    splitMs = j.getDouble("splitMs"),
                )
            )
        }
        return ShotSession(
            id = o.getString("id"),
            startedAtEpochMs = o.getLong("startedAtEpochMs"),
            shots = shots,
            durationMs = o.getDouble("durationMs"),
            notes = o.optString("notes", ""),
        )
    }
}
