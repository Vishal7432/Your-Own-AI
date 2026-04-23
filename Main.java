
import stores.VectorDatabase;
import model.VectorItem;
import service.RAGService;

public class Main {
    public static void main(String[] args) {

        VectorDatabase db = new VectorDatabase();

        // db.add(new VectorItem(1, "Java is a programming language", "tech", new
        // float[] { 0.2f, 0.3f }));
        // db.add(new VectorItem(2, "AI is future", "tech", new float[] { 0.5f, 0.6f
        // }));

        // String response = RAGService.ask(db, "What is java?");

        // System.out.println(response);
    }
}