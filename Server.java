import com.sun.net.httpserver.HttpServer;

import model.VectorItem;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;

import service.RAGService;
import stores.VectorDatabase;

public class Server {

    public static void main(String[] args) throws Exception {

        // 1. Server create
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        // 2. Database create (ONLY ONCE)
        VectorDatabase db = new VectorDatabase();
        db.add(new VectorItem(1, "Java is a programming language", "tech", new float[] { 0.2f, 0.3f }));
        db.add(new VectorItem(2, "AI is future", "tech", new float[] { 0.5f, 0.6f }));
        ;

        // 3. Pass DB to handler
        server.createContext("/ask", new AskHandler(db));

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

            if ("POST".equals(exchange.getRequestMethod())) {

                // 1. request read
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes());

                // simple JSON parse
                String question = body.replace("{\"question\":\"", "")
                        .replace("\"}", "");

                String answer = "";

                try {
                    // 2. RAG call (IMPORTANT FIX)
                    answer = RAGService.ask(db, question);
                } catch (Exception e) {
                    answer = "Error: " + e.getMessage();
                }

                // 3. response JSON
                String response = "{\"answer\":\"" + answer + "\"}";

                // 4. headers (CORS fix)
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

                exchange.sendResponseHeaders(200, response.length());

                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
            }
        }
    }
}