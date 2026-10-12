package com.example.aichatclient;

import java.nio.charset.StandardCharsets;

public class LlamaBridge {
    static {
        System.loadLibrary("llama_jni");
    }

    public interface TokenCallback {
        void onToken(byte[] utf8Piece);
    }

    /** Placeholder that marks where the image goes inside the prompt text. */
    public static final String DEFAULT_MARKER = "<__media__>";

    private long ctxPtr = 0;
    private boolean visionReady = false;

    // ---- text model
    private native long nativeLoad(String modelPath, int nCtx, int nThreads);
    private native int nativeGenerate(long ctx, byte[] prompt, int maxTokens,
                                      float temperature, TokenCallback cb);
    private native void nativeStop();
    private native void nativeFree(long ctx);

    // ---- image input (vision projector)
    private native boolean nativeLoadVision(long ctx, String mmprojPath, int nThreads);
    private native void nativeFreeVision(long ctx);
    private native int nativeGenerateImage(long ctx, byte[] prompt, byte[] rgb, int width,
                                           int height, int maxTokens, float temperature,
                                           TokenCallback cb);
    private native String nativeVisionMarker();

    public boolean load(String modelPath, int nCtx, int nThreads) {
        visionReady = false;
        ctxPtr = nativeLoad(modelPath, nCtx, nThreads);
        return ctxPtr != 0;
    }

    /** Loads the mmproj file that belongs to the loaded text model. */
    public boolean loadVision(String mmprojPath, int nThreads) {
        if (ctxPtr == 0) return false;
        visionReady = nativeLoadVision(ctxPtr, mmprojPath, nThreads);
        return visionReady;
    }

    public void unloadVision() {
        if (ctxPtr != 0) nativeFreeVision(ctxPtr);
        visionReady = false;
    }

    public boolean hasVision() {
        return ctxPtr != 0 && visionReady;
    }

    public String visionMarker() {
        try {
            String m = nativeVisionMarker();
            return (m == null || m.isEmpty()) ? DEFAULT_MARKER : m;
        } catch (Throwable t) {
            return DEFAULT_MARKER;
        }
    }

    /** Returns number of generated tokens, or a negative error code. */
    public int generate(String prompt, int maxTokens, float temperature, TokenCallback cb) {
        if (ctxPtr == 0) return -1;
        return nativeGenerate(ctxPtr, prompt.getBytes(StandardCharsets.UTF_8),
                maxTokens, temperature, cb);
    }

    /**
     * Prompt must contain the marker once. rgb = width*height*3 bytes.
     * Returns number of generated tokens, or a negative error code.
     */
    public int generateWithImage(String prompt, byte[] rgb, int width, int height,
                                 int maxTokens, float temperature, TokenCallback cb) {
        if (!hasVision()) return -1;
        return nativeGenerateImage(ctxPtr, prompt.getBytes(StandardCharsets.UTF_8),
                rgb, width, height, maxTokens, temperature, cb);
    }

    public void stop() {
        nativeStop();
    }

    public boolean isLoaded() {
        return ctxPtr != 0;
    }

    public void close() {
        if (ctxPtr != 0) {
            nativeFree(ctxPtr);      // also releases the vision projector
            ctxPtr = 0;
        }
        visionReady = false;
    }
}
