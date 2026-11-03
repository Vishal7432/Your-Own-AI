package service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class LLMService {

        public static String askLLM(String context, String question) throws Exception {

                URI uri = URI.create("http://localhost:11434/api/generate");
                URL url = uri.toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(12000);

                String prompt = "You are a helpful assistant. Answer the user's question directly. " +
                                "Use the provided context if it contains relevant information. " +
                                "If it doesn't, just use your own general knowledge. " +
                                "IMPORTANT: Do NOT mention the 'context', 'provided text', or say things like 'the context doesn't mention'. "
                                +
                                "Just answer the question naturally.\n\n" +
                                "Context:\n" + context +
                                "Question: " + question + "\n\n" +
                                "Answer: ";

                String jsonInput = JsonService.stringify(Map.of(
                                "model", "tinyllama",
                                "prompt", prompt,
                                "stream", true,
                                "temperature", 0,
                                "num_predict", 80));

                try (OutputStream os = conn.getOutputStream()) {
                        os.write(jsonInput.getBytes(StandardCharsets.UTF_8));
                }

                // Line by line read karo
                StringBuilder fullAnswer = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                                if (line.isEmpty()) {
                                        continue;
                                }
                                try {
                                        JsonObject chunk = JsonParser.parseString(line).getAsJsonObject();
                                        if (chunk.has("response")) {
                                                fullAnswer.append(chunk.get("response").getAsString());
                                        }
                                        if (chunk.has("done") && chunk.get("done").getAsBoolean()) {
                                                break;
                                        }
                                } catch (JsonParseException e) {
                                        throw new IOException("Ollama returned invalid JSON.", e);
                                }
                        }
                } finally {
                        conn.disconnect();
                }

                return fullAnswer.toString().trim();
        }
}