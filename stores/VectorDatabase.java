package stores;

import java.util.*;
import model.VectorItem;

public class VectorDatabase {
    List<VectorItem> data = new ArrayList<>();

    public void add(VectorItem item) {
        data.add(item);
    }

    public List<VectorItem> getAll() {
        return data;
    }
}