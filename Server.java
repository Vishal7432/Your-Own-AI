import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import model.VectorItem;
import service.EmdService;
import service.JsonService;
import service.PDFService;
import stores.VectorDatabase;
import stores.VectorStorage;

public class Server {

    public static void main(String[] args) throws Exception {

        // 1. Server create
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        // 2. Database create (ONLY ONCE)
        VectorDatabase db = new VectorDatabase();
        VectorStorage.load(db);

        // db.add(new VectorItem(1, "Java is a programming language", "tech", new
        // float[] { 0.2f, 0.3f }));
        // db.add(new VectorItem(2, "AI is future", "tech", new float[] { 0.5f, 0.6f
        // }));
        // ;

        // 3. Pass DB to handler
        server.createContext("/ask", new AskHandler(db));
        server.createContext("/insertDoc", new InsertHandler(db));
        server.createContext("/insertPDF", new PDFHandler(db));
        server.createContext("/search", new SearchHandler(db));
        server.createContext("/benchmark", new BenchmarkHandler(db));
        server.createContext("/items", new ItemsHandler(db));
        server.createContext("/insert", new InsertVectorHandler(db));
        server.createContext("/doc/search", new DocSearchHandler(db));
        server.createContext("/doc/ask", new DocAskHandler(db));

        server.setExecutor(null);
        server.start();

        System.out.println("Server started at http://localhost:8080");
    }

    // Handler class
    static class AskHandler implements HttpHandler {

        private VectorDatabase db;

        // constructor
        public AskHandler(VectorDatabase db) {
            this.db = db;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST, OPTIONS");
                JsonService.send(exchange, 405, JsonService.error("Method not allowed."));
                return;
            }

            String question;
            String algo;
            try {
                JsonObject request = JsonService.parseObject(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                question = JsonService.requiredString(request, "question");
                algo = JsonService.optionalString(request, "algo", "bruteforce");
                if (!isSupportedAlgorithm(algo)) {
                    JsonService.send(exchange, 400, JsonService.error("Unsupported search algorithm."));
                    return;
                }
            } catch (JsonParseException | IllegalArgumentException e) {
                JsonService.send(exchange, 400, JsonService.error(e.getMessage()));
                return;
            }

            System.out.println("Request received");
            System.out.println("Question = " + question + " | Algo = " + algo);

            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody()) {
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, "UTF-8"), true);
                HttpURLConnection conn = null;

                try {
                    // Embedding + Search
                    float[] queryVec = service.EmdService.getEmbedding(question);
                    // Ab — default brute force
                    java.util.List<model.VectorItem> results = service.SearchService.topK(db, queryVec, 1, algo);

                    StringBuilder combined = new StringBuilder();
                    for (model.VectorItem v : results) {
                        combined.append(v.getText()).append("\n");
                    }

                    String context = combined.toString();
                    if (context.length() > 300) {
                        context = context.substring(0, 300);
                    }

                    // // Context word by word stream karo
                    // String[] words = context.split(" ");

                    // for (String word : words) {
                    // if (word.isEmpty())
                    // continue;

                    // writer.write("data: " + word + " \n\n");
                    // writer.flush();

                    // Thread.sleep(80);
                    // }

                    // Ollama streaming call
                    URI uri = URI.create("http://localhost:11434/api/generate");
                    URL url = uri.toURL();
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(180000);

                    String prompt = context + " Q: " + question + " A:";
                    String jsonInput = JsonService.stringify(Map.of(
                            "model", "tinyllama",
                            "prompt", prompt,
                            "stream", true,
                            "temperature", 0,
                            "num_predict", 80));

                    try (OutputStream ollamaOs = conn.getOutputStream()) {
                        ollamaOs.write(jsonInput.getBytes(StandardCharsets.UTF_8));
                    }

                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.isEmpty())
                                continue;

                            try {
                                JsonObject chunk = JsonParser.parseString(line).getAsJsonObject();
                                if (chunk.has("response")) {
                                    String token = chunk.get("response").getAsString()
                                            .replace("\r", " ").replace("\n", " ");
                                    writer.write("data: " + token + "\n\n");
                                }
                                writer.flush();
                                if (chunk.has("done") && chunk.get("done").getAsBoolean()) {
                                    break;
                                }
                            } catch (JsonParseException e) {
                                throw new IOException("Ollama returned invalid JSON.", e);
                            }
                        }
                    }

                } catch (Exception e) {
                    writer.write("data: Error: " + e.getMessage().replace("\r", " ").replace("\n", " ") + "\n\n");
                    writer.flush();
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }

                // Stream end signal
                writer.write("data: [DONE]\n\n");
                writer.flush();
            }
        }
    }

    // for endpoint Insert RAG handler
    static class InsertHandler implements HttpHandler {

        private VectorDatabase db;

        public InsertHandler(VectorDatabase db) {
            this.db = db;
        }

        public void handle(HttpExchange exchange) throws IOException {

            if ("OPTIONS".equals(exchange.getRequestMethod())) {

                exchange.getResponseHeaders().add(
                        "Access-Control-Allow-Origin", "*");

                exchange.getResponseHeaders().add(
                        "Access-Control-Allow-Methods",
                        "POST, OPTIONS");

                exchange.getResponseHeaders().add(
                        "Access-Control-Allow-Headers",
                        "Content-Type");

                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST, OPTIONS");
                JsonService.send(exchange, 405, JsonService.error("Method not allowed."));
                return;
            }

            String text;
            try {
                JsonObject request = JsonService.parseObject(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                text = JsonService.requiredString(request, "doc");
            } catch (JsonParseException | IllegalArgumentException e) {
                JsonService.send(exchange, 400, JsonService.error(e.getMessage()));
                return;
            }

            try {
                float[] embedding = EmdService.getEmbedding(text);
                db.add(new VectorItem(db.size() + 1, text, "doc", embedding));
                VectorStorage.save(db);
                JsonService.send(exchange, 200, Map.of("status", "inserted"));
            } catch (Exception e) {
                e.printStackTrace();
                JsonService.send(exchange, 500, JsonService.error("Document insertion failed."));
            }
        }
    }

    // PDF Handler
    static class PDFHandler implements HttpHandler {

        private VectorDatabase db;

        public PDFHandler(VectorDatabase db) {
            this.db = db;
        }

        public void handle(HttpExchange exchange) throws IOException {

            // CORS preflight
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if ("POST".equals(exchange.getRequestMethod())) {

                // System.out.println("PDF upload received");

                try {
                    // PDF bytes read karo
                    byte[] pdfBytes = exchange.getRequestBody().readAllBytes();

                    // Text extract karo
                    String fullText = PDFService.extractText(pdfBytes);
                    System.out.println("Extracted text length: " + fullText.length());

                    // Chunks banao (300 words, 50 overlap)
                    List<String> chunks = PDFService.chunkText(fullText, 300, 50);
                    // System.out.println("Total chunks: " + chunks.size());

                    // Har chunk embed karo aur store karo
                    int inserted = 0;
                    for (String chunk : chunks) {
                        if (chunk.trim().isEmpty())
                            continue;
                        float[] emb = EmdService.getEmbedding(chunk);
                        db.add(new VectorItem(db.size() + 1, chunk, "pdf", emb));
                        inserted++;
                        // System.out.println("Chunk " + inserted + " inserted");
                    }

                    // Save to disk
                    VectorStorage.save(db);

                    JsonService.send(exchange, 200, Map.of("status", "ok", "chunks", inserted));

                } catch (Exception e) {
                    e.printStackTrace();
                    JsonService.send(exchange, 500, Map.of(
                            "status", "error",
                            "message", "PDF processing failed."));
                }
            }
        }
    }

    static class SearchHandler implements HttpHandler {

        private VectorDatabase db;

        public SearchHandler(VectorDatabase db) {
            this.db = db;
        }

        public void handle(HttpExchange exchange) throws IOException {

            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST, OPTIONS");
                JsonService.send(exchange, 405, JsonService.error("Method not allowed."));
                return;
            }

            String query;
            String algo;
            try {
                JsonObject request = JsonService.parseObject(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                query = JsonService.requiredString(request, "query");
                algo = JsonService.optionalString(request, "algo", "bruteforce");
                if (!isSupportedAlgorithm(algo)) {
                    JsonService.send(exchange, 400, JsonService.error("Unsupported search algorithm."));
                    return;
                }
            } catch (JsonParseException | IllegalArgumentException e) {
                JsonService.send(exchange, 400, JsonService.error(e.getMessage()));
                return;
            }

            System.out.println("Search: " + query + " | Algo: " + algo);

            try {
                float[] queryVec = service.EmdService.getEmbedding(query);

                long startTime = System.currentTimeMillis();
                java.util.List<model.VectorItem> results = service.SearchService.topK(db, queryVec, 5, algo);
                java.util.List<model.VectorItem> all = db.getAll();
                long latency = System.currentTimeMillis() - startTime;

                java.util.List<Map<String, Object>> resultItems = new ArrayList<>();
                java.util.List<Integer> resultIndices = new ArrayList<>();
                for (int i = 0; i < results.size(); i++) {
                    model.VectorItem item = results.get(i);
                    String text = item.getText();
                    if (text.length() > 100) {
                        text = text.substring(0, 100);
                    }
                    resultItems.add(Map.of(
                            "rank", i + 1,
                            "text", text,
                            "category", item.category));
                    resultIndices.add(all.indexOf(item));
                }

                java.util.List<float[]> allVectors = new ArrayList<>();
                for (model.VectorItem item : all) {
                    allVectors.add(item.embedding);
                }

                JsonService.send(exchange, 200, Map.of(
                        "latency", latency,
                        "results", resultItems,
                        "resultIndices", resultIndices,
                        "queryVector", queryVec,
                        "allVectors", allVectors));
            } catch (Exception e) {
                e.printStackTrace();
                JsonService.send(exchange, 500, JsonService.error("Search failed."));
            }

        }

    }

    static class BenchmarkHandler implements HttpHandler {
        private final VectorDatabase db;

        BenchmarkHandler(VectorDatabase db) {
            this.db = db;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST, OPTIONS");
                JsonService.send(exchange, 405, JsonService.error("Method not allowed."));
                return;
            }

            String query;
            int k;
            int repetitions;
            try {
                JsonObject request = JsonService.parseObject(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                query = JsonService.requiredString(request, "query");
                k = JsonService.optionalInt(request, "k", 5, 100);
                repetitions = JsonService.optionalInt(request, "repetitions", 5, 20);
            } catch (JsonParseException | IllegalArgumentException e) {
                JsonService.send(exchange, 400, JsonService.error(e.getMessage()));
                return;
            }

            try {
                long embeddingStart = System.nanoTime();
                float[] queryVector = EmdService.getEmbedding(query);
                double embeddingMillis = (System.nanoTime() - embeddingStart) / 1_000_000.0;
                List<Map<String, Object>> measurements = new ArrayList<>();
                for (service.SearchService.BenchmarkResult result :
                        service.SearchService.benchmark(db, queryVector, k, repetitions)) {
                    measurements.add(Map.of(
                            "algorithm", result.algorithm(),
                            "recallAtK", result.recallAtK(),
                            "averageMillis", result.averageMillis(),
                            "minMillis", result.minMillis(),
                            "results", result.results()));
                }

                int dimensions = queryVector.length;
                JsonService.send(exchange, 200, Map.of(
                        "datasetSize", db.size(),
                        "dimensions", dimensions,
                        "k", k,
                        "repetitions", repetitions,
                        "embeddingMillis", embeddingMillis,
                        "timingIncludesIndexBuild", true,
                        "hnswParameters", service.SearchService.hnswParameters(k, db.size()),
                        "benchmarks", measurements));
            } catch (Exception e) {
                e.printStackTrace();
                JsonService.send(exchange, 500, JsonService.error("Search benchmark failed."));
            }
        }
    }

    static class ItemsHandler implements HttpHandler {
        private VectorDatabase db;

        public ItemsHandler(VectorDatabase db) {
            this.db = db;
        }

        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod()) && !"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET, POST, OPTIONS");
                JsonService.send(exchange, 405, JsonService.error("Method not allowed."));
                return;
            }

            java.util.List<model.VectorItem> all = db.getAll();
            java.util.List<Map<String, Object>> items = new ArrayList<>();
            for (model.VectorItem item : all) {
                String text = item.text;
                if (text.length() > 80) {
                    text = text.substring(0, 80);
                }
                items.add(Map.of(
                        "id", item.id,
                        "metadata", text,
                        "category", item.category,
                        "embedding", item.embedding));
            }
            JsonService.send(exchange, 200, items);
        }
    }

    static class InsertVectorHandler implements HttpHandler {
        private VectorDatabase db;

        public InsertVectorHandler(VectorDatabase db) {
            this.db = db;
        }

        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST, OPTIONS");
                JsonService.send(exchange, 405, JsonService.error("Method not allowed."));
                return;
            }

            try {
                JsonObject request = JsonService.parseObject(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String metadata = JsonService.requiredString(request, "metadata");
                String category = JsonService.optionalString(request, "category", "doc");
                if (category.trim().isEmpty()) {
                    throw new IllegalArgumentException("Field 'category' must not be empty.");
                }

                if (!request.has("embedding") || !request.get("embedding").isJsonArray()) {
                    throw new IllegalArgumentException("Field 'embedding' must be a non-empty array of numbers.");
                }
                com.google.gson.JsonArray values = request.getAsJsonArray("embedding");
                if (values.size() == 0) {
                    throw new IllegalArgumentException("Field 'embedding' must be a non-empty array of numbers.");
                }
                float[] embedding = new float[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    if (!values.get(i).isJsonPrimitive() || !values.get(i).getAsJsonPrimitive().isNumber()) {
                        throw new IllegalArgumentException("Field 'embedding' must contain only numbers.");
                    }
                    embedding[i] = values.get(i).getAsFloat();
                    if (!Float.isFinite(embedding[i])) {
                        throw new IllegalArgumentException("Embedding values must be finite numbers.");
                    }
                }

                db.add(new model.VectorItem(db.size() + 1, metadata, category, embedding));
                stores.VectorStorage.save(db);
                JsonService.send(exchange, 200, Map.of("status", "ok"));
            } catch (JsonParseException | IllegalArgumentException e) {
                JsonService.send(exchange, 400, JsonService.error(e.getMessage()));
            } catch (Exception e) {
                e.printStackTrace();
                JsonService.send(exchange, 500, JsonService.error("Vector insertion failed."));
            }
        }
    }

    static class DocSearchHandler implements HttpHandler {
        private VectorDatabase db;

        public DocSearchHandler(VectorDatabase db) {
            this.db = db;
        }

        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST, OPTIONS");
                JsonService.send(exchange, 405, JsonService.error("Method not allowed."));
                return;
            }

            String question;
            try {
                JsonObject request = JsonService.parseObject(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                question = JsonService.requiredString(request, "question");
            } catch (JsonParseException | IllegalArgumentException e) {
                JsonService.send(exchange, 400, JsonService.error(e.getMessage()));
                return;
            }

            try {
                float[] queryVec = service.EmdService.getEmbedding(question);
                java.util.List<model.VectorItem> results = service.SearchService.topK(db, queryVec, 3,
                        "bruteforce");

                java.util.List<Map<String, Object>> contexts = new ArrayList<>();
                for (model.VectorItem item : results) {
                    String text = item.text;
                    if (text.length() > 200) {
                        text = text.substring(0, 200);
                    }
                    contexts.add(Map.of(
                            "title", item.category,
                            "text", text,
                            "distance", 0.0));
                }
                JsonService.send(exchange, 200, Map.of("contexts", contexts));
            } catch (Exception e) {
                e.printStackTrace();
                JsonService.send(exchange, 500, JsonService.error("Document search failed."));
            }
        }
    }

    static class DocAskHandler implements HttpHandler {
        private VectorDatabase db;

        public DocAskHandler(VectorDatabase db) {
            this.db = db;
        }

        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST, OPTIONS");
                JsonService.send(exchange, 405, JsonService.error("Method not allowed."));
                return;
            }

            String question;
            try {
                JsonObject request = JsonService.parseObject(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                question = JsonService.requiredString(request, "question");
            } catch (JsonParseException | IllegalArgumentException e) {
                JsonService.send(exchange, 400, Map.of("error", e.getMessage(), "contexts", List.of()));
                return;
            }

            try {
                float[] queryVec = service.EmdService.getEmbedding(question);
                java.util.List<model.VectorItem> results = service.SearchService.topK(db, queryVec, 3,
                        "bruteforce");

                StringBuilder context = new StringBuilder();
                for (model.VectorItem item : results) {
                    context.append(item.text).append("\n");
                }

                String answer = service.LLMService.askLLM(context.toString(), question);
                java.util.List<Map<String, Object>> contexts = new ArrayList<>();
                for (int i = 0; i < results.size(); i++) {
                    model.VectorItem item = results.get(i);
                    String text = item.text;
                    if (text.length() > 150) {
                        text = text.substring(0, 150);
                    }
                    contexts.add(Map.of(
                            "title", item.category + " chunk " + (i + 1),
                            "text", text,
                            "distance", 0.0));
                }
                JsonService.send(exchange, 200, Map.of(
                        "answer", answer,
                        "model", "tinyllama",
                        "contexts", contexts));
            } catch (Exception e) {
                e.printStackTrace();
                JsonService.send(exchange, 500, Map.of(
                        "error", "Document question answering failed.",
                        "contexts", List.of()));
            }
        }
    }

    private static boolean isSupportedAlgorithm(String algorithm) {
        return "bruteforce".equals(algorithm)
                || "kdtree-exact".equals(algorithm)
                || "hnsw-experimental".equals(algorithm);
    }
}