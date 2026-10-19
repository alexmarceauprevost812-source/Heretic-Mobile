package ca.heretic.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDiscoveryTest {
    @Test fun findsNestedModelsAndAvoidsCyclesAndDuplicates() {
        val tree = mapOf(
            "root" to listOf(ModelEntry("sub", "IA", directory = true), ModelEntry("z", "zeta.GGUF", 50), ModelEntry("txt", "note.txt")),
            "sub" to listOf(ModelEntry("a", "alpha.gguf", 20), ModelEntry("root", "loop", directory = true), ModelEntry("z", "zeta.GGUF", 50))
        )
        val result = ModelDiscovery().scan("root", { tree[it].orEmpty() }, { false })
        assertEquals(listOf("alpha.gguf", "zeta.GGUF"), result.map { it.name })
        assertEquals("IA", result.first().folder)
        assertEquals("sub", result.first().parentId)
        assertEquals(20L, result.first().size)
    }
    @Test fun emptyFolderDoesNotInventModels() {
        assertTrue(ModelDiscovery().scan("root", { emptyList() }, { false }).isEmpty())
    }
    @Test(expected = IllegalStateException::class) fun cancellationStopsBeforeReadingProvider() {
        ModelDiscovery().scan("root", { error("Should not query provider") }, { true })
    }
    @Test(expected = IllegalStateException::class) fun oversizedFolderRequiresNarrowerSelection() {
        ModelDiscovery(1).scan("root", { listOf(ModelEntry("a", "a.gguf"), ModelEntry("b", "b.gguf")) }, { false })
    }
}
