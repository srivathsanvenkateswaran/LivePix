package com.srivathsan.livepix

import com.srivathsan.livepix.data.DuplicateCandidate
import com.srivathsan.livepix.data.Duplicates
import com.srivathsan.livepix.data.FaceClusterer
import com.srivathsan.livepix.data.FaceFacts
import com.srivathsan.livepix.data.FaceRef
import com.srivathsan.livepix.data.PersonRecord
import com.srivathsan.livepix.media.FaceMath
import com.srivathsan.livepix.media.ImageHash
import com.srivathsan.livepix.media.RawFace
import com.srivathsan.livepix.media.SceneLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class VisionLogicTest {
    @Test fun decodesAFaceFromTheCellItWasFoundIn() {
        val cells = 80 * 80
        val cls = FloatArray(cells); val obj = FloatArray(cells)
        val bbox = FloatArray(cells * 4); val kps = FloatArray(cells * 10)
        val i = 10 * 80 + 20 // row 10, column 20
        cls[i] = 0.9f; obj[i] = 0.9f
        bbox[4 * i] = 0.5f; bbox[4 * i + 1] = 0.5f // centre of the cell
        val face = FaceMath.decode(listOf(FaceMath.Level(8, cls, obj, bbox, kps)), 640, 0.8f).single()
        assertEquals(0.9f, face.score, 1e-3f)
        assertEquals(20.5f * 8 - 4, face.x, 1e-3f) // log-size 0 means width = stride
        assertEquals(10.5f * 8 - 4, face.y, 1e-3f)
        assertEquals(160f, face.landmarks[0], 1e-3f)
        assertEquals(80f, face.landmarks[1], 1e-3f)
    }

    @Test fun overlappingDetectionsCollapseToTheStrongest() {
        val weak = RawFace(0.8f, 0f, 0f, 100f, 100f, FloatArray(10))
        val strong = RawFace(0.9f, 5f, 5f, 100f, 100f, FloatArray(10))
        val apart = RawFace(0.85f, 300f, 0f, 100f, 100f, FloatArray(10))
        assertEquals(listOf(strong, apart), FaceMath.nonMaxSuppression(listOf(weak, strong, apart)))
    }

    @Test fun alignmentMapsLandmarksOntoTheTemplate() {
        // The template itself, shifted and doubled in size.
        val template = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)
        val input = FloatArray(10) { template[it] * 2 + if (it % 2 == 0) 100f else 50f }
        val m = FaceMath.alignment(input)
        assertEquals(0.5f, m[0], 1e-3f)
        assertEquals(0f, m[1], 1e-3f)
        assertEquals(template[0], m[0] * input[0] + m[1] * input[1] + m[2], 1e-2f)
        assertEquals(template[1], m[3] * input[0] + m[4] * input[1] + m[5], 1e-2f)
    }

    @Test fun dHashSeesGradientDirection() {
        val rising = IntArray(72) { val v = it % 9 * 25; (v shl 16) or (v shl 8) or v }
        val falling = IntArray(72) { val v = 200 - it % 9 * 25; (v shl 16) or (v shl 8) or v }
        assertEquals(0L, ImageHash.dHash(rising))
        assertEquals(-1L, ImageHash.dHash(falling))
    }

    private fun candidate(key: String, hash: Long, date: Long, w: Int = 4000, h: Int = 3000, size: Long = 1000, color: Int = 0x808080, rich: Boolean = false) =
        DuplicateCandidate(key, hash, color, w, h, size, date, rich)

    @Test fun sameMomentReencodesAreCopiesAndBurstsAreNot() {
        val hash = 0x0F0F_3C3C_5A5A_1234L
        val original = candidate("a", hash, 1_000_000)
        val reencoded = candidate("b", hash xor 0b111, 1_000_500, size = 400)
        val burst = candidate("c", hash xor 0b11000, 1_004_000)
        val groups = Duplicates.find(listOf(original, reencoded, burst))
        assertEquals(listOf(listOf("a", "b")), groups.map { g -> g.map { it.key } })
    }

    @Test fun identicalPicturesFromDifferentTimesAreCopies() {
        val hash = 0x7777_1111_2222_3333L
        val groups = Duplicates.find(listOf(candidate("a", hash, 0), candidate("b", hash, 9_000_000_000)))
        assertEquals(1, groups.size)
    }

    @Test fun differentShapesAreNeverCopies() {
        val hash = 0x7777_1111_2222_3333L
        assertTrue(Duplicates.find(listOf(candidate("a", hash, 0), candidate("b", hash, 0, w = 3000, h = 3000))).isEmpty())
    }

    @Test fun livePhotoIsKeptOverALargerFlatCopy() {
        val hash = 0x7777_1111_2222_3333L
        val flat = candidate("flat", hash, 0, w = 8000, h = 6000)
        val live = candidate("live", hash, 0, rich = true)
        assertEquals("live", Duplicates.find(listOf(flat, live)).single().first().key)
    }

    @Test fun sceneSearchUsesSynonymsAndClassNames() {
        val names = List(1000) { "class $it" }.toMutableList().apply { this[3] = "tiger shark" }
        assertTrue(281 in SceneLabels.matches("cat", names))
        assertEquals(setOf(3), SceneLabels.matches("shark", names))
        assertTrue(SceneLabels.matches("ar", names).isEmpty())
    }

    // Faces of a person: a shared direction plus noise, like embeddings of one identity.
    private val random = Random(7)
    private fun identity() = FaceMath.normalize(FloatArray(128) { random.nextGaussian().toFloat() })
    private fun faceOf(identity: FloatArray, id: String): FaceRef {
        val v = FaceMath.normalize(FloatArray(128) { identity[it] + 0.06f * random.nextGaussian().toFloat() })
        return FaceRef(id, id.substringBefore('#'), FaceFacts(0f, 0f, 0.2f, 0.2f, 0.9f, v))
    }

    @Test fun groupsFacesByIdentity() {
        val a = identity(); val b = identity()
        val faces = List(10) { faceOf(a, "a$it#0") } + List(10) { faceOf(b, "b$it#0") }
        val result = FaceClusterer.cluster(faces.shuffled(random), emptyList())
        assertEquals(2, result.records.size)
        assertEquals(1, faces.take(10).map { result.assignment[it.id] }.distinct().size)
        assertNotEquals(result.assignment["a0#0"], result.assignment["b0#0"])
    }

    @Test fun aNamedPersonKeepsTheirIdentityAcrossRuns() {
        val a = identity()
        val first = FaceClusterer.cluster(List(5) { faceOf(a, "a$it#0") }, emptyList())
        val named = first.records.single().copy(name = "Asha")
        val second = FaceClusterer.cluster(List(8) { faceOf(a, "a$it#0") }, listOf(named))
        assertEquals("Asha", second.records.single().name)
        assertEquals(named.id, second.records.single().id)
    }

    @Test fun rejectedFacesStayOutOfThatPerson() {
        val a = identity()
        val person = PersonRecord("p", "Asha", listOf(a), setOf("a0#0"))
        val result = FaceClusterer.cluster(List(4) { faceOf(a, "a$it#0") }, listOf(person))
        assertNotEquals("p", result.assignment["a0#0"])
        assertEquals("p", result.assignment["a1#0"])
    }
}
