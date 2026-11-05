package com.srivathsan.livepix.data

import android.content.Context
import org.json.JSONObject

/** Edits keyed by asset; stored in app preferences, never written into the photo files. */
class EditStore(context: Context) {
    private val prefs = context.getSharedPreferences("edits", Context.MODE_PRIVATE)

    fun all(): Map<String, AssetEdit> = prefs.all.mapNotNull { (k, v) ->
        (v as? String)?.let { k to decode(it) }
    }.toMap()

    fun put(key: String, edit: AssetEdit) {
        if (edit == AssetEdit()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, encode(edit)).apply()
    }

    private fun encode(e: AssetEdit) = JSONObject().apply {
        put("effect", e.effect.name)
        e.keyFrameUs?.let { put("key", it) }
        put("muted", e.muted)
        put("portrait", e.portrait)
        put("aperture", e.aperture.toDouble())
        e.focusX?.let { put("fx", it.toDouble()) }
        e.focusY?.let { put("fy", it.toDouble()) }
        put("lighting", e.lighting)
        put("li", e.lightingIntensity.toDouble())
        put("fav", e.favorite)
    }.toString()

    private fun decode(s: String): AssetEdit = runCatching {
        val o = JSONObject(s)
        AssetEdit(
            effect = runCatching { LiveEffect.valueOf(o.optString("effect")) }.getOrDefault(LiveEffect.LIVE),
            keyFrameUs = if (o.has("key")) o.getLong("key") else null,
            muted = o.optBoolean("muted"),
            portrait = o.optBoolean("portrait", true),
            aperture = o.optDouble("aperture", 4.5).toFloat(),
            focusX = if (o.has("fx")) o.getDouble("fx").toFloat() else null,
            focusY = if (o.has("fy")) o.getDouble("fy").toFloat() else null,
            lighting = o.optString("lighting", "NATURAL"),
            lightingIntensity = o.optDouble("li", 0.5).toFloat(),
            favorite = o.optBoolean("fav"),
        )
    }.getOrDefault(AssetEdit())
}
