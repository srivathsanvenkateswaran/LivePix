package com.srivathsan.livepix.data

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream

class FaceFacts(val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val embedding: FloatArray)

/** What the on-device models found in one picture. */
class ImageFacts(val hash: Long, val color: Int, val pet: Int, val labels: IntArray, val faces: List<FaceFacts>) {
    companion object {
        const val NO_PET = 0
        const val CAT = 1
        const val DOG = 2
    }
}

/**
 * Keeps model results across launches, keyed by file version. An append-only binary file: analysis
 * can stop at any moment, and a half-written last record is simply dropped on the next load.
 */
class VisionStore(context: Context) {
    private val file = File(context.filesDir, "vision-v$VERSION.bin")
    private val facts = HashMap<String, ImageFacts>()
    private var pending = ArrayList<Pair<String, ImageFacts>>()

    init { load() }

    fun snapshot(): Map<String, ImageFacts> = synchronized(this) { HashMap(facts) }
    fun has(versionKey: String): Boolean = synchronized(this) { versionKey in facts }

    fun put(versionKey: String, value: ImageFacts) = synchronized(this) {
        facts[versionKey] = value
        pending += versionKey to value
        if (pending.size >= FLUSH_EVERY) flush()
    }

    fun flush() = synchronized(this) {
        if (pending.isEmpty()) return@synchronized
        runCatching {
            DataOutputStream(FileOutputStream(file, true).buffered()).use { out -> pending.forEach { (k, v) -> write(out, k, v) } }
        }
        pending = ArrayList()
    }

    /** Rewrites the file without entries for files that no longer exist. */
    fun compact(liveKeys: Set<String>) = synchronized(this) {
        flush()
        val stale = facts.keys.count { it !in liveKeys }
        if (stale < 50 || stale * 5 < facts.size) return@synchronized
        facts.keys.retainAll(liveKeys)
        runCatching {
            DataOutputStream(FileOutputStream(file, false).buffered()).use { out -> facts.forEach { (k, v) -> write(out, k, v) } }
        }
    }

    private fun write(out: DataOutputStream, key: String, f: ImageFacts) {
        out.writeUTF(key)
        out.writeLong(f.hash)
        out.writeInt(f.color)
        out.writeByte(f.pet)
        out.writeByte(f.labels.size)
        f.labels.forEach { out.writeShort(it) }
        out.writeByte(f.faces.size)
        for (face in f.faces) {
            out.writeFloat(face.x); out.writeFloat(face.y); out.writeFloat(face.w); out.writeFloat(face.h); out.writeFloat(face.score)
            out.writeShort(face.embedding.size)
            face.embedding.forEach { out.writeFloat(it) }
        }
    }

    private fun load() {
        if (!file.exists()) return
        var truncated = false
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                try {
                    while (input.available() > 0) {
                        val key = input.readUTF()
                        facts[key] = read(input)
                    }
                } catch (_: EOFException) {
                    truncated = true
                }
            }
        }.onFailure { truncated = true }
        if (truncated) runCatching {
            DataOutputStream(FileOutputStream(file, false).buffered()).use { out -> facts.forEach { (k, v) -> write(out, k, v) } }
        }
    }

    private fun read(input: DataInputStream): ImageFacts {
        val hash = input.readLong()
        val color = input.readInt()
        val pet = input.readByte().toInt()
        val labels = IntArray(input.readByte().toInt()) { input.readShort().toInt() }
        val faces = List(input.readByte().toInt()) {
            val x = input.readFloat(); val y = input.readFloat(); val w = input.readFloat(); val h = input.readFloat()
            val score = input.readFloat()
            FaceFacts(x, y, w, h, score, FloatArray(input.readShort().toInt()) { input.readFloat() })
        }
        return ImageFacts(hash, color, pet, labels, faces)
    }

    private companion object {
        const val VERSION = 1
        const val FLUSH_EVERY = 25
    }
}
