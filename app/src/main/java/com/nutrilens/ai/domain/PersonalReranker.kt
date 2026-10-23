package com.nutrilens.ai.domain

/**
 * Re-ranks the model's scores using photos this user already labelled (their image embeddings),
 * same maths as ml/personalize_eval.py. Measured there: a dish the model got wrong and the user
 * corrected once is recognised in 96.7% of later photos (vs 18% before); dishes the user never
 * logged are unaffected (76.3% -> 77.0%).
 */
object PersonalReranker {
    const val K = 5
    const val THRESHOLD = 0.6f
    const val ALPHA_MAX = 0.6f

    class Memory(val label: String, val embedding: FloatArray)

    /**
     * Blends [scores] with a similarity-weighted vote of the [K] nearest memories whose cosine
     * similarity is at least [THRESHOLD]; the closer the best match, the more weight (up to
     * [ALPHA_MAX]). Returns [scores] unchanged when nothing similar is remembered.
     */
    fun rerank(scores: Map<String, Float>, embedding: FloatArray?, memory: List<Memory>): Map<String, Float> {
        if (embedding == null || memory.isEmpty()) return scores
        val near = memory
            .map { it to dot(it.embedding, embedding) }
            .sortedByDescending { it.second }
            .take(K)
            .filter { it.second >= THRESHOLD }
        if (near.isEmpty()) return scores
        val votes = HashMap<String, Float>()
        near.forEach { (m, sim) -> votes.merge(m.label, sim, Float::plus) }
        val total = votes.values.sum()
        val alpha = ALPHA_MAX * ((near.first().second - THRESHOLD) / (1f - THRESHOLD)).coerceAtMost(1f)
        return (scores.keys + votes.keys).associateWith { label ->
            (1 - alpha) * (scores[label] ?: 0f) + alpha * ((votes[label] ?: 0f) / total)
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
        return s
    }
}
