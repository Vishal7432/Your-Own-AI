package service;

import java.util.*;
import model.VectorItem;
import stores.VectorDatabase;

public class SearchService {

    //  Main method — algo choose karta hai
    public static List<VectorItem> topK(VectorDatabase db, float[] query, int k, String algo) {
        switch (algo) {
            case "kdtree":
                return kdTreeSearch(db, query, k);
            case "hnsw":
                return hnswSearch(db, query, k);
            default:
                return bruteForce(db, query, k);
        }
    }

    // Backward compatible (purana code kaam karta rahe)
    public static List<VectorItem> topK(VectorDatabase db, float[] query, int k) {
        return bruteForce(db, query, k);
    }

    // 1. Brute Force
    private static List<VectorItem> bruteForce(VectorDatabase db, float[] query, int k) {
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

    // 2. KD-Tree Search
    private static List<VectorItem> kdTreeSearch(VectorDatabase db, float[] query, int k) {
        List<VectorItem> all = db.getAll();
        if (all.isEmpty())
            return new ArrayList<>();

        KDNode root = buildKDTree(all, 0);
        PriorityQueue<VectorItem> pq = new PriorityQueue<>((a, b) -> Float.compare(
                SimilarityService.cosine(a.embedding, query),
                SimilarityService.cosine(b.embedding, query)));

        searchKDTree(root, query, k, pq, 0);

        List<VectorItem> result = new ArrayList<>(pq);
        result.sort((a, b) -> Float.compare(
                SimilarityService.cosine(b.embedding, query),
                SimilarityService.cosine(a.embedding, query)));
        return result.subList(0, Math.min(k, result.size()));
    }

    // KD-Tree Node
    static class KDNode {
        VectorItem item;
        KDNode left, right;

        KDNode(VectorItem item) {
            this.item = item;
        }
    }

    // KD-Tree build
    private static KDNode buildKDTree(List<VectorItem> items, int depth) {
        if (items.isEmpty())
            return null;
        int dim = depth % items.get(0).embedding.length;

        items.sort((a, b) -> Float.compare(a.embedding[dim], b.embedding[dim]));
        int mid = items.size() / 2;

        KDNode node = new KDNode(items.get(mid));
        node.left = buildKDTree(new ArrayList<>(items.subList(0, mid)), depth + 1);
        node.right = buildKDTree(new ArrayList<>(items.subList(mid + 1, items.size())), depth + 1);
        return node;
    }

    // KD-Tree search
    private static void searchKDTree(KDNode node, float[] query, int k,
            PriorityQueue<VectorItem> pq, int depth) {
        if (node == null)
            return;

        pq.offer(node.item);
        if (pq.size() > k)
            pq.poll();

        int dim = depth % query.length;
        KDNode first = query[dim] < node.item.embedding[dim] ? node.left : node.right;
        KDNode second = query[dim] < node.item.embedding[dim] ? node.right : node.left;

        searchKDTree(first, query, k, pq, depth + 1);

        float diff = query[dim] - node.item.embedding[dim];
        if (pq.size() < k || diff * diff < 0.1f) {
            searchKDTree(second, query, k, pq, depth + 1);
        }
    }

    // 3. HNSW (Simplified)
    private static List<VectorItem> hnswSearch(VectorDatabase db, float[] query, int k) {
        List<VectorItem> all = db.getAll();
        if (all.isEmpty())
            return new ArrayList<>();

        // Entry point — random node
        int efSearch = Math.min(20, all.size());
        Set<Integer> visited = new HashSet<>();
        PriorityQueue<float[]> candidates = new PriorityQueue<>(
                (a, b) -> Float.compare(b[1], a[1]));
        PriorityQueue<float[]> result = new PriorityQueue<>(
                (a, b) -> Float.compare(a[1], b[1]));

        int entryIdx = 0;
        float entrySim = SimilarityService.cosine(all.get(entryIdx).embedding, query);
        candidates.offer(new float[] { entryIdx, entrySim });
        result.offer(new float[] { entryIdx, entrySim });
        visited.add(entryIdx);

        while (!candidates.isEmpty()) {
            float[] current = candidates.poll();
            int idx = (int) current[0];

            // Neighbors check (simplified — nearby indices)
            int start = Math.max(0, idx - 5);
            int end = Math.min(all.size() - 1, idx + 5);

            for (int i = start; i <= end; i++) {
                if (visited.contains(i))
                    continue;
                visited.add(i);

                float sim = SimilarityService.cosine(all.get(i).embedding, query);
                candidates.offer(new float[] { i, sim });
                result.offer(new float[] { i, sim });

                if (result.size() > efSearch)
                    result.poll();
            }
        }

        List<VectorItem> finalResult = new ArrayList<>();
        List<float[]> sorted = new ArrayList<>(result);
        sorted.sort((a, b) -> Float.compare(b[1], a[1]));

        for (int i = 0; i < Math.min(k, sorted.size()); i++) {
            finalResult.add(all.get((int) sorted.get(i)[0]));
        }
        return finalResult;
    }
}