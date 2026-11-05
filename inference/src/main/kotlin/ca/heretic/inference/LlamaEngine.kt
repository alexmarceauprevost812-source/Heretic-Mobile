package ca.heretic.inference

import ca.heretic.core.GenerationSettings
import ca.heretic.core.InferenceEngine
import ca.heretic.core.Message
import ca.heretic.core.Utf8Stream

/** generate/close run on one worker; stop is safe from the UI thread. */
class LlamaEngine : InferenceEngine {
    private var handle = create()
    var tokenCount = 0; private set
    var firstTextMs: Long? = null; private set
    var elapsedMs = 0L; private set
    var tokensPerSecond = 0.0; private set
    fun summary(): String = "$tokenCount tokens • %.1f tokens/s • premier texte : %s ms".format(tokensPerSecond, firstTextMs ?: "—")
    @Synchronized override fun prepare() { check(handle != 0L); reset(handle) }
    @Synchronized override fun stop() { if (handle != 0L) abort(handle) }
    @Synchronized override fun close() { if (handle != 0L) { destroy(handle); handle = 0L } }
    override fun generate(path: String, messages: List<Message>, settings: GenerationSettings, onText: (String) -> Unit) {
        val decoder = Utf8Stream()
        tokenCount = 0; firstTextMs = null; tokensPerSecond = 0.0
        val start = System.nanoTime()
        var firstToken = 0L
        try {
        run(handle, path, messages.map { it.role }.toTypedArray(), messages.map { it.text.toByteArray() }.toTypedArray(),
            object : TokenSink { override fun onBytes(bytes: ByteArray) {
                val now = System.nanoTime()
                if (tokenCount++ == 0) firstToken = now
                decoder.append(bytes).takeIf { it.isNotEmpty() }?.let {
                    if (firstTextMs == null) firstTextMs = (now - start) / 1_000_000
                    onText(it)
                }
            } },
            settings.temperature, settings.topP, settings.maxTokens, settings.threads,
            settings.topK, settings.minP, settings.repeatPenalty, settings.repeatLastN, settings.seed, settings.contextSize)
        } finally {
            val end = System.nanoTime()
            elapsedMs = (end - start) / 1_000_000
            tokensPerSecond = if (tokenCount > 1 && end > firstToken) (tokenCount - 1) * 1e9 / (end - firstToken) else 0.0
        }
    }
    private external fun create(): Long
    private external fun reset(handle: Long)
    private external fun abort(handle: Long)
    private external fun destroy(handle: Long)
    private external fun run(handle: Long, path: String, roles: Array<String>, content: Array<ByteArray>, sink: TokenSink, temperature: Float, topP: Float, maxTokens: Int, threads: Int, topK: Int, minP: Float, repeatPenalty: Float, repeatLastN: Int, seed: Long, contextSize: Int)
    external fun inspect(path: String): String
    external fun quantize(source: String, destination: String, format: Int)
    companion object { init { System.loadLibrary("heretic") } }
}
interface TokenSink { fun onBytes(bytes: ByteArray) }

