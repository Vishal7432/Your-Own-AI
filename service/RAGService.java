package service;

import java.util.*;
import stores.VectorDatabase;
import model.VectorItem;
import service.LLMService;

public class RAGService {

    public static String ask(VectorDatabase db, String query) {

        float[] queryVec;
        try {
            queryVec = EmdService.getEmbedding(query);
        } catch (Exception e) {
            return "Error getting embedding: " + e.getMessage();
        }

        List<VectorItem> context = SearchService.topK(db, queryVec, 3);

        StringBuilder combined = new StringBuilder();

        for (VectorItem v : context) {
            combined.append(v.metadata).append("\n");
        }
        System.out.println("Calling LLM...");
        // send to LLM
        try {
            return LLMService.askLLM(combined.toString(), query);
        } catch (Exception e) {
            return "Error calling LLM: " + e.getMessage();
        }
    }
}