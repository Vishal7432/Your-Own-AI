package service;

import java.io.*;
import java.net.*;

// pseudo (simplified)  // (Ollama Call)
public class EmdService {

    public static float[] getEmbedding(String text) throws Exception {
        // HTTP call to Ollama
        URI uri = URI.create("http://localhost:11434/api/embeddings");
        URL url = uri.toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        // parse JSON → float[]

        String jsonInput = "{ \"model\": \"nomic-embed-text\", \"prompt\": \"" + text + "\" }";

        OutputStream os = conn.getOutputStream();
        os.write(jsonInput.getBytes("utf-8"));
        os.flush();

        BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));

        StringBuilder response = new StringBuilder();
        String line;

        while ((line = br.readLine()) != null) {
            response.append(line);
        }
        br.close();

        // 🔥 STEP 1: response ko String banao
        String res = response.toString();

        // 🔥 STEP 2: embedding array extract karo
        int start = res.indexOf("\"embedding\":[") + 13;
        int end = res.indexOf("]", start);

        String arr = res.substring(start, end);

        // 🔥 STEP 3: split karke float[] banao
        String[] nums = arr.split(",");
        float[] vector = new float[nums.length];

        for (int i = 0; i < nums.length; i++) {
            vector[i] = Float.parseFloat(nums[i]);
        }

        // 🔥 DEBUG (check size)
        System.out.println("Vector size: " + vector.length);

        conn.disconnect();

        // ✅ FINAL RETURN (IMPORTANT)
        return vector; // placeholder
    }
}
