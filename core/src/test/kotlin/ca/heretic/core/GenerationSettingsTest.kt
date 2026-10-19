package ca.heretic.core
import org.junit.Assert.assertEquals
import org.junit.Test
class GenerationSettingsTest {
    @Test fun optionalInstructionDoesNotDuplicateOrAlterHistory() {
        val history = listOf(Message("user", "Bonjour"))
        assertEquals(history, GenerationSettings().messages(history))
        assertEquals(listOf(Message("system", "Réponds en français")) + history,
            GenerationSettings(instruction = " Réponds en français ").messages(history))
        assertEquals(1, history.size)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNonFiniteSampling() { GenerationSettings(temperature = Float.NaN) }
    @Test(expected = IllegalArgumentException::class) fun reservesRoomForPrompt() { GenerationSettings(maxTokens = 2048) }
    @Test(expected = IllegalArgumentException::class) fun boundsThreadCount() { GenerationSettings(threads = 0) }
}
