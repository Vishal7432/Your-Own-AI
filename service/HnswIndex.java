package service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import model.VectorItem;

final class HnswIndex {

    static final int M = 8;
    static final int EF_CONSTRUCTION = 64;

    private static final long LEVEL_RANDOM_SEED = 7432L;
    private static final double LEVEL_MULTIPLIER = 1.0 / Math.log(M);

    private final List<Node> nodes = new ArrayList<>();
    private final Random levelRandom = new Random(LEVEL_RANDOM_SEED);
    private int entryPoint = -1;
    private int maxLevel = -1;

    HnswIndex(List<VectorItem> items) {
        for (VectorItem item : items) {
            insert(item);
        }
    }

    List<VectorItem> search(float[] query, int k, int efSearch) {
        if (entryPoint < 0 || k <= 0) {
            return new ArrayList<>();
        }

        double[] normalizedQuery = normalize(query);
        int current = entryPoint;
        for (int layer = maxLevel; layer > 0; layer--) {
            current = greedySearch(normalizedQuery, current, layer);
        }

        List<Integer> candidates = searchLayer(
                normalizedQuery,
                List.of(current),
                Math.max(k, efSearch),
                0);
        List<VectorItem> results = new ArrayList<>(Math.min(k, candidates.size()));
        for (int i = 0; i < Math.min(k, candidates.size()); i++) {
            results.add(nodes.get(candidates.get(i)).item);
        }
        return results;
    }

    private void insert(VectorItem item) {
        Node node = new Node(item, normalize(item.embedding), randomLevel());
        int newIndex = nodes.size();
        nodes.add(node);

        if (entryPoint < 0) {
            entryPoint = newIndex;
            maxLevel = node.level;
            return;
        }

        int currentEntry = entryPoint;
        for (int layer = maxLevel; layer > node.level; layer--) {
            currentEntry = greedySearch(node.vector, currentEntry, layer);
        }

        for (int layer = Math.min(node.level, maxLevel); layer >= 0; layer--) {
            List<Integer> candidates = searchLayer(
                    node.vector,
                    List.of(currentEntry),
                    EF_CONSTRUCTION,
                    layer);
            List<Integer> selected = selectNeighbors(
                    node.vector,
                    candidates,
                    maxConnections(layer));

            for (int neighborIndex : selected) {
                node.neighbors.get(layer).add(neighborIndex);
                Node neighbor = nodes.get(neighborIndex);
                neighbor.neighbors.get(layer).add(newIndex);
                pruneConnections(neighborIndex, layer);
            }

            if (!candidates.isEmpty()) {
                currentEntry = candidates.get(0);
            }
        }

        if (node.level > maxLevel) {
            entryPoint = newIndex;
            maxLevel = node.level;
        }
    }

    private int greedySearch(double[] query, int startIndex, int layer) {
        int current = startIndex;
        boolean improved;
        do {
            improved = false;
            double currentScore = similarity(query, nodes.get(current).vector);
            for (int neighbor : nodes.get(current).neighbors.get(layer)) {
                double candidateScore = similarity(query, nodes.get(neighbor).vector);
                if (isBetter(neighbor, candidateScore, current, currentScore)) {
                    current = neighbor;
                    currentScore = candidateScore;
                    improved = true;
                }
            }
        } while (improved);
        return current;
    }

    private List<Integer> searchLayer(double[] query, List<Integer> entries, int ef, int layer) {
        PriorityQueue<Integer> candidates = new PriorityQueue<>(bestFirst(query));
        PriorityQueue<Integer> results = new PriorityQueue<>(worstFirst(query));
        Set<Integer> visited = new HashSet<>();

        for (int entry : entries) {
            if (visited.add(entry)) {
                candidates.offer(entry);
                results.offer(entry);
            }
        }

        while (!candidates.isEmpty()) {
            int current = candidates.poll();
            int worst = results.peek();
            if (results.size() >= ef && isBetter(worst, current, query)) {
                break;
            }

            for (int neighbor : nodes.get(current).neighbors.get(layer)) {
                if (!visited.add(neighbor)) {
                    continue;
                }

                if (results.size() < ef || isBetter(neighbor, worst, query)) {
                    candidates.offer(neighbor);
                    results.offer(neighbor);
                    if (results.size() > ef) {
                        results.poll();
                    }
                }
            }
        }

        List<Integer> ordered = new ArrayList<>(results);
        ordered.sort(bestFirst(query));
        return ordered;
    }

    private List<Integer> selectNeighbors(double[] query, List<Integer> candidates, int limit) {
        List<Integer> ordered = new ArrayList<>(candidates);
        ordered.sort(bestFirst(query));

        List<Integer> selected = new ArrayList<>(Math.min(limit, ordered.size()));
        List<Integer> deferred = new ArrayList<>();
        for (int candidate : ordered) {
            boolean diverse = true;
            for (int existing : selected) {
                if (similarity(nodes.get(candidate).vector, nodes.get(existing).vector)
                        >= similarity(query, nodes.get(candidate).vector)) {
                    diverse = false;
                    break;
                }
            }
            if (diverse && selected.size() < limit) {
                selected.add(candidate);
            } else {
                deferred.add(candidate);
            }
        }

        for (int candidate : deferred) {
            if (selected.size() == limit) {
                break;
            }
            selected.add(candidate);
        }
        return selected;
    }

    private void pruneConnections(int nodeIndex, int layer) {
        Node node = nodes.get(nodeIndex);
        int maxConnections = maxConnections(layer);
        if (node.neighbors.get(layer).size() <= maxConnections) {
            return;
        }

        List<Integer> selected = selectNeighbors(
                node.vector,
                node.neighbors.get(layer),
                maxConnections);
        node.neighbors.get(layer).clear();
        node.neighbors.get(layer).addAll(selected);
    }

    private Comparator<Integer> bestFirst(double[] query) {
        return (left, right) -> {
            int scoreOrder = Double.compare(
                    similarity(query, nodes.get(right).vector),
                    similarity(query, nodes.get(left).vector));
            return scoreOrder != 0
                    ? scoreOrder
                    : Integer.compare(nodes.get(left).item.id, nodes.get(right).item.id);
        };
    }

    private Comparator<Integer> worstFirst(double[] query) {
        return (left, right) -> {
            int scoreOrder = Double.compare(
                    similarity(query, nodes.get(left).vector),
                    similarity(query, nodes.get(right).vector));
            return scoreOrder != 0
                    ? scoreOrder
                    : Integer.compare(nodes.get(right).item.id, nodes.get(left).item.id);
        };
    }

    private boolean isBetter(int left, double leftScore, int right, double rightScore) {
        return leftScore > rightScore
                || (leftScore == rightScore && nodes.get(left).item.id < nodes.get(right).item.id);
    }

    private boolean isBetter(int left, int right, double[] query) {
        return isBetter(
                left,
                similarity(query, nodes.get(left).vector),
                right,
                similarity(query, nodes.get(right).vector));
    }

    private int maxConnections(int layer) {
        return layer == 0 ? M * 2 : M;
    }

    private int randomLevel() {
        double sample = Math.max(levelRandom.nextDouble(), Double.MIN_VALUE);
        return (int) (-Math.log(sample) * LEVEL_MULTIPLIER);
    }

    private static double[] normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        norm = Math.sqrt(norm);
        double[] normalized = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / norm;
        }
        return normalized;
    }

    private static double similarity(double[] left, double[] right) {
        double dot = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
        }
        return dot;
    }

    private static final class Node {
        private final VectorItem item;
        private final double[] vector;
        private final int level;
        private final List<List<Integer>> neighbors;

        private Node(VectorItem item, double[] vector, int level) {
            this.item = item;
            this.vector = vector;
            this.level = level;
            this.neighbors = new ArrayList<>(level + 1);
            for (int layer = 0; layer <= level; layer++) {
                neighbors.add(new ArrayList<>());
            }
        }
    }
}
