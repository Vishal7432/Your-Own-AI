package service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import model.VectorItem;
import stores.VectorDatabase;

public class SearchService {

    private static final double BOUNDARY_EPSILON = 1e-12;

    public static List<VectorItem> topK(VectorDatabase db, float[] query, int k, String algo) {
        if (k <= 0) {
            return new ArrayList<>();
        }
        double[] normalizedQuery = validateAndCopy(db, query);
        switch (algo) {
            case "bruteforce":
                return bruteForce(db.getAll(), query, k);
            case "kdtree-exact":
                return kdTreeSearch(db.getAll(), normalizedQuery, query, k);
            case "hnsw-experimental":
                if (db.size() == 0) {
                    return new ArrayList<>();
                }
                return new HnswIndex(db.getAll()).search(
                        query,
                        k,
                        hnswParameters(k, db.size()).get("efSearch"));
            default:
                throw new IllegalArgumentException("Unsupported search algorithm: " + algo);
        }
    }

    public static List<VectorItem> topK(VectorDatabase db, float[] query, int k) {
        return topK(db, query, k, "bruteforce");
    }

    public static List<VectorItem> bruteForce(VectorDatabase db, float[] query, int k) {
        if (k <= 0) {
            return new ArrayList<>();
        }
        validateAndCopy(db, query);
        return bruteForce(db.getAll(), query, k);
    }

    public static List<BenchmarkResult> benchmark(VectorDatabase db, float[] query, int k, int repetitions) {
        if (k <= 0 || repetitions <= 0) {
            throw new IllegalArgumentException("k and repetitions must be positive.");
        }
        validateAndCopy(db, query);

        List<VectorItem> exactResults = bruteForce(db.getAll(), query, k);
        List<BenchmarkResult> results = new ArrayList<>();
        for (String algorithm : List.of("bruteforce", "kdtree-exact", "hnsw-experimental")) {
            topK(db, query, k, algorithm);

            long totalNanos = 0;
            long minNanos = Long.MAX_VALUE;
            List<VectorItem> lastResults = exactResults;
            for (int i = 0; i < repetitions; i++) {
                long start = System.nanoTime();
                lastResults = topK(db, query, k, algorithm);
                long elapsed = System.nanoTime() - start;
                totalNanos += elapsed;
                minNanos = Math.min(minNanos, elapsed);
            }

            long matchingIds = lastResults.stream()
                    .filter(item -> exactResults.stream().anyMatch(expected -> expected.id == item.id))
                    .count();
            double recall = exactResults.isEmpty() ? 1.0 : (double) matchingIds / exactResults.size();
            results.add(new BenchmarkResult(
                    algorithm,
                    recall,
                    totalNanos / (double) repetitions / 1_000_000.0,
                    minNanos / 1_000_000.0,
                    lastResults));
        }
        return results;
    }

    public static Map<String, Integer> hnswParameters(int k, int datasetSize) {
        return Map.of(
                "M", HnswIndex.M,
                "efConstruction", HnswIndex.EF_CONSTRUCTION,
                "efSearch", Math.min(datasetSize, Math.max(32, k * 4)));
    }

    private static List<VectorItem> bruteForce(List<VectorItem> items, float[] query, int k) {
        PriorityQueue<VectorItem> best = worstFirstQueue(query);
        for (VectorItem item : items) {
            offer(item, query, k, best);
        }
        return orderedResults(best, query);
    }

    private static List<VectorItem> kdTreeSearch(List<VectorItem> items, double[] normalizedQuery,
            float[] query, int k) {
        if (items.isEmpty()) {
            return new ArrayList<>();
        }

        List<KDPoint> points = new ArrayList<>(items.size());
        for (VectorItem item : items) {
            points.add(new KDPoint(item, normalize(item.embedding)));
        }

        KDNode root = buildKDTree(points, 0, normalizedQuery.length);
        PriorityQueue<VectorItem> best = worstFirstQueue(query);
        searchKDTree(root, normalizedQuery, query, k, best);
        return orderedResults(best, query);
    }

    private static PriorityQueue<VectorItem> worstFirstQueue(float[] query) {
        return new PriorityQueue<>((left, right) -> {
            int scoreOrder = Double.compare(score(left, query), score(right, query));
            return scoreOrder != 0 ? scoreOrder : Integer.compare(right.id, left.id);
        });
    }

    private static void offer(VectorItem item, float[] query, int k, PriorityQueue<VectorItem> best) {
        if (best.size() < k) {
            best.offer(item);
            return;
        }
        VectorItem worst = best.peek();
        int scoreOrder = Double.compare(score(item, query), score(worst, query));
        if (scoreOrder > 0 || (scoreOrder == 0 && item.id < worst.id)) {
            best.poll();
            best.offer(item);
        }
    }

    private static List<VectorItem> orderedResults(PriorityQueue<VectorItem> best, float[] query) {
        List<VectorItem> results = new ArrayList<>(best);
        results.sort((left, right) -> {
            int scoreOrder = Double.compare(score(right, query), score(left, query));
            return scoreOrder != 0 ? scoreOrder : Integer.compare(left.id, right.id);
        });
        return results;
    }

    private static double score(VectorItem item, float[] query) {
        double dot = 0;
        double itemNorm = 0;
        double queryNorm = 0;
        for (int i = 0; i < query.length; i++) {
            dot += (double) item.embedding[i] * query[i];
            itemNorm += (double) item.embedding[i] * item.embedding[i];
            queryNorm += (double) query[i] * query[i];
        }
        return dot / (Math.sqrt(itemNorm) * Math.sqrt(queryNorm));
    }

    private static double[] validateAndCopy(VectorDatabase db, float[] query) {
        if (query == null || query.length == 0) {
            throw new IllegalArgumentException("Query embedding must not be empty.");
        }
        double queryNorm = squaredNorm(query);
        if (!Double.isFinite(queryNorm) || queryNorm == 0) {
            throw new IllegalArgumentException("Query embedding must contain finite, non-zero values.");
        }

        List<VectorItem> items = db.getAll();
        for (VectorItem item : items) {
            if (item.embedding == null || item.embedding.length != query.length) {
                throw new IllegalArgumentException("All stored embeddings must match the query dimensions.");
            }
            double norm = squaredNorm(item.embedding);
            if (!Double.isFinite(norm) || norm == 0) {
                throw new IllegalArgumentException("Stored embeddings must contain finite, non-zero values.");
            }
        }
        return normalize(query);
    }

    private static double squaredNorm(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                return Double.NaN;
            }
            norm += (double) value * value;
        }
        return norm;
    }

    private static double[] normalize(float[] vector) {
        double norm = Math.sqrt(squaredNorm(vector));
        double[] normalized = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / norm;
        }
        return normalized;
    }

    private static KDNode buildKDTree(List<KDPoint> points, int depth, int dimensions) {
        if (points.isEmpty()) {
            return null;
        }
        int axis = depth % dimensions;
        points.sort(Comparator.comparingDouble((KDPoint point) -> point.coordinates[axis])
                .thenComparingInt(point -> point.item.id));
        int middle = points.size() / 2;
        KDNode node = new KDNode(points.get(middle), axis);
        node.left = buildKDTree(new ArrayList<>(points.subList(0, middle)), depth + 1, dimensions);
        node.right = buildKDTree(new ArrayList<>(points.subList(middle + 1, points.size())), depth + 1, dimensions);
        return node;
    }

    private static void searchKDTree(KDNode node, double[] query, float[] originalQuery, int k,
            PriorityQueue<VectorItem> best) {
        if (node == null) {
            return;
        }

        offer(node.point.item, originalQuery, k, best);
        double difference = query[node.axis] - node.point.coordinates[node.axis];
        KDNode near = difference <= 0 ? node.left : node.right;
        KDNode far = difference <= 0 ? node.right : node.left;
        searchKDTree(near, query, originalQuery, k, best);

        double worstDistance = best.size() < k
                ? Double.POSITIVE_INFINITY
                : 2.0 - 2.0 * score(best.peek(), originalQuery);
        if (difference * difference <= worstDistance + BOUNDARY_EPSILON) {
            searchKDTree(far, query, originalQuery, k, best);
        }
    }

    public record BenchmarkResult(
            String algorithm,
            double recallAtK,
            double averageMillis,
            double minMillis,
            List<VectorItem> results) {
    }

    private static final class KDPoint {
        private final VectorItem item;
        private final double[] coordinates;

        private KDPoint(VectorItem item, double[] coordinates) {
            this.item = item;
            this.coordinates = coordinates;
        }
    }

    private static final class KDNode {
        private final KDPoint point;
        private final int axis;
        private KDNode left;
        private KDNode right;

        private KDNode(KDPoint point, int axis) {
            this.point = point;
            this.axis = axis;
        }
    }
}
