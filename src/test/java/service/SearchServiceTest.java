package service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import model.VectorItem;
import stores.VectorDatabase;

class SearchServiceTest {

    @Test
    void exactKdTreeMatchesBruteForceAcrossKValuesAndDimensions() {
        Random random = new Random(7432);
        VectorDatabase database = new VectorDatabase();
        int dimensions = 96;

        for (int id = 0; id < 180; id++) {
            float[] embedding = new float[dimensions];
            for (int dimension = 0; dimension < dimensions; dimension++) {
                embedding[dimension] = random.nextFloat() * 2 - 1;
            }
            database.add(new VectorItem(id, "document-" + id, "test", embedding));
        }

        for (int trial = 0; trial < 12; trial++) {
            float[] query = new float[dimensions];
            for (int dimension = 0; dimension < dimensions; dimension++) {
                query[dimension] = random.nextFloat() * 2 - 1;
            }

            for (int k : List.of(1, 5, 25, 180, 200)) {
                List<Integer> expected = ids(SearchService.topK(database, query, k, "bruteforce"));
                List<Integer> actual = ids(SearchService.topK(database, query, k, "kdtree-exact"));
                assertEquals(expected, actual, "Top-k results differ for trial=" + trial + ", k=" + k);
            }
        }
    }

    @Test
    void exactSearchBreaksSimilarityTiesByAscendingId() {
        VectorDatabase database = new VectorDatabase();
        database.add(new VectorItem(9, "first", "test", new float[] { 1, 0 }));
        database.add(new VectorItem(2, "second", "test", new float[] { 2, 0 }));
        database.add(new VectorItem(5, "third", "test", new float[] { 0, 1 }));

        assertEquals(List.of(2, 9), ids(SearchService.topK(database, new float[] { 1, 0 }, 2, "bruteforce")));
        assertEquals(List.of(2, 9), ids(SearchService.topK(database, new float[] { 1, 0 }, 2, "kdtree-exact")));
    }

    @Test
    void exactSearchRejectsInvalidEmbeddingsAndUnknownAlgorithms() {
        VectorDatabase database = new VectorDatabase();
        database.add(new VectorItem(1, "one", "test", new float[] { 1, 0 }));

        assertThrows(IllegalArgumentException.class,
                () -> SearchService.topK(database, new float[] { 0, 0 }, 1, "bruteforce"));
        assertThrows(IllegalArgumentException.class,
                () -> SearchService.topK(database, new float[] { 1 }, 1, "kdtree-exact"));
        assertThrows(IllegalArgumentException.class,
                () -> SearchService.topK(database, new float[] { 1, 0 }, 1, "hnsw"));
    }

    @Test
    void hnswReturnsRepeatableCandidatesOnDeterministicGraph() {
        Random random = new Random(17);
        VectorDatabase database = new VectorDatabase();
        int dimensions = 48;
        for (int id = 0; id < 120; id++) {
            float[] embedding = new float[dimensions];
            for (int dimension = 0; dimension < dimensions; dimension++) {
                embedding[dimension] = random.nextFloat() * 2 - 1;
            }
            database.add(new VectorItem(id, "document-" + id, "test", embedding));
        }
        float[] query = new float[dimensions];
        for (int dimension = 0; dimension < dimensions; dimension++) {
            query[dimension] = random.nextFloat() * 2 - 1;
        }

        List<VectorItem> first = SearchService.topK(database, query, 10, "hnsw-experimental");
        List<VectorItem> second = SearchService.topK(database, query, 10, "hnsw-experimental");
        List<Integer> allIds = new ArrayList<>();
        for (int id = 0; id < database.size(); id++) {
            allIds.add(id);
        }

        assertEquals(10, first.size());
        assertEquals(ids(first), ids(second));
        assertTrue(ids(first).stream().allMatch(allIds::contains));
    }

    @Test
    void hnswCanVisitTheWholeGraphWhenSearchBreadthCoversDataset() {
        Random random = new Random(91);
        VectorDatabase database = new VectorDatabase();
        int dimensions = 24;
        int count = 64;
        for (int id = 0; id < count; id++) {
            float[] embedding = new float[dimensions];
            for (int dimension = 0; dimension < dimensions; dimension++) {
                embedding[dimension] = random.nextFloat() * 2 - 1;
            }
            database.add(new VectorItem(id, "document-" + id, "test", embedding));
        }
        float[] query = new float[dimensions];
        for (int dimension = 0; dimension < dimensions; dimension++) {
            query[dimension] = random.nextFloat() * 2 - 1;
        }

        assertEquals(
                ids(SearchService.topK(database, query, count, "bruteforce")),
                ids(SearchService.topK(database, query, count, "hnsw-experimental")));
    }

    @Test
    void benchmarkReportsRecallForExactAndApproximateAlgorithms() {
        VectorDatabase database = new VectorDatabase();
        database.add(new VectorItem(1, "first", "test", new float[] { 1, 0, 0 }));
        database.add(new VectorItem(2, "second", "test", new float[] { 0, 1, 0 }));
        database.add(new VectorItem(3, "third", "test", new float[] { 0, 0, 1 }));

        List<SearchService.BenchmarkResult> results =
                SearchService.benchmark(database, new float[] { 1, 1, 0 }, 2, 2);

        assertEquals(List.of("bruteforce", "kdtree-exact", "hnsw-experimental"),
                results.stream().map(SearchService.BenchmarkResult::algorithm).toList());
        assertTrue(results.stream().allMatch(result -> result.recallAtK() == 1.0));
        assertTrue(results.stream().allMatch(result -> result.averageMillis() >= 0));
    }

    private static List<Integer> ids(List<VectorItem> items) {
        List<Integer> ids = new ArrayList<>();
        for (VectorItem item : items) {
            ids.add(item.id);
        }
        return ids;
    }
}
