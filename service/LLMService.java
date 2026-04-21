package service;

import java.io.*;
import java.net.*;

public class LLMService {

        public static String askLLM(String context, String question) throws Exception {

                // URL url = new URL("http://localhost:11434/api/generate");
                // HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                URI uri = URI.create("http://localhost:11434/api/generate");
                URL url = uri.toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                // conn.setConnectTimeout(10000); // 10 sec
                // conn.setReadTimeout(20000); // 20 sec

                // Prompt (VERY IMPORTANT)
                String prompt = "Context:\n" + context + "\n\n" +
                                "Question: " + "\n" +
                                "Give a short direct answer:\n";

                String safePrompt = prompt
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"")
                                .replace("\n", "\\n");

                String jsonInput = "{ \"model\": \"tinyllama\", \"prompt\": \"" + safePrompt
                                + "\", \"stream\": false, \"num_predict\": 15}";

                OutputStream os = conn.getOutputStream();
                os.write(jsonInput.getBytes("utf-8"));
                os.flush();

                BufferedReader br = new BufferedReader(
                                new InputStreamReader(conn.getInputStream()));

                StringBuilder response = new StringBuilder();
                String line;
                // System.out.println(jsonInput);

                while ((line = br.readLine()) != null) {
                        response.append(line);
                }
                br.close();

                String res = response.toString();

                // response extract
                int start = res.indexOf("\"response\":\"") + 12;
                int end = res.lastIndexOf("\"");

                String answer = res.substring(start, end);

                conn.disconnect();

                answer = answer.replace("\\n", " ").trim();
                return answer;
        }
}