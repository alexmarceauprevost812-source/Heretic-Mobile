package ca.heretic.core
import org.junit.Assert.assertEquals
import org.junit.Test
class Utf8StreamTest {
    @Test fun splitFrenchAndEmoji() {
        val input = "Été, français 🧡".toByteArray()
        val decoder = Utf8Stream()
        assertEquals("Été, français 🧡", input.joinToString("") { decoder.append(byteArrayOf(it)) })
    }
    @Test fun everyPossibleBoundary() {
        val text = "À bientôt — réponse locale 🧡"
        val bytes = text.toByteArray()
        for (split in 0..bytes.size) {
            val decoder = Utf8Stream()
            assertEquals(text, decoder.append(bytes.copyOfRange(0, split)) + decoder.append(bytes.copyOfRange(split, bytes.size)))
        }
    }
    @Test fun invalidByteDoesNotBlockFollowingText() {
        val decoder = Utf8Stream()
        assertEquals("\uFFFDbonjour", decoder.append(byteArrayOf(0xFF.toByte()) + "bonjour".toByteArray()))
    }
    @Test fun keepsAsciiStreaming() {
        val decoder = Utf8Stream()
        assertEquals("bon", decoder.append("bon".toByteArray()))
        assertEquals("jour", decoder.append("jour".toByteArray()))
    }
}
