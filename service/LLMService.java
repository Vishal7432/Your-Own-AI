package service;

import java.io.*;
import java.net.*;

public class LLMService {

        public static String askLLM(String context, String question) throws Exception {

                URI uri = URI.create("http://localhost:11434/api/generate");
                URL url = uri.toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
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

                String safePrompt = prompt
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n");

                // ✅ stream: true karo
                String jsonInput = "{ \"model\": \"tinyllama\", \"prompt\": \"" + safePrompt
                                + "\", \"stream\": true, \"temperature\": 0, \"num_predict\": 80}";

                OutputStream os = conn.getOutputStream();
                os.write(jsonInput.getBytes("utf-8"));
                os.flush();

                // ✅ Line by line read karo
                BufferedReader br = new BufferedReader(
                                new InputStreamReader(conn.getInputStream()));

                StringBuilder fullAnswer = new StringBuilder();
                String line;

                while ((line = br.readLine()) != null) {
                        if (line.isEmpty())
                                continue;

                        // har line ek JSON chunk hai
                        int start = line.indexOf("\"response\":\"") + 12;
                        int end = line.indexOf("\"", start);

                        if (start > 11 && end > start) {
                                String token = line.substring(start, end);
                                token = token.replace("\\n", " ");
                                fullAnswer.append(token);
                        }

                        // done check karo
                        if (line.contains("\"done\":true"))
                                break;
                }

                br.close();
                conn.disconnect();

                return fullAnswer.toString().trim();
        }
}