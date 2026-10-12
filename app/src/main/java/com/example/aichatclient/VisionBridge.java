package com.example.aichatclient;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Vision (image + question) support on top of an already loaded LlamaBridge model. */
public class VisionBridge {

    /** Same shape as your existing token callback; a lambda works: bytes -> ... */
    public interface TokenCallback { void onToken(byte[] utf8); }

    /** Put this inside the user's turn so the model knows where the image goes. */
    public static final String MARKER = "<__media__>";

    // Same native library as LlamaBridge (vision_jni.cpp is compiled into it).
    // static { System.loadLibrary("YOUR_LIB_NAME"); }  // only if LlamaBridge doesn't load it first

    private long visionPtr = 0;
    private final long enginePtr;

    /** @param enginePtr the handle returned by LlamaBridge.nativeLoad */
    public VisionBridge(long enginePtr) { this.enginePtr = enginePtr; }

    public boolean isReady() { return visionPtr != 0 && nativeSupportsVision(visionPtr); }

    public boolean load(String mmprojPath, int threads) {
        close();
        visionPtr = nativeLoadVision(enginePtr, mmprojPath, threads);
        return visionPtr != 0;
    }

    /** Returns token count, or a negative error code (see getError). */
    public int generate(String prompt, ImageUtils.Rgb img, int maxTokens, float temp, TokenCallback cb) {
        if (visionPtr == 0) return -1;
        return nativeGenerate(enginePtr, visionPtr, prompt.getBytes(StandardCharsets.UTF_8),
                img.data, img.w, img.h, maxTokens, temp, cb);
    }

    public void stop() { /* call your LlamaBridge.stop(): both share the same stop flag */ }

    public void close() {
        if (visionPtr != 0) { nativeFreeVision(visionPtr); visionPtr = 0; }
    }

    public static String getError(int rc) {
        switch (rc) {
            case -1: return "Vision model not loaded";
            case -2: return "Image + prompt too long for the context size";
            case -3: return "Could not process the image";
            case -4: return "Decoding failed";
            case -5: return "Prompt is missing the image marker";
            default: return "Error " + rc;
        }
    }

    /** Finds a projector file (mmproj*.gguf) next to the model file. */
    public static File findMmproj(File modelFile) {
        File dir = modelFile.getParentFile();
        if (dir == null) return null;
        File[] list = dir.listFiles((d, n) -> n.toLowerCase().startsWith("mmproj") && n.endsWith(".gguf"));
        return (list != null && list.length > 0) ? list[0] : null;
    }

    /** Copies a picked mmproj file into app storage and returns it. */
    public static File importMmproj(Context c, Uri uri) throws Exception {
        File out = new File(c.getFilesDir(), "mmproj-imported.gguf");
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        return out;
    }

    private native long nativeLoadVision(long enginePtr, String path, int threads);
    private native boolean nativeSupportsVision(long visionPtr);
    private native int nativeGenerate(long enginePtr, long visionPtr, byte[] prompt, byte[] rgb,
                                      int w, int h, int maxTokens, float temp, TokenCallback cb);
    private native void nativeFreeVision(long visionPtr);
}
