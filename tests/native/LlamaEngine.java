package ca.heretic.inference;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs the exact production JNI bridge on the host; does not emulate Android. */
public final class LlamaEngine {
    static { System.loadLibrary("heretic"); }
    private native long create();
    private native void reset(long h);
    private native void abort(long h);
    private native void destroy(long h);
    private native void run(long h, String path, String[] roles, byte[][] text, Sink sink, float temperature, float topP, int maxTokens, int threads, int topK, float minP, float repeatPenalty, int repeatLastN, long seed, int contextSize);
    private native String inspect(String path);
    private native void quantize(String source, String destination, int format);
    private void infer(long h, String path, String[] roles, byte[][] text, Sink sink) {
        run(h, path, roles, text, sink, 0.7f, 0.9f, 512, 4, 40, 0.0f, 1.0f, 64, -1, 2048);
    }
    public interface Sink { void onBytes(byte[] bytes); }
    public static void main(String[] args) throws Exception {
        LlamaEngine engine = new LlamaEngine();
        long handle = engine.create();
        try {
            if (!engine.inspect(args[0]).contains("reconnues")) throw new AssertionError("Metadata missing");
            String[] seeded = new String[2];
            for (int round = 0; round < 2; round++) {
                engine.reset(handle);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                engine.run(handle, args[0], new String[]{"user"}, new byte[][]{"Once upon a time".getBytes(StandardCharsets.UTF_8)},
                    bytes -> out.write(bytes, 0, bytes.length), 0.8f, 0.95f, 16, 2, 12, 0.05f, 1.1f, 32, 42, 1024);
                seeded[round] = out.toString(StandardCharsets.UTF_8);
            }
            if (seeded[0].isEmpty() || !seeded[0].equals(seeded[1])) throw new AssertionError("Seeded advanced sampling not reproducible");
            AtomicInteger tokens = new AtomicInteger();
            engine.reset(handle);
            engine.infer(handle, args[0], new String[]{"user"}, new byte[][]{"Once upon a time".getBytes(StandardCharsets.UTF_8)}, bytes -> {
                if (tokens.incrementAndGet() == 5) engine.abort(handle);
            });
            if (tokens.get() != 5) throw new AssertionError("Streaming/stop: " + tokens.get());
            byte[] sourceHash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(args[0])));
            for (int format : new int[]{4, 5, 8}) {
                Path output = Path.of(args[0] + ".Q" + format + ".test.gguf");
                try {
                    engine.quantize(args[0], output.toString(), format);
                    if (Files.size(output) <= 4) throw new AssertionError("Empty quantized model");
                    tokens.set(0);
                    engine.reset(handle);
                    engine.infer(handle, output.toString(), new String[]{"user"}, new byte[][]{"Once upon a time".getBytes(StandardCharsets.UTF_8)}, bytes -> {
                        if (tokens.incrementAndGet() == 5) engine.abort(handle);
                    });
                    if (tokens.get() != 5) throw new AssertionError("Quantized model does not generate tokens");
                    System.out.println("PASS: Q" + format + " conversion and inference");
                } finally { Files.deleteIfExists(output); }
            }
            if (!Arrays.equals(sourceHash, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(args[0])))))
                throw new AssertionError("Source model changed");
            engine.reset(handle);
            try {
                engine.run(handle, args[0], new String[]{"user"}, new byte[][]{"test".getBytes(StandardCharsets.UTF_8)}, bytes -> {}, 0.7f, 0.9f, 0, 4, 40, 0.0f, 1.0f, 64, -1, 2048);
                throw new AssertionError("Expected settings error");
            } catch (IllegalStateException expected) { /* native bounds validated */ }
            engine.reset(handle);
            String longText = "hello ".repeat(2100);
            try {
                engine.infer(handle, args[0], new String[]{"user"}, new byte[][]{longText.getBytes(StandardCharsets.UTF_8)}, bytes -> {});
                throw new AssertionError("Expected context overflow");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().contains("longue")) throw expected;
            }
            engine.reset(handle);
            try {
                engine.infer(handle, "/nonexistent/model.gguf", new String[]{"user"}, new byte[][]{"test".getBytes(StandardCharsets.UTF_8)}, bytes -> {});
                throw new AssertionError("Expected model error");
            } catch (IllegalStateException expected) { /* controlled load error */ }
            System.out.println("PASS: JNI streaming, stop, settings bounds, Q4/Q5/Q8 conversions, source unchanged, context overflow, missing model");
        } finally { engine.destroy(handle); }
    }
}

