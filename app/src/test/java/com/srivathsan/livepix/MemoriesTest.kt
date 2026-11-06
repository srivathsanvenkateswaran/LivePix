package com.srivathsan.livepix

import com.srivathsan.livepix.data.City
import com.srivathsan.livepix.data.MemoryItem
import com.srivathsan.livepix.data.MemoryMaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class MemoriesTest {
    private val chennai = City("Chennai", 13.08, 80.27, 4_600_000, "Tamil Nadu", "India")
    private val munnar = City("Munnar", 10.08, 77.06, 30_000, "Kerala", "India")
    private val kochi = City("Kochi", 9.93, 76.26, 600_000, "Kerala", "India")
    private val alleppey = City("Alappuzha", 9.49, 76.33, 170_000, "Kerala", "India")
    private val paris = City("Paris", 48.85, 2.35, 2_100_000, "Île-de-France", "France")
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun at(year: Int, month: Int, date: Int, hourOfDay: Int = 12) =
        Calendar.getInstance().apply { clear(); set(year, month, date, hourOfDay, 0) }.timeInMillis

    private var next = 0
    private fun item(ms: Long, city: City?, favorite: Boolean = false, people: List<String> = emptyList(), pet: Boolean = false) =
        MemoryItem("k${next++}", ms, city, favorite, (next * 2654435761L), people, pet)

    /** A library at home over many days, so home is clear. */
    private fun homeDays() = List(30) { item(at(2024, 0, 1) + it * 2 * day, chennai) }
    private val now get() = at(2026, 10, 6)

    @Test fun homeIsWhereMostDaysWereSpent() {
        val manyInOneDay = List(50) { item(at(2025, 5, 1) + it * 60_000L, paris) }
        assertEquals("Chennai", MemoryMaker.home(homeDays() + manyInOneDay)?.name)
    }

    @Test fun aTripIsNamedAfterItsPlace() {
        val trip = List(10) { item(at(2025, 3, 1) + it * 5 * hour, munnar) }
        val m = MemoryMaker.make(homeDays() + trip, now).first { it.id.startsWith("trip") }
        assertEquals("Munnar", m.title)
        assertEquals(10, m.keys.size)
    }

    @Test fun aTripAcrossARegionIsNamedAfterTheRegion() {
        val cities = listOf(munnar, kochi, alleppey)
        val trip = List(12) { item(at(2025, 3, 1) + it * 5 * hour, cities[it % 3]) }
        assertEquals("Kerala", MemoryMaker.make(homeDays() + trip, now).first { it.id.startsWith("trip") }.title)
    }

    @Test fun aBusyWeekAtHomeIsNotATrip() {
        val week = List(20) { item(at(2025, 3, 1) + it * 4 * hour, chennai) }
        assertFalse(MemoryMaker.make(homeDays() + week, now).any { it.id.startsWith("trip") })
    }

    @Test fun onThisDayLooksAtEarlierYearsOnly() {
        val lastYear = List(6) { item(at(2025, 10, 6) + it * hour, null) }
        val thisYear = List(6) { item(at(2026, 10, 6) + it * hour, null) }
        val m = MemoryMaker.make(lastYear + thisYear, now).single { it.title == "On This Day" }
        assertEquals(lastYear.map { it.key }.toSet(), m.keys.toSet())
    }

    @Test fun namedPeopleGetAMemoryWhenSeenOverSeveralDays() {
        val photos = List(15) { item(at(2025, 1, 1) + it * day, null, people = listOf("Asha")) }
        assertTrue(MemoryMaker.make(photos, now).any { it.title == "Time with Asha" })
    }

    @Test fun onePersonInOneDayIsNotEnough() {
        val photos = List(15) { item(at(2025, 1, 1) + it * 60_000L, null, people = listOf("Asha")) }
        assertFalse(MemoryMaker.make(photos, now).any { it.title == "Time with Asha" })
    }

    @Test fun highlightsAreCappedSpreadAndKeepFavourites() {
        val items = List(300) { item(at(2025, 1, 1) + it * hour, null, favorite = it == 155) }
        val picks = MemoryMaker.highlights(items)
        assertTrue(picks.size <= 30)
        assertTrue(picks.any { it.favorite })
        assertTrue(picks.last().date - picks.first().date > 200 * hour)
    }

    @Test fun anEmptyLibraryMakesNoMemories() = assertTrue(MemoryMaker.make(emptyList(), now).isEmpty())
}
