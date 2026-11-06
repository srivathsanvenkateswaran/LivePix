package com.srivathsan.livepix.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** What memory-making needs to know about one item. */
class MemoryItem(
    val key: String,
    val date: Long,
    val city: City? = null,
    val favorite: Boolean = false,
    val hash: Long = 0,
    val people: List<String> = emptyList(),
    val pet: Boolean = false,
    val hasFace: Boolean = false,
)

data class Memory(val id: String, val title: String, val subtitle: String, val keys: List<String>, val cover: String)

/** Makes the Memories collection: trips, this day in past years, people, months, and years. */
object MemoryMaker {
    private const val DAY = 86_400_000L
    private const val MOMENT_GAP = 36 * 3_600_000L
    private const val MIN_TRIP_ITEMS = 8
    private const val AWAY_KM = 100.0
    private const val MIN_ON_THIS_DAY = 5
    private const val MIN_MONTH = 25
    private const val MIN_YEAR = 40
    private const val MIN_PERSON = 12
    private const val MIN_PETS = 8
    private const val HIGHLIGHTS = 30
    private const val MONTHS_BACK = 6

    fun make(items: List<MemoryItem>, now: Long = System.currentTimeMillis()): List<Memory> {
        if (items.isEmpty()) return emptyList()
        val sorted = items.sortedBy { it.date }
        return onThisDay(sorted, now) + trips(sorted) + people(sorted) + months(sorted, now) + years(sorted, now) + pets(sorted)
    }

    /** The home town: where photos were taken on the most different days. */
    internal fun home(items: List<MemoryItem>): City? =
        items.filter { it.city != null }.groupBy { it.city }
            .maxByOrNull { (_, list) -> list.map { dayNumber(it.date) }.distinct().size }?.key

    private fun trips(items: List<MemoryItem>): List<Memory> {
        val home = home(items)
        val moments = ArrayList<MutableList<MemoryItem>>()
        for (item in items) {
            if (moments.isEmpty() || item.date - moments.last().last().date > MOMENT_GAP) moments += ArrayList<MemoryItem>()
            moments.last() += item
        }
        return moments.filter { it.size >= MIN_TRIP_ITEMS }.mapNotNull { moment ->
            val placed = moment.mapNotNull { it.city }
            if (placed.isEmpty() || placed.size * 2 < moment.size) return@mapNotNull null
            val away = placed.filter { home == null || distanceKm(home.lat, home.lng, it.lat, it.lng) > AWAY_KM }
            if (away.size * 10 < placed.size * 6) return@mapNotNull null
            memory("trip:${moment.first().date}", tripTitle(away), range(moment.first().date, moment.last().date), moment)
        }.reversed()
    }

    private fun tripTitle(cities: List<City>): String {
        val ranked = cities.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        return when {
            ranked.size == 1 -> ranked[0].name
            ranked.size == 2 -> "${ranked[0].name} & ${ranked[1].name}"
            ranked.map { it.region }.distinct().size == 1 && ranked[0].region.isNotEmpty() -> ranked[0].region
            ranked.map { it.country }.distinct().size == 1 -> ranked[0].country
            else -> "${ranked[0].name} & More"
        }
    }

    private fun onThisDay(items: List<MemoryItem>, now: Long): List<Memory> {
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val matches = items.filter { item ->
            val c = Calendar.getInstance().apply { timeInMillis = item.date }
            c.get(Calendar.YEAR) < today.get(Calendar.YEAR) && kotlin.math.abs(c.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)) <= 2
        }
        if (matches.size < MIN_ON_THIS_DAY) return emptyList()
        return listOf(memory("today:${dayNumber(now)}", "On This Day", SimpleDateFormat("d MMMM", Locale.getDefault()).format(now), matches))
    }

    private fun people(items: List<MemoryItem>): List<Memory> {
        val names = items.flatMap { it.people }.distinct()
        return names.mapNotNull { name ->
            val theirs = items.filter { name in it.people }
            val days = theirs.map { dayNumber(it.date) }.distinct().size
            if (theirs.size < MIN_PERSON || days < 3) null
            else memory("person:$name", "Time with $name", range(theirs.first().date, theirs.last().date), theirs)
        }
    }

    private fun months(items: List<MemoryItem>, now: Long): List<Memory> {
        val format = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val cutoff = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, -MONTHS_BACK) }.timeInMillis
        val current = format.format(now)
        return items.filter { it.date >= cutoff }.groupBy { format.format(it.date) }
            .filter { (name, list) -> name != current && list.size >= MIN_MONTH }
            .map { (name, list) -> memory("month:$name", name, "Month in Review", list) }
            .reversed()
    }

    private fun years(items: List<MemoryItem>, now: Long): List<Memory> {
        val thisYear = year(now)
        return items.groupBy { year(it.date) }
            .filter { (y, list) -> y != thisYear && list.size >= MIN_YEAR }
            .map { (y, list) -> memory("year:$y", y.toString(), "Year in Review", list) }
            .reversed()
    }

    private fun pets(items: List<MemoryItem>): List<Memory> {
        val pets = items.filter { it.pet }
        if (pets.size < MIN_PETS) return emptyList()
        return listOf(memory("pets", "Furry Friends", range(pets.first().date, pets.last().date), pets))
    }

    private fun memory(id: String, title: String, subtitle: String, items: List<MemoryItem>): Memory {
        val picks = highlights(items)
        return Memory(id, title, subtitle, picks.map { it.key }, cover(picks).key)
    }

    /**
     * Spreads picks evenly through time. Each stretch contributes a favourite if it has one, and
     * otherwise whichever picture looks least like the previous pick, so bursts don't repeat.
     */
    internal fun highlights(items: List<MemoryItem>): List<MemoryItem> {
        val out = ArrayList<MemoryItem>()
        val slots = minOf(HIGHLIGHTS, items.size)
        for (slot in 0 until slots) {
            val from = slot * items.size / slots
            val to = (slot + 1) * items.size / slots
            val stretch = items.subList(from, to)
            val previous = out.lastOrNull()?.hash ?: 0L
            val pick = stretch.maxByOrNull { (if (it.favorite) 100 else 0) + java.lang.Long.bitCount(it.hash xor previous) + (if (it.hasFace) 4 else 0) }!!
            // Drop near-repeats unless the memory would get too short.
            if (previous != 0L && pick.hash != 0L && !pick.favorite && items.size > HIGHLIGHTS / 2 &&
                java.lang.Long.bitCount(pick.hash xor previous) < 6
            ) continue
            out += pick
        }
        return out
    }

    private fun cover(picks: List<MemoryItem>): MemoryItem =
        picks.firstOrNull { it.favorite } ?: picks.firstOrNull { it.hasFace } ?: picks[picks.size / 2]

    private fun dayNumber(ms: Long): Long = (ms + java.util.TimeZone.getDefault().getOffset(ms)) / DAY
    private fun year(ms: Long): Int = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR)

    private fun range(first: Long, last: Long): String {
        val full = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val a = full.format(first)
        val b = full.format(last)
        if (a == b) return a
        val sameYear = year(first) == year(last)
        return "${if (sameYear) SimpleDateFormat("d MMM", Locale.getDefault()).format(first) else a} – $b"
    }
}
