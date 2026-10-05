package com.example.aichatclient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WordPieceTokenizer {

    private final Map<String, Integer> vocab = new HashMap<>(40000);
    private final int clsId, sepId, unkId;

    public WordPieceTokenizer(File vocabFile) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(vocabFile), StandardCharsets.UTF_8))) {
            String line;
            int id = 0;
            while ((line = br.readLine()) != null) vocab.put(line, id++);
        }
        clsId = vocab.getOrDefault("[CLS]", 101);
        sepId = vocab.getOrDefault("[SEP]", 102);
        unkId = vocab.getOrDefault("[UNK]", 100);
    }

    /** Returns [CLS] tokens... [SEP], at most maxLen long. */
    public long[] encode(String text, int maxLen) {
        List<Integer> ids = new ArrayList<>();
        ids.add(clsId);
        outer:
        for (String word : basicTokenize(text)) {
            for (int id : wordPiece(word)) {
                if (ids.size() >= maxLen - 1) break outer;
                ids.add(id);
            }
        }
        ids.add(sepId);
        long[] out = new long[ids.size()];
        for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
        return out;
    }

    private List<Integer> wordPiece(String word) {
        List<Integer> result = new ArrayList<>();
        if (word.length() > 100) { result.add(unkId); return result; }
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            Integer found = null;
            while (start < end) {
                String sub = word.substring(start, end);
                if (start > 0) sub = "##" + sub;
                Integer id = vocab.get(sub);
                if (id != null) { found = id; break; }
                end--;
            }
            if (found == null) {          // unknown word: single [UNK]
                result.clear();
                result.add(unkId);
                return result;
            }
            result.add(found);
            start = end;
        }
        return result;
    }

    private static List<String> basicTokenize(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK) continue;       // strip accents
            if (cp == 0 || cp == 0xFFFD) continue;
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) { flush(cur, out); continue; }
            if (type == Character.CONTROL || type == Character.FORMAT) continue;
            if (isPunct(cp, type) || isCjk(cp)) {
                flush(cur, out);
                out.add(new String(Character.toChars(cp)));
                continue;
            }
            cur.appendCodePoint(cp);
        }
        flush(cur, out);
        return out;
    }

    private static void flush(StringBuilder cur, List<String> out) {
        if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
    }

    private static boolean isPunct(int cp, int type) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64)
                || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) return true;
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x20000 && cp <= 0x2A6DF) || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }
}