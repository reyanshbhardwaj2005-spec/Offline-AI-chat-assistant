package com.example.aichatclient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ContextBuilder {

    /** Token budgets for one prompt (the reply is not included). */
    public static class Budget {
        public int total = 1500;
        public int summary = 220;
        public int memory = 420;
        public int minHistory = 250;
    }

    public static class Result {
        public String prompt = "";
        public int memoriesUsed = 0;
        public boolean usedSummary = false;
        public int historyMessages = 0;
        public int promptTokens = 0;
        public String debug = "";
    }

    /**
     * history = all finished messages of the chat, the latest user message last.
     * Short follow-ups ("why?", "when is it?") are searched together with the previous user message.
     */
    public static String retrievalQuery(String text, List<Message> history) {
        if (text.length() >= 30) return text;
        for (int i = history.size() - 2; i >= 0; i--) {
            Message m = history.get(i);
            if (m.role == Message.USER && !m.error) {
                String prev = m.text.length() > 300 ? m.text.substring(0, 300) : m.text;
                return text + ". " + prev;       // newest words first, so truncation cuts the old part
            }
        }
        return text;
    }

    /**
     * history: all finished messages, latest user message last.
     * covered: how many messages from the start are already inside the summary.
     */
    public static Result build(ChatTemplate template, String systemPrompt, String summary,
                               List<MemoryStore.Hit> hits, List<Message> history,
                               int covered, Budget b) {
        Result r = new Result();

        // ---- summary section
        String sum = summary == null ? "" : summary.trim();
        if (!sum.isEmpty()) sum = TokenEstimator.trim(sum, b.summary);
        String sumSection = sum.isEmpty() ? ""
                : "\n\nSummary of the earlier part of this conversation:\n" + sum;
        r.usedSummary = !sum.isEmpty();

        int sysTokens = TokenEstimator.estimate(systemPrompt) + 30;   // + chat-template tokens
        int sumTokens = TokenEstimator.estimate(sumSection);
        int floor = Math.max(0, Math.min(covered, history.size() - 1));

        // ---- which messages will be visible anyway? (so memories don't repeat them)
        List<Message> provisional = selectWindow(history, floor,
                Math.max(b.minHistory, b.total - sysTokens - sumTokens - b.memory));
        Set<String> visible = new HashSet<>();
        for (Message m : provisional) visible.add(m.text);

        // ---- memory section
        StringBuilder mem = new StringBuilder();
        StringBuilder memDebug = new StringBuilder();
        int memTokens = 0;
        if (hits != null) {
            for (MemoryStore.Hit h : hits) {
                if (visible.contains(h.row.content)) continue;
                String c = h.row.content.replace('\n', ' ').trim();
                String tag = MemoryStore.DOCUMENT.equals(h.row.type)
                        ? "(" + h.row.source + ") "
                        : "(" + ageLabel(h.row.createdAt) + ") ";
                String line = "- " + tag + c + "\n";
                int t = TokenEstimator.estimate(line);
                if (memTokens + t > b.memory) {
                    if (r.memoriesUsed > 0) break;
                    line = TokenEstimator.trim(line, b.memory) + "\n";   // keep one, trimmed
                    t = b.memory;
                }
                mem.append(line);
                memTokens += t;
                r.memoriesUsed++;
                memDebug.append(String.format(Locale.US, "%.2f  %s", h.score, line));
            }
        }
        String memSection = r.memoriesUsed == 0 ? ""
                : "\n\nThings you know about the user and their documents (items are dated; "
                + "if they conflict, trust the newer one):\n" + mem;

        // ---- recent messages get whatever budget is left
        List<Message> window = selectWindow(history, floor,
                Math.max(b.minHistory, b.total - sysTokens - sumTokens - memTokens));
        r.historyMessages = window.size();

        r.prompt = template.build(systemPrompt + sumSection + memSection, window);
        r.promptTokens = TokenEstimator.estimate(r.prompt);

        StringBuilder dbg = new StringBuilder();
        dbg.append("Prompt ≈ ").append(r.promptTokens).append(" tokens (budget ")
                .append(b.total).append(")\n");
        dbg.append("Summary used: ").append(r.usedSummary ? "yes" : "no").append("\n");
        dbg.append("Recent messages: ").append(r.historyMessages).append("\n");
        dbg.append("Memories used: ").append(r.memoriesUsed).append("\n");
        if (memDebug.length() > 0) dbg.append("\n").append(memDebug);
        r.debug = dbg.toString().trim();
        return r;
    }

    /** Newest messages that fit the budget, starting from index `floor`. */
    private static List<Message> selectWindow(List<Message> history, int floor, int budget) {
        List<Message> w = new ArrayList<>();
        int used = 0;
        for (int i = history.size() - 1; i >= floor; i--) {
            Message m = history.get(i);
            if (m.error) continue;
            int t = TokenEstimator.estimate(m.text) + 6;
            if (!w.isEmpty() && used + t > budget) break;
            used += t;
            w.add(0, m);
        }
        while (w.size() > 1 && w.get(0).role != Message.USER) w.remove(0);
        return w;
    }

    private static String ageLabel(long createdAt) {
        long days = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - createdAt);
        if (days <= 0) return "today";
        if (days == 1) return "yesterday";
        if (days < 14) return days + " days ago";
        if (days < 60) return (days / 7) + " weeks ago";
        return (days / 30) + " months ago";
    }
}