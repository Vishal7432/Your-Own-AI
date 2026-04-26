import com.sun.net.httpserver.HttpServer;

import model.VectorItem;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;

import service.EmdService;
import service.RAGService;
import stores.VectorDatabase;
import stores.VectorStorage;
import service.PDFService;

import java.net.URI;
import java.net.URL;
import java.net.HttpURLConnection;

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

            if ("POST".equals(exchange.getRequestMethod())) {

                System.out.println("Request received");

                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes());

                String question = "";
                String algo = "bruteforce";

                if (body.contains("\"algo\":\"")) {
                    question = body.split("\"question\":\"")[1].split("\"")[0];
                    algo = body.split("\"algo\":\"")[1].split("\"")[0];
                } else {
                    question = body.replace("{\"question\":\"", "").replace("\"}", "");
                }

                System.out.println("Question = " + question + " | Algo = " + algo);

                // ✅ Streaming headers
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, 0);

                OutputStream os = exchange.getResponseBody();
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, "UTF-8"), true);

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

                    // // ✅ Context word by word stream karo
                    // String[] words = context.split(" ");

                    // for (String word : words) {
                    // if (word.isEmpty())
                    // continue;

                    // writer.write("data: " + word + " \n\n");
                    // writer.flush();

                    // Thread.sleep(80);
                    // }

                    // ✅ Ollama streaming call
                    URI uri = URI.create("http://localhost:11434/api/generate");
                    URL url = uri.toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(180000);

                    String prompt = context + " Q: " + question + " A:";
                    String safePrompt = prompt
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n");

                    String jsonInput = "{ \"model\": \"tinyllama\", \"prompt\": \"" + safePrompt
                            + "\", \"stream\": true, \"temperature\": 0, \"num_predict\": 80}";

                    OutputStream ollamaOs = conn.getOutputStream();
                    ollamaOs.write(jsonInput.getBytes("utf-8"));
                    ollamaOs.flush();

                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));

                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.isEmpty())
                            continue;

                        int start = line.indexOf("\"response\":\"") + 12;
                        int end = line.indexOf("\"", start);

                        if (start > 11 && end > start) {
                            String token = line.substring(start, end);
                            token = token.replace("\\n", " ");

                            // ✅ SSE format mein bhejo
                            writer.write("data: " + token + "\n\n");
                            writer.flush();
                        }

                        if (line.contains("\"done\":true"))
                            break;
                    }

                    br.close();
                    conn.disconnect();

                } catch (Exception e) {
                    writer.write("data: Error: " + e.getMessage() + "\n\n");
                    writer.flush();
                }

                // ✅ Stream end signal
                writer.write("data: [DONE]\n\n");
                writer.flush();
                os.close();
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

            System.out.println(exchange.getRequestMethod());

            // ADD THIS FIRST
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

            // ADD THIS TOO
            if ("POST".equals(exchange.getRequestMethod())) {

                String body = new String(exchange.getRequestBody().readAllBytes());

                String text = body.replace("{\"doc\":\"", "")
                        .replace("\"}", "");

                try {

                    float[] emb = EmdService.getEmbedding(text);

                    db.add(new VectorItem(db.size() + 1, text, "doc", emb));
                    VectorStorage.save(db);

                    String response = "{\"status\":\"inserted\"}";

                    exchange.getResponseHeaders().add(
                            "Access-Control-Allow-Origin", "*");

                    exchange.sendResponseHeaders(200, response.length());

                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes());
                    os.close();

                } catch (Exception e) {
                    e.printStackTrace();
                }
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

                    String response = "{\"status\":\"ok\",\"chunks\":" + inserted + "}";

                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(200, response.length());

                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes());
                    os.close();

                } catch (Exception e) {
                    e.printStackTrace();
                    String response = "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(500, response.length());
                    OutputStream os = exchange.getResponseBody();
                    os.write(response.getBytes());
                    os.close();
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

            if ("POST".equals(exchange.getRequestMethod())) {

                String body = new String(exchange.getRequestBody().readAllBytes());

                // parse query aur algo
                String query = "";
                String algo = "bruteforce";

                if (body.contains("\"algo\":\"")) {
                    query = body.split("\"query\":\"")[1].split("\"")[0];
                    algo = body.split("\"algo\":\"")[1].split("\"")[0];
                } else {
                    query = body.replace("{\"query\":\"", "").replace("\"}", "");
                }

                System.out.println("Search: " + query + " | Algo: " + algo);

                try {
                    // ✅ Embedding + timing
                    float[] queryVec = service.EmdService.getEmbedding(query);

                    long startTime = System.currentTimeMillis();

                    java.util.List<model.VectorItem> results = service.SearchService.topK(db, queryVec, 5, algo);

                    long latency = System.currentTimeMillis() - startTime;

                    // ✅ JSON response banao
                    StringBuilder json = new StringBuilder();
                    json.append("{\"latency\":").append(latency).append(",\"results\":[");

                    for (int i = 0; i < results.size(); i++) {
                        model.VectorItem v = results.get(i);
                        String text = v.getText()
                                .replace("\"", "\\\"")
                                .replace("\n", " ");
                        if (text.length() > 100)
                            text = text.substring(0, 100);

                        json.append("{\"rank\":").append(i + 1)
                                .append(",\"text\":\"").append(text).append("\"")
                                .append(",\"category\":\"").append(v.category).append("\"}");

                        if (i < results.size() - 1)
                            json.append(",");
                    }
                    json.append("]}");

                    String response = json.toString()

                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    byte[] responseBytes = response.getBytes("UTF-8");
exchange.sendResponseHeaders(200, responseBytes.length);

                    OutputStream os = exchange.getResponseBody();
                    os.write(responseBytes);
                    os.close();

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}