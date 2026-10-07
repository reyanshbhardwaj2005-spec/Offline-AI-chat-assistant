package com.example.aichatclient;

public class TokenEstimator {

    /** Rough token count: ~3.8 chars per token for English, ~1.3 for other scripts (Hindi etc.). */
    public static int estimate(String s) {
        if (s == null || s.isEmpty()) return 0;
        int ascii = 0, other = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < 128) ascii++;
            else other++;
        }
        return (int) Math.ceil(ascii / 3.8 + other / 1.3);
    }

    /** Cuts s so that its estimate fits in maxTokens. */
    public static String trim(String s, int maxTokens) {
        int t = estimate(s);
        if (t <= maxTokens) return s;
        int keep = (int) (s.length() * (maxTokens / (double) t));
        return s.substring(0, Math.max(0, keep)).trim() + "…";
    }
}
