package stores;

import java.util.*;
import model.VectorItem;

public class VectorDatabase {
    List<VectorItem> data = new ArrayList<>();

    public void add(VectorItem item) {
        // Check for duplicate text
        for (VectorItem existing : data) {
            if (existing.text.equals(item.text)) {
                System.out.println("⚠️ Duplicate text found, skipping...");
                return;
            }
        }
        data.add(item);
    }

    public List<VectorItem> getAll() {
        return data;
    }

    public int size() {
        // TODO Auto-generated method stub
        return data.size();
    }
}