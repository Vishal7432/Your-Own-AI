package service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class EmdService {

    public static float[] getEmbedding(String text) throws Exception {
        URI uri = URI.create("http://localhost:11434/api/embeddings");
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);

            String jsonInput = JsonService.stringify(Map.of(
                    "model", "nomic-embed-text",
                    "prompt", text));
            try (OutputStream output = conn.getOutputStream()) {
                output.write(jsonInput.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream responseStream = status >= 200 && status < 300
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String response;
            try (InputStream input = responseStream) {
                if (input == null) {
                    throw new IOException("Ollama embedding request failed with HTTP " + status + ".");
                }
                response = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Ollama embedding request failed with HTTP " + status + ": " + response);
            }

            JsonElement value = JsonParser.parseString(response).getAsJsonObject().get("embedding");
            if (value == null || !value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
                throw new IOException("Ollama response did not contain a non-empty embedding array.");
            }

            JsonArray values = value.getAsJsonArray();
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                JsonElement item = values.get(i);
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) {
                    throw new IOException("Ollama returned a non-numeric embedding value.");
                }
                vector[i] = item.getAsFloat();
                if (!Float.isFinite(vector[i])) {
                    throw new IOException("Ollama returned a non-finite embedding value.");
                }
            }
            return vector;
        } finally {
            conn.disconnect();
        }
    }
}
