package com.srivathsan.livepix.data

/** Everything derived from the on-device models, ready for the screens to read. */
class VisionIndex(
    /** People with enough photos to show, named ones first. */
    val people: List<Person>,
    val cats: List<String>,
    val dogs: List<String>,
    /** Groups of copies, best copy first. */
    val duplicates: List<List<DuplicateCandidate>>,
    /** Classifier results per asset key. */
    val labels: Map<String, IntArray>,
    val labelNames: List<String>,
) {
    private val byId = people.associateBy { it.id }
    fun person(id: String): Person? = byId[id]

    companion object {
        private const val MIN_PERSON_PHOTOS = 3

        class Built(val index: VisionIndex, val records: List<PersonRecord>)

        fun build(
            assets: List<Asset>,
            traitsOf: (Asset) -> Traits?,
            facts: Map<String, ImageFacts>,
            known: List<PersonRecord>,
            labelNames: List<String>,
        ): Built {
            val faces = ArrayList<FaceRef>()
            val cats = ArrayList<String>()
            val dogs = ArrayList<String>()
            val labels = HashMap<String, IntArray>()
            val candidates = ArrayList<DuplicateCandidate>()
            for (a in assets) {
                val f = facts[a.versionKey] ?: continue
                f.faces.forEachIndexed { i, face -> faces += FaceRef("${a.versionKey}#$i", a.key, face) }
                if (f.pet == ImageFacts.CAT) cats += a.key
                if (f.pet == ImageFacts.DOG) dogs += a.key
                if (f.labels.isNotEmpty()) labels[a.key] = f.labels
                candidates += DuplicateCandidate(a.key, f.hash, f.color, a.width, a.height, a.size, a.originalDate ?: a.dateTaken, a.isLive || traitsOf(a)?.hasDepth == true)
            }
            val result = FaceClusterer.cluster(faces, known)
            val order = assets.withIndex().associate { (i, a) -> a.key to i }
            val members = faces.filter { it.id in result.assignment }.groupBy { result.assignment.getValue(it.id) }
            val people = result.records.mapNotNull { r ->
                val theirs = members[r.id].orEmpty()
                if (theirs.isEmpty()) return@mapNotNull null
                val cover = theirs.maxBy { it.face.score * it.face.w }
                val keys = theirs.map { it.assetKey }.distinct().sortedBy { order[it] ?: 0 }
                if (r.name == null && keys.size < MIN_PERSON_PHOTOS) return@mapNotNull null
                Person(r.id, r.name, keys, cover.assetKey, cover.face, theirs.groupBy({ it.assetKey }, { it.id }))
            }.sortedWith(compareByDescending<Person> { it.name != null }.thenByDescending { it.keys.size })
            return Built(VisionIndex(people, cats, dogs, Duplicates.find(candidates), labels, labelNames), result.records)
        }
    }
}
