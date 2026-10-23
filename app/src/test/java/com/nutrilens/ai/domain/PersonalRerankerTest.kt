package com.nutrilens.ai.domain

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalRerankerTest {

    private fun unit(vararg v: Float): FloatArray {
        val n = sqrt(v.fold(0f) { a, x -> a + x * x })
        return FloatArray(v.size) { v[it] / n }
    }

    private val model = mapOf("pho" to 0.55f, "pizza" to 0.30f, "ramen" to 0.15f)

    @Test fun nearDuplicateOfACorrectedPhotoFlipsTheAnswer() {
        val memory = listOf(PersonalReranker.Memory("pizza", unit(1f, 0f, 0f)))
        val out = PersonalReranker.rerank(model, unit(0.98f, 0.1f, 0f), memory)
        assertEquals("pizza", out.maxBy { it.value }.key)
        assertEquals(1f, out.values.sum(), 1e-4f)
    }

    @Test fun dissimilarMemoriesChangeNothing() {
        val memory = listOf(PersonalReranker.Memory("pizza", unit(1f, 0f, 0f)))
        assertSame(model, PersonalReranker.rerank(model, unit(0f, 1f, 0f), memory))
    }

    @Test fun noEmbeddingOrNoMemoryChangesNothing() {
        assertSame(model, PersonalReranker.rerank(model, null, listOf(PersonalReranker.Memory("x", unit(1f)))))
        assertSame(model, PersonalReranker.rerank(model, unit(1f, 0f), emptyList()))
    }

    @Test fun weightGrowsWithSimilarity() {
        val memory = listOf(PersonalReranker.Memory("pizza", unit(1f, 0f)))
        val weak = PersonalReranker.rerank(model, unit(0.65f, 0.76f), memory)["pizza"]!!
        val strong = PersonalReranker.rerank(model, unit(0.95f, 0.31f), memory)["pizza"]!!
        assertTrue("$weak < $strong", weak < strong)
    }
}
