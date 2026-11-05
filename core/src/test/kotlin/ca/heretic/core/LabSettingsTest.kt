package ca.heretic.core
import org.junit.Assert.*
import org.junit.Test
class LabSettingsTest {
    @Test fun personalityIsSystemTextAndDoesNotMutateHistory() {
        val history = listOf(Message("user", "Bonjour"))
        val messages = GenerationSettings(language = "français", tone = "concis", specialty = "code").messages(history)
        assertEquals("system", messages.first().role)
        assertTrue(messages.first().text.contains("français")); assertEquals(history, messages.drop(1))
    }
    @Test fun validatesAdvancedBoundaries() {
        val invalid = listOf<() -> GenerationSettings>(
            { GenerationSettings(minP = Float.NaN) }, { GenerationSettings(topK = -1) },
            { GenerationSettings(repeatPenalty = Float.POSITIVE_INFINITY) }, { GenerationSettings(seed = 4294967295L) },
            { GenerationSettings(contextSize = 511) }, { GenerationSettings(maxTokens = 1024, contextSize = 1024) })
        invalid.forEach { factory -> try { factory(); fail("Invalid settings accepted") } catch (_: IllegalArgumentException) {} }
        GenerationSettings(seed = 4294967294L, minP = 1f, topK = 0, contextSize = 16384, maxTokens = 4096)
    }
}
