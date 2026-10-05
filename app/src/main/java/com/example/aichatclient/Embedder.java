package com.example.aichatclient;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

public class Embedder {

    private static final int MAX_LEN = 256;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final WordPieceTokenizer tokenizer;
    private final boolean hasTokenTypes;

    public Embedder(File onnxFile, File vocabFile) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2);
        session = env.createSession(onnxFile.getAbsolutePath(), opts);
        tokenizer = new WordPieceTokenizer(vocabFile);
        hasTokenTypes = session.getInputNames().contains("token_type_ids");
    }

    /** Returns a unit-length embedding (384 numbers for MiniLM-L6). */
    public synchronized float[] embed(String text) throws OrtException {
        long[] ids = tokenizer.encode(text, MAX_LEN);
        int n = ids.length;
        long[] mask = new long[n];
        java.util.Arrays.fill(mask, 1L);

        try (OnnxTensor tIds = OnnxTensor.createTensor(env, new long[][]{ids});
             OnnxTensor tMask = OnnxTensor.createTensor(env, new long[][]{mask});
             OnnxTensor tType = OnnxTensor.createTensor(env, new long[][]{new long[n]})) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", tIds);
            inputs.put("attention_mask", tMask);
            if (hasTokenTypes) inputs.put("token_type_ids", tType);

            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hidden = (float[][][]) result.get(0).getValue();   // [1][n][dim]
                float[][] tokens = hidden[0];
                int dim = tokens[0].length;
                float[] out = new float[dim];
                for (float[] t : tokens) {
                    for (int j = 0; j < dim; j++) out[j] += t[j];
                }
                float norm = 0f;
                for (int j = 0; j < dim; j++) {
                    out[j] /= tokens.length;
                    norm += out[j] * out[j];
                }
                norm = (float) Math.sqrt(norm);
                if (norm > 0f) for (int j = 0; j < dim; j++) out[j] /= norm;
                return out;
            }
        }
    }

    public void close() {
        try { session.close(); } catch (Exception ignored) { }
    }
}