package com.srivathsan.livepix.data

import android.content.Context
import com.srivathsan.livepix.media.FaceMath
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One face in one photo. [id] stays the same while the file does. */
class FaceRef(val id: String, val assetKey: String, val face: FaceFacts)

/** What is remembered about a person between runs: their name, and what they look like. */
data class PersonRecord(
    val id: String,
    val name: String? = null,
    /** Reference faces. More than one after merging, when a person looks different across photos. */
    val anchors: List<FloatArray> = emptyList(),
    /** Faces the user said aren't this person. */
    val rejected: Set<String> = emptySet(),
)

/** A person as shown in the app. */
class Person(val id: String, val name: String?, val keys: List<String>, val coverKey: String, val cover: FaceFacts, val faceIds: Map<String, List<String>>) {
    val title: String get() = name ?: "Add Name"
}

object FaceClusterer {
    /**
     * Cosine similarity for "same person". Calibrated on the LFW face dataset: at 0.42, 1,065 faces of
     * 40 people grouped with none mixed; at 0.38 some people started to merge.
     */
    const val THRESHOLD = 0.42f
    private const val MERGE_THRESHOLD = 0.5f

    class Result(val records: List<PersonRecord>, val assignment: Map<String, String>)

    private class Cluster(val record: PersonRecord) {
        val sum = FloatArray(EMBEDDING)
        var count = 0
        var centroid: FloatArray? = record.anchors.firstOrNull()
        val members = ArrayList<FaceRef>()

        fun add(f: FaceRef) {
            members += f
            count++
            for (i in sum.indices) sum[i] += f.face.embedding[i]
            centroid = FaceMath.normalize(sum.copyOf())
        }

        fun similarity(v: FloatArray): Float {
            var best = centroid?.let { FaceMath.similarity(it, v) } ?: -1f
            for (a in record.anchors) best = maxOf(best, FaceMath.similarity(a, v))
            return best
        }
    }

    /**
     * Groups faces, starting from the people already known so their ids and names carry over. Clearest
     * faces go first, since they make better references for the harder ones.
     */
    fun cluster(faces: List<FaceRef>, known: List<PersonRecord>, threshold: Float = THRESHOLD): Result {
        val clusters = known.map(::Cluster).toMutableList()
        for (f in faces.sortedByDescending { it.face.score * it.face.w }) {
            var best: Cluster? = null
            var bestScore = threshold
            for (c in clusters) {
                if (f.id in c.record.rejected) continue
                val s = c.similarity(f.face.embedding)
                if (s >= bestScore) { bestScore = s; best = c }
            }
            (best ?: Cluster(PersonRecord(UUID.randomUUID().toString())).also { clusters += it }).add(f)
        }
        // Early faces can split one person in two; join nameless groups that ended up alike.
        val merged = ArrayList<Cluster>()
        for (c in clusters.sortedByDescending { it.count }) {
            val center = c.centroid
            val into = if (c.record.name != null || center == null) null else merged.firstOrNull { other ->
                val o = other.centroid
                o != null && FaceMath.similarity(o, center) >= MERGE_THRESHOLD && c.members.none { it.id in other.record.rejected }
            }
            if (into == null) merged += c else c.members.forEach(into::add)
        }
        val assignment = HashMap<String, String>()
        val records = merged.filter { it.count >= 2 || it.record.name != null }.map { c ->
            c.members.forEach { assignment[it.id] = c.record.id }
            val anchors = if (c.record.anchors.size > 1) c.record.anchors else listOfNotNull(c.centroid)
            c.record.copy(anchors = anchors)
        }
        return Result(records, assignment)
    }

    private const val EMBEDDING = 128
}

/** Names and reference faces, saved as JSON in app storage. */
class PeopleStore(context: Context) {
    private val file = File(context.filesDir, "people.json")
    var records: List<PersonRecord> = load(); private set

    /** Counts the user's changes, so a rebuild started before one can't overwrite it. */
    var version = 0; private set

    /** Stores a rebuild's result, unless the user changed something while it ran. */
    @Synchronized
    fun replaceIfUnchanged(sinceVersion: Int, next: List<PersonRecord>): Boolean {
        if (version != sinceVersion) return false
        records = next
        save()
        return true
    }

    /** Names a person. Using a name someone already has merges the two; returns who remains. */
    @Synchronized
    fun name(id: String, name: String): String {
        version++
        val existing = records.firstOrNull { it.id != id && it.name.equals(name, ignoreCase = true) }
        if (existing != null) return merge(id, into = existing.id)
        records = records.map { if (it.id == id) it.copy(name = name) else it }
        save()
        return id
    }

    @Synchronized
    fun merge(id: String, into: String): String {
        version++
        val from = records.firstOrNull { it.id == id } ?: return into
        records = records.filterNot { it.id == id }.map {
            if (it.id == into) it.copy(anchors = it.anchors + from.anchors, rejected = it.rejected + from.rejected) else it
        }
        save()
        return into
    }

    @Synchronized
    fun reject(id: String, faceIds: Collection<String>) {
        version++
        records = records.map { if (it.id == id) it.copy(rejected = it.rejected + faceIds) else it }
        save()
    }

    private fun save() {
        val json = JSONArray()
        records.forEach { r ->
            json.put(JSONObject().apply {
                put("id", r.id)
                r.name?.let { put("name", it) }
                put("anchors", JSONArray().apply { r.anchors.forEach { a -> put(JSONArray().apply { a.forEach { put(it.toDouble()) } }) } })
                put("rejected", JSONArray(r.rejected.toList()))
            })
        }
        runCatching { file.writeText(json.toString()) }
    }

    private fun load(): List<PersonRecord> {
        val json = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            val anchors = o.getJSONArray("anchors")
            val rejected = o.getJSONArray("rejected")
            PersonRecord(
                o.getString("id"),
                o.optString("name").ifEmpty { null },
                (0 until anchors.length()).map { a ->
                    val v = anchors.getJSONArray(a)
                    FloatArray(v.length()) { v.getDouble(it).toFloat() }
                },
                (0 until rejected.length()).map { rejected.getString(it) }.toSet(),
            )
        }
    }
}
