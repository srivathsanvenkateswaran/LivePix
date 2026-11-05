package com.srivathsan.livepix.media

/**
 * Minimal HEIF (ISO/IEC 23008-12) reader. It only parses the `meta` box, which is enough to
 * find the primary image, its rotation, and the auxiliary images iPhone stores next to it:
 * the depth map (`urn:mpeg:hevc:2015:auxid:2`) and the portrait-effects matte.
 */
class HeifParser private constructor(private val meta: ByteArray) {

    data class Extent(val offset: Long, val length: Long)

    data class Item(
        val id: Int,
        val type: String,
        var width: Int = 0,
        var height: Int = 0,
        var auxType: String? = null,
        var hvcC: ByteArray? = null,
        /** Counter-clockwise rotation in quarter turns, per the `irot` property. */
        var rotation: Int = 0,
        /** 0 = vertical axis (left-right flip), 1 = horizontal axis, null = none. */
        var mirrorAxis: Int? = null,
        var constructionMethod: Int = 0,
        val extents: MutableList<Extent> = mutableListOf(),
    )

    var primaryId: Int = -1; private set
    val items = LinkedHashMap<Int, Item>()
    /** auxl references: aux item id -> the items it describes. */
    val auxOf = HashMap<Int, List<Int>>()
    /** dimg references: grid item id -> its tiles in row-major order. */
    val tilesOf = HashMap<Int, List<Int>>()
    /** thmb references: thumbnail item id -> the items it previews. */
    val thumbnailOf = HashMap<Int, List<Int>>()
    private var idat: ByteArray = ByteArray(0)

    val primary: Item? get() = items[primaryId]

    /** Depth/disparity map attached to the primary image, if any. */
    val depthItem: Item?
        get() = auxItems().firstOrNull { it.auxType in DEPTH_URNS && it.type == "hvc1" }

    val portraitMatteItem: Item?
        get() = auxItems().firstOrNull { it.auxType == PORTRAIT_MATTE_URN && it.type == "hvc1" }

    /** The embedded preview image for the primary, typically 320x240. */
    val primaryThumbnail: Item?
        get() = thumbnailOf.entries.firstOrNull { primaryId in it.value }?.let { items[it.key] }?.takeIf { it.type == "hvc1" }

    private fun auxItems(): List<Item> =
        items.values.filter { it.auxType != null && auxOf[it.id]?.contains(primaryId) != false }

    private fun parse() {
        val m = meta
        val propList = ArrayList<Pair<String, IntRange>>()
        val assoc = HashMap<Int, List<Int>>()
        // meta is a FullBox: skip version/flags.
        for (b in m.boxes(4, m.size)) {
            when (b.type) {
                "pitm" -> primaryId = if (m[b.start].toInt() == 0) m.u16(b.start + 4) else m.u32(b.start + 4).toInt()
                "iinf" -> {
                    val v = m[b.start].toInt()
                    val first = b.start + 4 + if (v == 0) 2 else 4
                    for (infe in m.boxes(first, b.end)) {
                        if (infe.type != "infe") continue
                        val iv = m[infe.start].toInt()
                        if (iv < 2) continue
                        var q = infe.start + 4
                        val id = if (iv == 2) m.u16(q).also { q += 2 } else m.u32(q).toInt().also { q += 4 }
                        q += 2 // protection index
                        items[id] = Item(id, m.fourCC(q))
                    }
                }
                "iloc" -> parseIloc(b)
                "idat" -> idat = m.copyOfRange(b.start, b.end)
                "iref" -> {
                    val w = if (m[b.start].toInt() == 0) 2 else 4
                    for (r in m.boxes(b.start + 4, b.end)) {
                        val from = m.uN(r.start, w).toInt()
                        val n = m.u16(r.start + w)
                        val to = (0 until n).map { m.uN(r.start + w + 2 + it * w, w).toInt() }
                        when (r.type) {
                            "auxl" -> auxOf[from] = to
                            "dimg" -> tilesOf[from] = to
                            "thmb" -> thumbnailOf[from] = to
                        }
                    }
                }
                "iprp" -> for (c in m.boxes(b.start, b.end)) {
                    when (c.type) {
                        "ipco" -> m.boxes(c.start, c.end).forEach { propList += it.type to (it.start until it.end) }
                        "ipma" -> {
                            val v = m[c.start].toInt()
                            val flags = m.uN(c.start + 1, 3).toInt()
                            var p = c.start + 4
                            val n = m.u32(p).toInt(); p += 4
                            repeat(n) {
                                val id = if (v < 1) m.u16(p).also { p += 2 } else m.u32(p).toInt().also { p += 4 }
                                val k = m.u8(p); p += 1
                                val list = ArrayList<Int>(k)
                                repeat(k) {
                                    if (flags and 1 != 0) { list += m.u16(p) and 0x7FFF; p += 2 } else { list += m.u8(p) and 0x7F; p += 1 }
                                }
                                assoc[id] = list
                            }
                        }
                    }
                }
            }
        }
        for ((id, indices) in assoc) {
            val item = items[id] ?: continue
            for (index in indices) {
                if (index == 0 || index > propList.size) continue
                val (type, range) = propList[index - 1]
                val s = range.first
                when (type) {
                    "ispe" -> { item.width = m.u32(s + 4).toInt(); item.height = m.u32(s + 8).toInt() }
                    "auxC" -> {
                        var e = s + 4
                        while (e < range.last + 1 && m[e].toInt() != 0) e++
                        item.auxType = String(m, s + 4, e - s - 4, Charsets.US_ASCII)
                    }
                    "hvcC" -> item.hvcC = m.copyOfRange(s, range.last + 1)
                    "irot" -> item.rotation = m.u8(s) and 3
                    "imir" -> item.mirrorAxis = m.u8(s) and 1
                }
            }
        }
    }

    private fun parseIloc(b: Box) {
        val m = meta
        val v = m[b.start].toInt()
        var p = b.start + 4
        val offsetSize = m.u8(p) shr 4
        val lengthSize = m.u8(p) and 15
        val baseSize = m.u8(p + 1) shr 4
        val indexSize = if (v == 1 || v == 2) m.u8(p + 1) and 15 else 0
        p += 2
        val count = if (v < 2) m.u16(p).also { p += 2 } else m.u32(p).toInt().also { p += 4 }
        repeat(count) {
            val id = if (v < 2) m.u16(p).also { p += 2 } else m.u32(p).toInt().also { p += 4 }
            var method = 0
            if (v == 1 || v == 2) { method = m.u16(p) and 15; p += 2 }
            p += 2 // data reference index
            val base = m.uN(p, baseSize); p += baseSize
            val extentCount = m.u16(p); p += 2
            val extents = ArrayList<Extent>(extentCount)
            repeat(extentCount) {
                p += indexSize
                val off = m.uN(p, offsetSize); p += offsetSize
                val len = m.uN(p, lengthSize); p += lengthSize
                extents += Extent(base + off, len)
            }
            pending[id] = method to extents
        }
    }

    private val pending = HashMap<Int, Pair<Int, List<Extent>>>()

    private fun attachLocations() {
        for ((id, loc) in pending) {
            val item = items[id] ?: continue
            item.constructionMethod = loc.first
            item.extents += loc.second
        }
    }

    /** Raw coded bytes of an item stored in the file (construction method 0). */
    fun readItemData(source: ByteSource, item: Item): ByteArray {
        if (item.constructionMethod == 1) {
            val e = item.extents.first()
            return idat.copyOfRange(e.offset.toInt(), (e.offset + e.length).toInt())
        }
        require(item.constructionMethod == 0) { "item ${item.id} uses construction method ${item.constructionMethod}" }
        val total = item.extents.sumOf { it.length }.toInt()
        val out = ByteArray(total)
        var p = 0
        for (e in item.extents) {
            val chunk = source.read(e.offset, e.length.toInt())
            chunk.copyInto(out, p)
            p += chunk.size
        }
        return out
    }

    companion object {
        const val PORTRAIT_MATTE_URN = "urn:com:apple:photo:2018:aux:portraiteffectsmatte"
        val DEPTH_URNS = setOf("urn:mpeg:hevc:2015:auxid:2", "urn:mpeg:mpegB:cicp:systems:auxiliary:depth")

        /** Largest meta box we are willing to load; iPhone files are ~10–40 KB. */
        private const val MAX_META = 4 * 1024 * 1024

        /** Returns null for files that are not HEIF or whose meta box cannot be found. */
        fun parse(source: ByteSource): HeifParser? {
            var offset = 0L
            var sawFtyp = false
            while (offset + 8 <= source.size) {
                val h = source.read(offset, 16)
                if (h.size < 8) return null
                var size = h.u32(0)
                val type = h.fourCC(4)
                var header = 8
                if (size == 1L) { size = h.uN(8, 8); header = 16 } else if (size == 0L) size = source.size - offset
                if (size < header) return null
                if (offset == 0L) {
                    if (type != "ftyp") return null
                    sawFtyp = true
                }
                if (type == "meta" && sawFtyp) {
                    if (size > MAX_META) return null
                    val body = source.read(offset + header, (size - header).toInt())
                    return runCatching { HeifParser(body).apply { parse(); attachLocations() } }.getOrNull()
                }
                offset += size
            }
            return null
        }
    }
}
