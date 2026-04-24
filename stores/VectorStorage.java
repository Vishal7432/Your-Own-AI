package stores;

import model.VectorItem;
import java.io.*;
import java.util.*;

public class VectorStorage {

    private static final String FILE = "vector_data.json";

    // ✅ DB ko JSON file mein save karo
    public static void save(VectorDatabase db) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(FILE))) {
            pw.println("[");
            List<VectorItem> all = db.getAll();
            for (int i = 0; i < all.size(); i++) {
                VectorItem v = all.get(i);
                StringBuilder emb = new StringBuilder();
                for (int j = 0; j < v.embedding.length; j++) {
                    emb.append(v.embedding[j]);
                    if (j < v.embedding.length - 1)
                        emb.append(",");
                }
                pw.print("  {\"id\":" + v.id +
                        ",\"text\":\"" + v.text.replace("\"", "\\\"").replace("\n", "\\n") + "\"" +
                        ",\"category\":\"" + v.category + "\"" +
                        ",\"embedding\":[" + emb + "]}");
                if (i < all.size() - 1)
                    pw.println(",");
                else
                    pw.println();
            }
            pw.println("]");
            System.out.println("✅ Saved " + all.size() + " vectors to " + FILE);
        } catch (Exception e) {
            System.out.println("❌ Save failed: " + e.getMessage());
        }
    }

    // ✅ JSON file se DB load karo
    public static void load(VectorDatabase db) {
        File f = new File(FILE);
        if (!f.exists()) {
            System.out.println("No saved data found, starting fresh.");
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null)
                sb.append(line);
            String json = sb.toString().trim();

            // Remove outer [ ]
            json = json.substring(1, json.length() - 1).trim();
            if (json.isEmpty())
                return;

            // Split by },{
            String[] items = json.split("\\},\\s*\\{");
            for (String item : items) {
                item = item.replace("{", "").replace("}", "");

                int id = Integer.parseInt(extractField(item, "id"));
                String text = extractField(item, "text");
                String category = extractField(item, "category");
                String embStr = extractEmbedding(item);

                String[] parts = embStr.split(",");
                float[] emb = new float[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    emb[i] = Float.parseFloat(parts[i].trim());
                }

                db.add(new VectorItem(id, text, category, emb));
            }
            System.out.println("✅ Loaded " + db.size() + " vectors from " + FILE);
        } catch (Exception e) {
            System.out.println("❌ Load failed: " + e.getMessage());
        }
    }

    private static String extractField(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search) + search.length();
        if (json.charAt(start) == '"') {
            start++;
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        } else {
            int end = json.indexOf(",", start);
            if (end == -1)
                end = json.length();
            return json.substring(start, end).trim();
        }
    }

    private static String extractEmbedding(String json) {
        int start = json.indexOf("\"embedding\":[") + 13;
        int end = json.indexOf("]", start);
        return json.substring(start, end);
    }
}