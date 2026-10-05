package com.example.aichatclient;

import java.util.ArrayList;
import java.util.List;

public class TextChunker {

    public static List<String> chunk(String raw, int size, int overlap) {
        String text = raw.replaceAll("\\s+", " ").trim();
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + size);
            if (end < text.length()) end = snap(text, i + size * 3 / 4, end);
            String piece = text.substring(i, end).trim();
            if (piece.length() > 20 || out.isEmpty()) out.add(piece);
            if (end >= text.length()) break;

            int next = Math.max(end - overlap, i + 1);
            while (next < end && text.charAt(next - 1) != ' ') next++;   // start on a word boundary
            i = next;
        }
        return out;
    }

    /** Prefer ending at a sentence end, otherwise at a space, within [from, end]. */
    private static int snap(String text, int from, int end) {
        for (int k = end; k > from; k--) {
            char c = text.charAt(k - 1);
            if (c == '.' || c == '!' || c == '?') return k;
        }
        for (int k = end; k > from; k--) {
            if (text.charAt(k - 1) == ' ') return k;
        }
        return end;
    }
}