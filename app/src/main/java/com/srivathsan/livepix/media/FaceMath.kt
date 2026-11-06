package com.srivathsan.livepix.media

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A face found by the detector, in the pixels of the detector's input. */
class RawFace(val score: Float, val x: Float, val y: Float, val w: Float, val h: Float, val landmarks: FloatArray)

/** The maths around the face models, kept free of Android so it can be unit tested. */
object FaceMath {
    /** One stride's outputs from YuNet: flattened cls, obj, bbox (4 per cell) and kps (10 per cell). */
    class Level(val stride: Int, val cls: FloatArray, val obj: FloatArray, val bbox: FloatArray, val kps: FloatArray)

    fun decode(levels: List<Level>, inputSize: Int, minScore: Float): List<RawFace> {
        val faces = ArrayList<RawFace>()
        for (level in levels) {
            val columns = inputSize / level.stride
            for (i in level.cls.indices) {
                val score = sqrt(level.cls[i].coerceIn(0f, 1f) * level.obj[i].coerceIn(0f, 1f))
                if (score < minScore) continue
                val row = i / columns
                val col = i % columns
                val s = level.stride.toFloat()
                val cx = (col + level.bbox[4 * i]) * s
                val cy = (row + level.bbox[4 * i + 1]) * s
                val w = exp(level.bbox[4 * i + 2]) * s
                val h = exp(level.bbox[4 * i + 3]) * s
                val landmarks = FloatArray(10) { k ->
                    (level.kps[10 * i + k] + (if (k % 2 == 0) col else row)) * s
                }
                faces += RawFace(score, cx - w / 2, cy - h / 2, w, h, landmarks)
            }
        }
        return nonMaxSuppression(faces)
    }

    /** Keeps the strongest of any overlapping detections. */
    fun nonMaxSuppression(faces: List<RawFace>, maxOverlap: Float = 0.3f): List<RawFace> {
        val kept = ArrayList<RawFace>()
        for (f in faces.sortedByDescending { it.score }) {
            if (kept.none { overlap(it, f) > maxOverlap }) kept += f
        }
        return kept
    }

    private fun overlap(a: RawFace, b: RawFace): Float {
        val w = min(a.x + a.w, b.x + b.w) - max(a.x, b.x)
        val h = min(a.y + a.h, b.y + b.h) - max(a.y, b.y)
        if (w <= 0 || h <= 0) return 0f
        val shared = w * h
        return shared / (a.w * a.h + b.w * b.h - shared)
    }

    /** Where the eyes, nose and mouth corners sit in the 112 px image the recogniser expects. */
    private val ALIGNED = floatArrayOf(
        38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f,
    )

    /**
     * The similarity transform (no shear) taking [landmarks] onto the aligned positions, by least
     * squares. Returns scaleX, skewX, transX, skewY, scaleY, transY, the order of Matrix.setValues.
     */
    fun alignment(landmarks: FloatArray): FloatArray {
        var sx = 0f; var sy = 0f; var dx = 0f; var dy = 0f
        for (i in 0 until 5) {
            sx += landmarks[2 * i]; sy += landmarks[2 * i + 1]
            dx += ALIGNED[2 * i]; dy += ALIGNED[2 * i + 1]
        }
        sx /= 5; sy /= 5; dx /= 5; dy /= 5
        var norm = 0f; var a = 0f; var b = 0f
        for (i in 0 until 5) {
            val px = landmarks[2 * i] - sx
            val py = landmarks[2 * i + 1] - sy
            val qx = ALIGNED[2 * i] - dx
            val qy = ALIGNED[2 * i + 1] - dy
            norm += px * px + py * py
            a += px * qx + py * qy
            b += px * qy - py * qx
        }
        a /= norm; b /= norm
        return floatArrayOf(a, -b, dx - a * sx + b * sy, b, a, dy - b * sx - a * sy)
    }

    fun normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val n = sqrt(sum)
        if (n > 0) for (i in v.indices) v[i] /= n
        return v
    }

    /** Dot product; equals cosine similarity for normalised vectors. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    fun softmax(logits: FloatArray): FloatArray {
        val top = logits.max()
        val out = FloatArray(logits.size) { exp(logits[it] - top) }
        val total = out.sum()
        for (i in out.indices) out[i] /= total
        return out
    }
}
