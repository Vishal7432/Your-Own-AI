package stores;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import model.VectorItem;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class VectorStorage {

    private static final String FILE = "vector_data.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void save(VectorDatabase db) {
        try (Writer writer = Files.newBufferedWriter(Path.of(FILE), StandardCharsets.UTF_8)) {
            GSON.toJson(db.getAll(), writer);
            System.out.println("Saved " + db.size() + " vectors to " + FILE);
        } catch (IOException e) {
            throw new IllegalStateException("Could not save vectors to " + FILE, e);
        }
    }

    public static void load(VectorDatabase db) {
        Path path = Path.of(FILE);
        if (!Files.exists(path)) {
            System.out.println("No saved data found, starting fresh.");
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            VectorItem[] items = GSON.fromJson(reader, VectorItem[].class);
            if (items == null) {
                throw new JsonParseException("Expected a JSON array of vector items.");
            }
            for (VectorItem item : items) {
                if (item == null || item.text == null || item.category == null || item.embedding == null) {
                    throw new JsonParseException("Vector data contains an item with missing fields.");
                }
                db.add(item);
            }
            System.out.println("Loaded " + db.size() + " vectors from " + FILE);
        } catch (IOException | JsonParseException e) {
            throw new IllegalStateException("Could not load vector data from " + FILE, e);
        }
    }
}
