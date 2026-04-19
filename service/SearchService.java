package service;

import java.util.*;
import model.VectorItem;
import stores.VectorDatabase;

// searching using Brute Force.
class SearchService {

    public static List<VectorItem> topK(VectorDatabase db, float[] query, int k) {

        PriorityQueue<VectorItem> pq = new PriorityQueue<>((a, b) -> Float.compare(
                SimilarityService.cosine(b.embedding, query),
                SimilarityService.cosine(a.embedding, query)));

        pq.addAll(db.getAll());

        List<VectorItem> result = new ArrayList<>();

        for (int i = 0; i < k && !pq.isEmpty(); i++) {
            result.add(pq.poll());
        }

        return result;
    }
}