package com.srivathsan.livepix.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import com.srivathsan.livepix.data.FaceFacts
import com.srivathsan.livepix.data.ImageFacts
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * The on-device models: YuNet finds faces, SFace turns each into a vector for grouping people, and
 * MobileNetV2 names what is in the picture. Everything runs locally; pictures never leave the phone.
 */
class Vision(context: Context) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    private val detector = session(context, "yunet.onnx")
    private val recognizer = session(context, "sface_int8.onnx")
    private val classifier = session(context, "mobilenet_int8.onnx")
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun session(context: Context, name: String): OrtSession =
        env.createSession(context.assets.open("models/$name").use { it.readBytes() }, options)

    fun analyze(bitmap: Bitmap): ImageFacts {
        val (hash, color) = fingerprint(bitmap)
        val probabilities = classify(bitmap)
        val top = probabilities.indices.sortedByDescending { probabilities[it] }.take(3).filter { probabilities[it] >= MIN_LABEL }
        var dog = 0f
        var cat = 0f
        for (i in SceneLabels.DOGS) dog += probabilities[i]
        for (i in SceneLabels.CATS) cat += probabilities[i]
        val pet = when {
            dog >= MIN_PET -> ImageFacts.DOG
            cat >= MIN_PET -> ImageFacts.CAT
            else -> ImageFacts.NO_PET
        }
        return ImageFacts(hash, color, pet, top.toIntArray(), faces(bitmap))
    }

    /** dHash of the picture, plus its average colour. */
    private fun fingerprint(bitmap: Bitmap): Pair<Long, Int> {
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        val pixels = IntArray(72)
        small.getPixels(pixels, 0, 9, 0, 0, 9, 8)
        if (small !== bitmap) small.recycle()
        return ImageHash.dHash(pixels) to ImageHash.averageColor(pixels)
    }

    private fun classify(bitmap: Bitmap): FloatArray {
        val side = min(bitmap.width, bitmap.height)
        // The usual centre crop: the middle 224/256 of the shorter side.
        val crop = side * 224f / 256f
        val left = (bitmap.width - crop) / 2
        val top = (bitmap.height - crop) / 2
        val input = Bitmap.createBitmap(CLASSIFIER_SIZE, CLASSIFIER_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(input).drawBitmap(
            bitmap,
            Matrix().apply { setRectToRect(RectF(left, top, left + crop, top + crop), RectF(0f, 0f, 224f, 224f), Matrix.ScaleToFit.FILL) },
            paint,
        )
        val pixels = IntArray(CLASSIFIER_SIZE * CLASSIFIER_SIZE)
        input.getPixels(pixels, 0, CLASSIFIER_SIZE, 0, 0, CLASSIFIER_SIZE, CLASSIFIER_SIZE)
        input.recycle()
        val n = pixels.size
        val data = FloatArray(n * 3)
        for (i in 0 until n) {
            val p = pixels[i]
            data[i] = (((p shr 16) and 255) / 255f - 0.485f) / 0.229f
            data[n + i] = (((p shr 8) and 255) / 255f - 0.456f) / 0.224f
            data[2 * n + i] = ((p and 255) / 255f - 0.406f) / 0.225f
        }
        return FaceMath.softmax(run(classifier, data, CLASSIFIER_SIZE))
    }

    private fun faces(bitmap: Bitmap): List<FaceFacts> {
        val scale = DETECTOR_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val input = Bitmap.createBitmap(DETECTOR_SIZE, DETECTOR_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(input).apply {
            scale(scale, scale)
            drawBitmap(bitmap, 0f, 0f, paint)
        }
        val pixels = IntArray(DETECTOR_SIZE * DETECTOR_SIZE)
        input.getPixels(pixels, 0, DETECTOR_SIZE, 0, 0, DETECTOR_SIZE, DETECTOR_SIZE)
        input.recycle()
        val n = pixels.size
        // YuNet takes BGR, 0..255.
        val data = FloatArray(n * 3)
        for (i in 0 until n) {
            val p = pixels[i]
            data[i] = (p and 255).toFloat()
            data[n + i] = ((p shr 8) and 255).toFloat()
            data[2 * n + i] = ((p shr 16) and 255).toFloat()
        }
        val levels = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, DETECTOR_SIZE.toLong(), DETECTOR_SIZE.toLong())).use { tensor ->
            detector.run(mapOf("input" to tensor)).use { out ->
                fun read(name: String): FloatArray {
                    val buffer = (out.get(name).get() as OnnxTensor).floatBuffer
                    return FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
                listOf(8, 16, 32).map { s -> FaceMath.Level(s, read("cls_$s"), read("obj_$s"), read("bbox_$s"), read("kps_$s")) }
            }
        }
        return FaceMath.decode(levels, DETECTOR_SIZE, MIN_FACE_SCORE)
            .filter { it.w >= MIN_FACE_PX }
            .take(MAX_FACES)
            .map { f ->
                val landmarks = FloatArray(10) { f.landmarks[it] / scale }
                FaceFacts(
                    x = f.x / scale / bitmap.width, y = f.y / scale / bitmap.height,
                    w = f.w / scale / bitmap.width, h = f.h / scale / bitmap.height,
                    score = f.score, embedding = embed(bitmap, landmarks),
                )
            }
    }

    private fun embed(bitmap: Bitmap, landmarks: FloatArray): FloatArray {
        val aligned = Bitmap.createBitmap(RECOGNIZER_SIZE, RECOGNIZER_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(aligned).drawBitmap(bitmap, Matrix().apply { setValues(FaceMath.alignment(landmarks) + floatArrayOf(0f, 0f, 1f)) }, paint)
        val pixels = IntArray(RECOGNIZER_SIZE * RECOGNIZER_SIZE)
        aligned.getPixels(pixels, 0, RECOGNIZER_SIZE, 0, 0, RECOGNIZER_SIZE, RECOGNIZER_SIZE)
        aligned.recycle()
        val n = pixels.size
        val data = FloatArray(n * 3)
        for (i in 0 until n) {
            val p = pixels[i]
            data[i] = ((p shr 16) and 255).toFloat()
            data[n + i] = ((p shr 8) and 255).toFloat()
            data[2 * n + i] = (p and 255).toFloat()
        }
        return FaceMath.normalize(run(recognizer, data, RECOGNIZER_SIZE))
    }

    /** Runs a model with one 1x3xNxN input and one output. */
    private fun run(session: OrtSession, data: FloatArray, size: Int): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, size.toLong(), size.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                val buffer = (out.get(0) as OnnxTensor).floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }

    override fun close() {
        detector.close(); recognizer.close(); classifier.close(); options.close()
    }

    private companion object {
        const val DETECTOR_SIZE = 640
        const val RECOGNIZER_SIZE = 112
        const val CLASSIFIER_SIZE = 224
        const val MIN_FACE_SCORE = 0.8f
        // Faces smaller than this, at 640 px across the photo, are too small to recognise.
        const val MIN_FACE_PX = 28f
        const val MAX_FACES = 12
        const val MIN_LABEL = 0.15f
        const val MIN_PET = 0.6f
    }
}

object ImageHash {
    /** 64-bit difference hash from a 9x8 ARGB image: one bit per horizontal neighbour pair. */
    fun dHash(pixels: IntArray): Long {
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (luma(pixels[y * 9 + x]) > luma(pixels[y * 9 + x + 1])) hash = hash or (1L shl bit)
            bit++
        }
        return hash
    }

    fun averageColor(pixels: IntArray): Int {
        var r = 0; var g = 0; var b = 0
        for (p in pixels) { r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255 }
        return ((r / pixels.size) shl 16) or ((g / pixels.size) shl 8) or (b / pixels.size)
    }

    private fun luma(p: Int) = 299 * ((p shr 16) and 255) + 587 * ((p shr 8) and 255) + 114 * (p and 255)
}

/** What people call things, mapped onto the classifier's ImageNet classes. */
object SceneLabels {
    val DOGS = 151..268
    val CATS = 281..285

    private val SYNONYMS = mapOf(
        "dog" to DOGS.toList(),
        "puppy" to DOGS.toList(),
        "cat" to CATS.toList(),
        "kitten" to CATS.toList(),
        "pet" to DOGS.toList() + CATS.toList(),
        "beach" to listOf(978, 975),
        "sea" to listOf(978, 975),
        "lake" to listOf(975),
        "mountain" to listOf(970, 979, 980, 972, 976),
        "valley" to listOf(979),
        "cliff" to listOf(972),
        "volcano" to listOf(980),
        "bird" to (7..24).toList() + (80..100).toList() + (127..146).toList(),
        "car" to listOf(436, 468, 511, 609, 627, 656, 661, 705, 717, 734, 751, 817),
        "bike" to listOf(444, 671),
        "motorcycle" to listOf(665, 670),
        "boat" to listOf(472, 554, 625, 814, 871, 914),
        "plane" to listOf(404, 895),
        "train" to listOf(466, 547, 565, 829),
        "food" to listOf(925, 926, 927, 928, 930, 931, 932, 933, 934, 935, 936, 937, 938, 943, 959, 960, 961, 962, 963, 964, 965, 967, 969),
        "coffee" to listOf(504, 967, 968),
        "flower" to listOf(985, 986),
        "temple" to listOf(832, 668),
        "church" to listOf(497),
    )

    /** ImageNet class indices matching a search term: synonyms, then words in the class names. */
    fun matches(query: String, names: List<String>): Set<Int> {
        val q = query.trim().lowercase()
        if (q.length < 3) return emptySet()
        val out = HashSet<Int>(SYNONYMS[q].orEmpty())
        val word = Regex("""\b${Regex.escape(q)}\b""")
        names.forEachIndexed { i, name -> if (word.containsMatchIn(name.lowercase())) out += i }
        return out
    }
}
