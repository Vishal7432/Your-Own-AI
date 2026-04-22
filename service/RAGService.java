package service;

import java.util.*;
import stores.VectorDatabase;
import model.VectorItem;
// import service.LLMService;

public class RAGService {

    public static String ask(VectorDatabase db, String query) {

        float[] queryVec;
        try {
            queryVec = EmdService.getEmbedding(query);
        } catch (Exception e) {
            return "Error getting embedding: " + e.getMessage();
        }

        List<VectorItem> results = SearchService.topK(db, queryVec, 3);

        StringBuilder combined = new StringBuilder();

        for (VectorItem v : results) {
            combined.append(v.getText()).append("\n");
        }

        String context = combined.toString();

        System.out.println("Calling LLM...");
        // System.out.println("Context:\n" + context);
        // System.out.println("Query: " + query);

        try {
            return LLMService.askLLM(context, query);
        } catch (Exception e) {
            return "Error calling LLM: " + e.getMessage();
        }
    }
}