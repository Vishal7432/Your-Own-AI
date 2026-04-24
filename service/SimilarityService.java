package service;

public class SimilarityService {

    public static float cosine(float[] a, float[] b) {
        float dot = 0, normA = 0, normB = 0;

        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }
}