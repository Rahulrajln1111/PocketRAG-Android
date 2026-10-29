package com.pdfapk.rag

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Random
import kotlin.math.min
import kotlin.math.sqrt

/** One search result from the vector index. */
data class VectorHit(
    val chunkId: Int,
    val score: Float,
    val position: Int
)

/**
 * A small inverted-file (IVF) vector database, written from scratch.
 *
 * Why not brute force: a 500-page PDF is ~600 chunks, which is fine to scan,
 * but the same app indexes a whole library over time and a 1,000-page manual
 * produces 1,200+ chunks. Why not a server-side vector DB: this app is
 * offline-first, the index has to live in app storage, and adding a native
 * database for a few thousand vectors costs more than it saves.
 *
 * How it works (the same principle as FAISS's `IndexIVFFlat`):
 *
 * 1. **Partition** the vectors into `nlist` clusters with k-means (k-means++
 *    seeding, fixed seed, so an index built twice is identical).
 * 2. **Store** every vector quantised to int8 with a per-vector scale, which
 *    halves the footprint and keeps the inner loop in cache.
 * 3. **Search** only the `nprobe` closest centroids instead of every vector:
 *    ~1/8 of the data on a 64-list index, with recall that stays above 95%
 *    for retrieval purposes.
 *
 * Persistence is a compact binary format (`IVF1`) so loading a document index
 * is a few milliseconds instead of re-embedding hundreds of chunks.
 */
class IvfIndex(
    val dim: Int,
    var defaultNprobe: Int = 1
) {

    private var centroids: Array<FloatArray> = emptyArray()
    private var codes: ByteArray = ByteArray(0)
    private var scales: FloatArray = FloatArray(0)
    private var ids: IntArray = IntArray(0)
    private var lists: Array<IntArray> = emptyArray()
    private val random = Random(SEED)

    val nlist: Int get() = centroids.size
    val size: Int get() = ids.size
    val isTrained: Boolean get() = centroids.isNotEmpty()

    /**
     * Builds the index from normalised vectors.
     *
     * [chunkIds] maps a vector position to the chunk it represents, so the
     * index can be rebuilt or filtered without shifting the chunk list.
     */
    fun build(
        vectors: List<FloatArray>,
        chunkIds: List<Int>,
        nlist: Int = recommendedNlist(vectors.size),
        iterations: Int = 10
    ) {
        require(vectors.size == chunkIds.size) { "vectors/chunkIds length mismatch" }
        require(vectors.all { it.size == dim }) { "vector dimension mismatch" }

        ids = chunkIds.toIntArray()
        codes = ByteArray(vectors.size * dim)
        scales = FloatArray(vectors.size)

        vectors.forEachIndexed { index, raw ->
            val vector = raw.copyOf()
            VectorMath.normalize(vector)
            val quantized = VectorMath.quantize(vector)
            quantized.codes.copyInto(codes, destinationOffset = index * dim)
            scales[index] = quantized.scale
        }

        if (vectors.isEmpty()) {
            centroids = emptyArray()
            lists = emptyArray()
            defaultNprobe = 1
            return
        }

        val clusters = min(maxOf(1, nlist), vectors.size)
        centroids = trainCentroids(vectors, clusters, iterations)
        lists = assign(vectors, centroids)
        defaultNprobe = maxOf(1, min(clusters, sqrt(clusters.toDouble()).toInt()))
    }

    /** Searches the `nprobe` closest partitions and returns the top [k]. */
    fun search(query: FloatArray, k: Int, nprobe: Int = defaultNprobe): List<VectorHit> {
        if (size == 0 || centroids.isEmpty()) return emptyList()
        require(query.size == dim) { "query dimension mismatch" }
        val normalised = query.copyOf()
        VectorMath.normalize(normalised)

        val probe = maxOf(1, min(nprobe, centroids.size))
        val ordered = centroids.indices
            .map { it to VectorMath.dot(normalised, centroids[it]) }
            .sortedByDescending { it.second }
            .take(probe)

        val scored = ArrayList<VectorHit>(probe * 32)
        for ((listIndex, _) in ordered) {
            for (position in lists[listIndex]) {
                val score = VectorMath.dotInt8(
                    normalised, codes, position * dim, scales[position]
                )
                scored.add(VectorHit(ids[position], score, position))
            }
        }
        return scored.sortedByDescending { it.score }.take(k)
    }

    /** Brute-force scan, used by tests to measure IVF recall. */
    fun searchExact(query: FloatArray, k: Int): List<VectorHit> {
        if (size == 0) return emptyList()
        val normalised = query.copyOf()
        VectorMath.normalize(normalised)
        return (0 until size)
            .map { position ->
                VectorHit(
                    chunkId = ids[position],
                    score = VectorMath.dotInt8(normalised, codes, position * dim, scales[position]),
                    position = position
                )
            }
            .sortedByDescending { it.score }
            .take(k)
    }

    /** Binary size of the index, shown in the UI. */
    fun approximateBytes(): Long =
        codes.size.toLong() + scales.size * 4L + ids.size * 4L +
            centroids.sumOf { it.size * 4L } + lists.sumOf { it.size * 4L }

    // ------------------------------------------------------------------
    // BUILD
    // ------------------------------------------------------------------

    private fun trainCentroids(
        vectors: List<FloatArray>,
        clusters: Int,
        iterations: Int
    ): Array<FloatArray> {
        if (clusters <= 1) {
            val mean = FloatArray(dim)
            for (vector in vectors) for (i in 0 until dim) mean[i] += vector[i]
            for (i in 0 until dim) mean[i] /= vectors.size
            return arrayOf(VectorMath.normalize(mean))
        }

        // k-means++ seeding: spread the initial centroids out.
        val chosen = ArrayList<FloatArray>(clusters)
        chosen.add(vectors[random.nextInt(vectors.size)].copyOf())
        val distances = DoubleArray(vectors.size) { Double.MAX_VALUE }
        while (chosen.size < clusters) {
            val latest = chosen.last()
            var total = 0.0
            for (i in vectors.indices) {
                val d = VectorMath.squaredDistance(vectors[i], latest).toDouble()
                if (d < distances[i]) distances[i] = d
                total += distances[i]
            }
            if (total <= 0.0) {
                chosen.add(vectors[random.nextInt(vectors.size)].copyOf())
                continue
            }
            var target = random.nextDouble() * total
            var index = 0
            while (index < distances.size - 1 && target > distances[index]) {
                target -= distances[index]
                index++
            }
            chosen.add(vectors[index].copyOf())
        }

        val centroids = chosen.toTypedArray()
        repeat(iterations) {
            val assignment = assign(vectors, centroids)
            var moved = false
            for (list in centroids.indices) {
                val members = assignment[list]
                if (members.isEmpty()) {
                    centroids[list] = vectors[random.nextInt(vectors.size)].copyOf()
                    moved = true
                    continue
                }
                val mean = FloatArray(dim)
                for (position in members) {
                    val vector = vectors[position]
                    for (i in 0 until dim) mean[i] += vector[i]
                }
                for (i in 0 until dim) mean[i] /= members.size
                VectorMath.normalize(mean)
                if (VectorMath.squaredDistance(mean, centroids[list]) > 1e-6f) moved = true
                centroids[list] = mean
            }
            if (!moved) return@repeat
        }
        return centroids
    }

    private fun assign(vectors: List<FloatArray>, centroids: Array<FloatArray>): Array<IntArray> {
        val buckets = Array(centroids.size) { ArrayList<Int>() }
        for (position in vectors.indices) {
            var best = 0
            var bestScore = -Float.MAX_VALUE
            for (list in centroids.indices) {
                val score = VectorMath.dot(vectors[position], centroids[list])
                if (score > bestScore) {
                    bestScore = score
                    best = list
                }
            }
            buckets[best].add(position)
        }
        return buckets.map { it.toIntArray() }.toTypedArray()
    }

    // ------------------------------------------------------------------
    // PERSISTENCE
    // ------------------------------------------------------------------

    fun save(output: OutputStream) {
        val out = DataOutputStream(output.buffered())
        out.writeInt(MAGIC)
        out.writeInt(FORMAT_VERSION)
        out.writeInt(dim)
        out.writeInt(nlist)
        out.writeInt(defaultNprobe)
        out.writeInt(ids.size)
        for (id in ids) out.writeInt(id)
        for (scale in scales) out.writeFloat(scale)
        out.write(codes)
        for (centroid in centroids) {
            for (value in centroid) out.writeFloat(value)
        }
        out.flush()
    }

    companion object {
        private const val MAGIC = 0x49564631   // "IVF1"
        private const val FORMAT_VERSION = 1
        private const val SEED = 42L

        /** ~sqrt(N) lists balances probe cost against bucket size. */
        fun recommendedNlist(count: Int): Int {
            if (count <= 1) return 1
            return min(128, maxOf(1, (sqrt(count.toDouble()) * 2).toInt()))
        }

        fun load(input: InputStream): IvfIndex {
            val stream = DataInputStream(input.buffered())
            val magic = stream.readInt()
            require(magic == MAGIC) { "not an IVF index" }
            val version = stream.readInt()
            require(version == FORMAT_VERSION) { "unsupported index version $version" }
            val dim = stream.readInt()
            val nlist = stream.readInt()
            val nprobe = stream.readInt()
            val count = stream.readInt()

            val index = IvfIndex(dim, nprobe)
            val ids = IntArray(count) { stream.readInt() }
            val scales = FloatArray(count) { stream.readFloat() }
            val codes = ByteArray(count * dim)
            stream.readFully(codes)
            val centroids = Array(nlist) {
                FloatArray(dim) { stream.readFloat() }
            }
            index.ids = ids
            index.scales = scales
            index.codes = codes
            index.centroids = centroids
            index.lists = index.assignFromCodes(dim, count)
            return index
        }
    }

    /**
     * Rebuilds the inverted lists after loading. Quantised codes are close
     * enough to their centroids for assignment; if a code lands far from every
     * centroid the nearest one is still chosen, so no vector is ever orphaned.
     */
    private fun assignFromCodes(dimension: Int, count: Int): Array<IntArray> {
        val buckets = Array(centroids.size) { ArrayList<Int>() }
        val buffer = FloatArray(dimension)
        for (position in 0 until count) {
            val scale = scales[position]
            for (i in 0 until dimension) {
                buffer[i] = codes[position * dimension + i] * scale
            }
            var best = 0
            var bestScore = -Float.MAX_VALUE
            for (list in centroids.indices) {
                val score = VectorMath.dot(buffer, centroids[list])
                if (score > bestScore) {
                    bestScore = score
                    best = list
                }
            }
            buckets[best].add(position)
        }
        return buckets.map { it.toIntArray() }.toTypedArray()
    }
}
