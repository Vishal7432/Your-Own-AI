package model;

public class VectorItem {
    public int id;
    public String metadata;
    public String category;
    public float[] embedding;

    public VectorItem(int id, String metadata, String category, float[] embedding) {
        this.id = id;
        this.metadata = metadata;
        this.category = category;
        this.embedding = embedding;
    }
}