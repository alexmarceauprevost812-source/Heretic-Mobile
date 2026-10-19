package ca.heretic.core

data class Message(val role: String, val text: String)
data class Conversation(val id: String, val title: String, val messages: List<Message>)

/** A byte token can end in the middle of a UTF-8 character. */
class Utf8Stream {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
    private var pending = byteArrayOf()
    fun append(bytes: ByteArray): String {
        val input = java.nio.ByteBuffer.wrap(pending + bytes)
        val output = java.nio.CharBuffer.allocate(input.remaining() + 2)
        decoder.decode(input, output, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        output.flip()
        return output.toString()
    }
}

interface InferenceEngine : AutoCloseable {
    fun prepare()
    fun generate(path: String, messages: List<Message>, onText: (String) -> Unit)
    fun stop()
}
