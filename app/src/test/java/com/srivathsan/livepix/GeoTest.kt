package com.srivathsan.livepix

import com.srivathsan.livepix.data.Geo
import com.srivathsan.livepix.data.GeoIndex
import com.srivathsan.livepix.data.distanceKm
import com.srivathsan.livepix.data.parseIso6709
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoTest {
    private val index = GeoIndex(
        Geo.parseCities(
            sequenceOf(
                "R|Kerala|India", "R|Tamil Nadu|India",
                "Kochi|9.939|76.260|600000|0", "Thrippunithura|9.950|76.350|90000|0", "Chennai|13.087|80.278|4600000|1",
            ),
        ),
    )

    @Test fun nearestPrefersTheLargerPlaceNearby() {
        assertEquals("Kochi", index.nearest(9.945, 76.30)?.name)
        assertEquals("Chennai", index.nearest(13.0, 80.2)?.name)
    }

    @Test fun nothingNearbyMeansNoPlace() = assertNull(index.nearest(0.0, 0.0))

    @Test fun nearestWorksAcrossTheDateLine() {
        val fiji = GeoIndex(Geo.parseCities(sequenceOf("R||Fiji", "Levuka|-17.680|179.990|8000|0")))
        assertEquals("Levuka", fiji.nearest(-17.68, -179.99)?.name)
    }

    @Test fun searchFavoursPrefixes() {
        assertEquals("Chennai", index.search("chen").first().name)
        assertEquals("Kerala", index.search("kochi").first().region)
    }

    @Test fun parsesIso6709() {
        val p = parseIso6709("+37.5090-122.2500/")!!
        assertEquals(37.509, p.lat, 1e-6)
        assertEquals(-122.25, p.lng, 1e-6)
    }

    @Test fun distanceIsAboutRight() = assertEquals(111.0, distanceKm(0.0, 0.0, 1.0, 0.0), 1.0)
}
