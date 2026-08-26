package com.github.haghard.vsearch;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.*;

public class Embedder implements AutoCloseable {
    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;

    public Embedder(Path modelDir) throws Exception {
        this.env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            opts.setIntraOpNumThreads(2);
            this.session = env.createSession(modelDir.resolve("model.onnx").toString(), opts);
            System.out.println("Expected Input Names: " + session.getInputNames());
            this.tokenizer = HuggingFaceTokenizer.newInstance(modelDir.resolve("tokenizer.json"));
            System.out.println(this.session.getMetadata().toString());
        }
    }

    /**
     * Embeds a single text and returns an L2-normalized vector.
     */
    public float[] embed(String text) throws Exception {
        var enc = tokenizer.encode(text);

        long[] inputIds = enc.getIds();
        long[] attentionMask = enc.getAttentionMask();
        long[] tokenTypeIds = enc.getTypeIds();
        long[] shape = {1L, enc.getIds().length};

        try (
                OnnxTensor inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), shape);
                OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape);
                OnnxTensor tokenTypeIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenTypeIds), shape)
        ) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputIdsTensor);
            inputs.put("attention_mask", attentionMaskTensor);
            inputs.put("token_type_ids", tokenTypeIdsTensor);

            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] hiddenState = (float[][][]) result.get(0).getValue();
                float[] pooled = meanPool(hiddenState[0], enc.getAttentionMask());
                l2Normalize(pooled);
                return pooled;
            }
        }
    }

    public static float[] meanPool(float[][] tokenEmbeddings, long[] attentionMask) {
        int dim = tokenEmbeddings[0].length;
        float[] result = new float[dim];
        long tokenCount = 0;
        int seqLen = Math.min(attentionMask.length, tokenEmbeddings.length);
        for (int t = 0; t < seqLen; t++) {
            if (attentionMask[t] == 1) {
                for (int d = 0; d < dim; d++) {
                    result[d] += tokenEmbeddings[t][d];
                }
                tokenCount++;
            }
        }
        if (tokenCount > 0) {
            for (int d = 0; d < dim; d++) {
                result[d] /= tokenCount;
            }
        }
        return result;
    }

    public static void l2Normalize(float[] v) {
        double norm = 0.0;
        for (float x : v) {
            norm += (double) x * x;
        }
        norm = Math.sqrt(norm);
        if (norm > 1e-12) {
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / norm);
            }
        }
    }

    @Override
    public void close() throws Exception {
        session.close();
        env.close();
    }
}
