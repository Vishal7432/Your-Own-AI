package service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.Loader;

import java.io.*;
import java.util.*;

public class PDFService {

    // ✅ PDF se text nikalo
    public static String extractText(byte[] pdfBytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    // ✅ Text ko chunks mein todo (300 words each, 50 word overlap)
    public static List<String> chunkText(String text, int chunkSize, int overlap) {
        String[] words = text.split("\\s+");
        List<String> chunks = new ArrayList<>();

        int i = 0;
        while (i < words.length) {
            int end = Math.min(i + chunkSize, words.length);
            StringBuilder chunk = new StringBuilder();
            for (int j = i; j < end; j++) {
                chunk.append(words[j]).append(" ");
            }
            chunks.add(chunk.toString().trim());
            i += chunkSize - overlap;
        }

        return chunks;
    }
}