package ca.heretic.data
import ca.heretic.core.GenerationSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class SettingsJsonTest {
    @Test fun roundTripPreservesEverySettingAndPersonality() {
        val s = GenerationSettings(temperature = 1.2f, topP = 0.95f, maxTokens = 800, threads = 2,
            instruction = "Consigne\nfrançaise", topK = 12, minP = 0.07f, repeatPenalty = 1.15f,
            repeatLastN = 128, seed = 4294967294L, contextSize = 4096, language = "français", tone = "concis", specialty = "code")
        assertEquals(s, SettingsJson.decode(JSONObject(SettingsJson.encode(s).toString())))
    }
    @Test fun legacySettingsKeepTheirValues() {
        val j = JSONObject("""{"temperature":0.2,"topP":0.8,"maxTokens":128,"threads":2,"instruction":"Bonjour"}""")
        val s = SettingsJson.decode(j)
        assertEquals(0.2f, s.temperature); assertEquals(128, s.maxTokens)
        assertEquals("Bonjour", s.instruction); assertEquals(2048, s.contextSize)
        assertEquals(-1L, s.seed)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidImportedContext() {
        SettingsJson.decode(SettingsJson.encode(GenerationSettings()).put("contextSize", 128))
    }
}
