package ca.heretic.inference

import ca.heretic.core.InferenceEngine
import ca.heretic.core.Message
import ca.heretic.core.Utf8Stream

/** generate/close run on one worker; stop is safe from the UI thread. */
class LlamaEngine : InferenceEngine {
    private var handle = create()
    @Synchronized override fun prepare() { check(handle != 0L); reset(handle) }
    @Synchronized override fun stop() { if (handle != 0L) abort(handle) }
    @Synchronized override fun close() { if (handle != 0L) { destroy(handle); handle = 0L } }
    override fun generate(path: String, messages: List<Message>, onText: (String) -> Unit) {
        val decoder = Utf8Stream()
        run(handle, path, messages.map { it.role }.toTypedArray(), messages.map { it.text.toByteArray() }.toTypedArray(),
            object : TokenSink { override fun onBytes(bytes: ByteArray) { decoder.append(bytes).takeIf { it.isNotEmpty() }?.let(onText) } })
    }
    private external fun create(): Long
    private external fun reset(handle: Long)
    private external fun abort(handle: Long)
    private external fun destroy(handle: Long)
    private external fun run(handle: Long, path: String, roles: Array<String>, content: Array<ByteArray>, sink: TokenSink)
    companion object { init { System.loadLibrary("heretic") } }
}
interface TokenSink { fun onBytes(bytes: ByteArray) }
