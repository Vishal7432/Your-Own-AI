package model;

public class VectorItem {
    public int id;
    public String text;
    public String category;
    public float[] embedding;

    public VectorItem(int id, String text, String category, float[] embedding) {
        this.id = id;
        this.text = text;
        this.category = category;
        this.embedding = embedding;
    }

    public String getText() {
        return text;
    }
}