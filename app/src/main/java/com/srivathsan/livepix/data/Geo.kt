package com.srivathsan.livepix.data

import android.content.Context
import java.io.DataInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class GeoPoint(val lat: Double, val lng: Double)

data class City(val name: String, val lat: Double, val lng: Double, val population: Int, val region: String, val country: String) {
    val point: GeoPoint get() = GeoPoint(lat, lng)
    /** "Kochi, Kerala, India". */
    val fullName: String get() = listOf(name, region, country).filter { it.isNotEmpty() }.distinct().joinToString(", ")
}

/** Offline place names, from GeoNames data bundled with the app. Nothing leaves the phone. */
class GeoIndex(val cities: List<City>) {
    private val bins = HashMap<Int, MutableList<City>>()
    private val nearestCache = ConcurrentHashMap<Long, Any>()

    init { cities.forEach { bins.getOrPut(bin(it.lat, it.lng)) { ArrayList() }.add(it) } }

    /** The city a point belongs to: close by, leaning towards a larger place over a nearby suburb. */
    fun nearest(lat: Double, lng: Double, maxKm: Double = 60.0): City? {
        val cacheKey = (floor((lat + 90) * 100).toLong() shl 20) or floor((lng + 180) * 100).toLong()
        val cached = nearestCache.getOrPut(cacheKey) { lookup(lat, lng, maxKm) ?: NONE }
        return cached as? City
    }

    private fun lookup(lat: Double, lng: Double, maxKm: Double): City? {
        var best: City? = null
        var bestScore = Double.MAX_VALUE
        for (dy in -1..1) for (dx in -1..1) {
            bins[bin(lat + dy, lng + dx)]?.forEach { c ->
                val km = distanceKm(lat, lng, c.lat, c.lng)
                val score = (km + 1) / log10(max(c.population, 10).toDouble())
                if (km <= maxKm && score < bestScore) { bestScore = score; best = c }
            }
        }
        return best
    }

    /** Cities whose name starts with [query], largest first, then ones merely containing it. */
    fun search(query: String, limit: Int = 30): List<City> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val starts = ArrayList<City>()
        val contains = ArrayList<City>()
        for (c in cities) {
            val n = c.name.lowercase()
            if (n.startsWith(q)) starts += c else if (q.length > 2 && q in n) contains += c
            if (starts.size >= limit) break
        }
        return (starts + contains).take(limit)
    }

    private companion object {
        val NONE = Any()
        fun bin(lat: Double, lng: Double): Int =
            floor(lat.coerceIn(-90.0, 90.0) + 90).toInt() * 400 + floor(((lng + 540) % 360)).toInt()
    }
}

object Geo {
    @Volatile private var index: GeoIndex? = null
    @Volatile private var land: List<FloatArray>? = null

    /** Parses the bundled city table; slow on first call, so call it off the main thread. */
    fun index(context: Context): GeoIndex = index ?: synchronized(this) {
        index ?: context.assets.open("geo/cities.txt").bufferedReader().use { parseCities(it.lineSequence()) }.let(::GeoIndex).also { index = it }
    }

    internal fun parseCities(lines: Sequence<String>): List<City> {
        val regions = ArrayList<Pair<String, String>>()
        val cities = ArrayList<City>(35_000)
        for (line in lines) {
            val p = line.split('|')
            if (p[0] == "R" && p.size == 3) regions += p[1] to p[2]
            else if (p.size == 5) {
                val region = regions[p[4].toInt()]
                cities += City(p[0], p[1].toDouble(), p[2].toDouble(), p[3].toInt(), region.first, region.second)
            }
        }
        return cities
    }

    /** Coastlines as rings of x, y pairs in normalised map space, see [mapX] and [mapY]. */
    fun land(context: Context): List<FloatArray> = land ?: synchronized(this) {
        land ?: DataInputStream(context.assets.open("geo/land.bin").buffered()).use { input ->
            List(input.readInt()) {
                val n = input.readInt()
                FloatArray(n * 2).also { ring ->
                    for (i in 0 until n) {
                        val lng = input.readShort() / 32767.0 * 180
                        val lat = input.readShort() / 32767.0 * 90
                        ring[2 * i] = mapX(lng).toFloat()
                        ring[2 * i + 1] = mapY(lat).toFloat()
                    }
                }
            }
        }.also { land = it }
    }

    /** Web Mercator, 0..1 across the world. */
    fun mapX(lng: Double): Double = (lng + 180) / 360

    fun mapY(lat: Double): Double {
        val clamped = min(MAX_LAT, max(-MAX_LAT, lat)) * PI / 180
        return (1 - ln(tan(clamped) + 1 / cos(clamped)) / PI) / 2
    }

    private const val MAX_LAT = 85.0
}

fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = (lat2 - lat1) * PI / 180
    val dLng = (lng2 - lng1) * PI / 180
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * 6371 * asin(sqrt(a))
}
