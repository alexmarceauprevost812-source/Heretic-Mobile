package ca.heretic.inference;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the exact production JNI bridge on the host; does not emulate Android. */
public final class LlamaEngine {
    static { System.loadLibrary("heretic"); }
    private native long create();
    private native void reset(long h);
    private native void abort(long h);
    private native void destroy(long h);
    private native void run(long h, String path, String[] roles, byte[][] text, Sink sink);
    public interface Sink { void onBytes(byte[] bytes); }
    public static void main(String[] args) {
        LlamaEngine engine = new LlamaEngine();
        long handle = engine.create();
        try {
            AtomicInteger tokens = new AtomicInteger();
            engine.reset(handle);
            engine.run(handle, args[0], new String[]{"user"}, new byte[][]{"Once upon a time".getBytes(StandardCharsets.UTF_8)}, bytes -> {
                if (tokens.incrementAndGet() == 5) engine.abort(handle);
            });
            if (tokens.get() != 5) throw new AssertionError("Streaming/stop: " + tokens.get());
            engine.reset(handle);
            String longText = "hello ".repeat(2100);
            try {
                engine.run(handle, args[0], new String[]{"user"}, new byte[][]{longText.getBytes(StandardCharsets.UTF_8)}, bytes -> {});
                throw new AssertionError("Expected context overflow");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("longue")) throw expected;
            }
            engine.reset(handle);
            try {
                engine.run(handle, "/nonexistent/model.gguf", new String[]{"user"}, new byte[][]{"test".getBytes(StandardCharsets.UTF_8)}, bytes -> {});
                throw new AssertionError("Expected model error");
            } catch (IllegalStateException expected) { /* controlled load error */ }
            System.out.println("PASS: JNI streaming, stop after 5 tokens, context overflow, missing model");
        } finally { engine.destroy(handle); }
    }
}
