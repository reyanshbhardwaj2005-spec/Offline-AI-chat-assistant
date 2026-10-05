package com.example.aichatclient;

import java.nio.charset.StandardCharsets;

public class LlamaBridge {
    static {
        System.loadLibrary("llama_jni");
    }

    public interface TokenCallback {
        void onToken(byte[] utf8Piece);
    }

    private long ctxPtr = 0;

    private native long nativeLoad(String modelPath, int nCtx, int nThreads);
    private native int nativeGenerate(long ctx, byte[] prompt, int maxTokens,
                                      float temperature, TokenCallback cb);
    private native void nativeStop();
    private native void nativeFree(long ctx);

    public boolean load(String modelPath, int nCtx, int nThreads) {
        ctxPtr = nativeLoad(modelPath, nCtx, nThreads);
        return ctxPtr != 0;
    }

    /** Returns number of generated tokens, or a negative error code. */
    public int generate(String prompt, int maxTokens, float temperature, TokenCallback cb) {
        if (ctxPtr == 0) return -1;
        return nativeGenerate(ctxPtr, prompt.getBytes(StandardCharsets.UTF_8),
                maxTokens, temperature, cb);
    }

    public void stop() {
        nativeStop();
    }

    public boolean isLoaded() {
        return ctxPtr != 0;
    }

    public void close() {
        if (ctxPtr != 0) {
            nativeFree(ctxPtr);
            ctxPtr = 0;
        }
    }
}