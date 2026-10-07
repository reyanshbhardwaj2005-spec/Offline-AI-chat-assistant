package com.example.aichatclient;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MemoryStore extends SQLiteOpenHelper {

    public static final String FACT = "fact";
    public static final String EPISODIC = "episodic";
    public static final String DOCUMENT = "document";

    public static class Row {
        public final long id;
        public final String type, source, content, lower;
        public final float[] vec;
        public final float importance;
        public final long createdAt;

        Row(long id, String type, String source, String content,
            float[] vec, float importance, long createdAt) {
            this.id = id; this.type = type; this.source = source;
            this.content = content; this.lower = content.toLowerCase(Locale.ROOT);
            this.vec = vec; this.importance = importance; this.createdAt = createdAt;
        }
    }

    public static class NewItem {
        final String type, source, content;
        final float[] vec;
        final float importance;

        public NewItem(String type, String source, String content, float[] vec, float importance) {
            this.type = type; this.source = source; this.content = content;
            this.vec = vec; this.importance = importance;
        }
    }

    public static class Hit {
        public final Row row;
        public final float sim;
        public final double score;

        Hit(Row row, float sim, double score) { this.row = row; this.sim = sim; this.score = score; }
    }

    public static class UiItem {
        public final String label, source, full;
        public final long id;               // -1 for a whole document

        UiItem(String label, long id, String source, String full) {
            this.label = label; this.id = id; this.source = source; this.full = full;
        }
    }

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the", "and", "for", "with", "what", "who", "how", "are", "you", "your", "this",
            "that", "have", "has", "was", "were", "can", "could", "would", "should", "about",
            "from", "tell", "give", "please", "when", "where", "why", "which", "does", "did",
            "not", "but", "any", "all", "its", "our", "his", "her", "them", "then", "than"));

    private final List<Row> cache = new ArrayList<>();
    private boolean loaded = false;

    public MemoryStore(Context ctx) {
        super(ctx, "memory.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE memory ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "type TEXT NOT NULL,"
                + "source TEXT,"
                + "content TEXT NOT NULL,"
                + "embedding BLOB NOT NULL,"
                + "importance REAL NOT NULL,"
                + "created_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { }

    private void ensureLoaded() {
        if (loaded) return;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT id, type, source, content, embedding, importance, created_at "
                        + "FROM memory ORDER BY id", null)) {
            while (c.moveToNext()) {
                cache.add(new Row(c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                        toFloats(c.getBlob(4)), c.getFloat(5), c.getLong(6)));
            }
        }
        loaded = true;
    }

    // ------------------------------------------------------------ writing

    public synchronized long add(String type, String source, String content,
                                 float[] vec, float importance) {
        List<NewItem> one = new ArrayList<>();
        one.add(new NewItem(type, source, content, vec, importance));
        return addAll(one);
    }

    public synchronized int addAll(List<NewItem> items) {
        ensureLoaded();
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        int added = 0;
        db.beginTransaction();
        try {
            for (NewItem it : items) {
                ContentValues cv = new ContentValues();
                cv.put("type", it.type);
                cv.put("source", it.source);
                cv.put("content", it.content);
                cv.put("embedding", toBytes(it.vec));
                cv.put("importance", it.importance);
                cv.put("created_at", now);
                long id = db.insert("memory", null, cv);
                if (id > 0) {
                    cache.add(new Row(id, it.type, it.source, it.content, it.vec, it.importance, now));
                    added++;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return added;
    }

    public synchronized void deleteById(long id) {
        ensureLoaded();
        getWritableDatabase().delete("memory", "id=?", new String[]{String.valueOf(id)});
        cache.removeIf(r -> r.id == id);
    }

    public synchronized void deleteSource(String source) {
        ensureLoaded();
        getWritableDatabase().delete("memory", "type=? AND source=?",
                new String[]{DOCUMENT, source});
        cache.removeIf(r -> DOCUMENT.equals(r.type) && source.equals(r.source));
    }

    public synchronized void clearAll() {
        ensureLoaded();
        getWritableDatabase().delete("memory", null, null);
        cache.clear();
    }

    // ------------------------------------------------------------ reading

    public synchronized int count() {
        ensureLoaded();
        return cache.size();
    }

    public synchronized float maxSimilarity(float[] vec) {
        ensureLoaded();
        float best = 0f;
        for (Row r : cache) best = Math.max(best, dot(vec, r.vec));
        return best;
    }

    /** Hybrid search: 65% meaning, 15% keywords, 10% recency, 10% importance. */
    public synchronized List<Hit> search(String queryText, float[] q, int topK, float minSim) {
        ensureLoaded();
        List<String> words = keywords(queryText);
        long now = System.currentTimeMillis();
        List<Hit> hits = new ArrayList<>();

        for (Row r : cache) {
            float sim = dot(q, r.vec);
            double kw = 0;
            if (!words.isEmpty()) {
                int found = 0;
                for (String w : words) if (r.lower.contains(w)) found++;
                kw = found / (double) words.size();
            }
            if (sim < minSim && kw < 0.5) continue;      // not relevant enough

            double ageDays = (now - r.createdAt) / 86400000.0;
            double recency = DOCUMENT.equals(r.type) ? 0.5 : Math.exp(-ageDays / 30.0);
            double score = 0.65 * sim + 0.15 * kw + 0.10 * recency + 0.10 * r.importance;
            hits.add(new Hit(r, sim, score));
        }
        Collections.sort(hits, (a, b) -> Double.compare(b.score, a.score));
        return hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
    }

    public synchronized List<UiItem> listForUi() {
        ensureLoaded();
        List<UiItem> out = new ArrayList<>();
        Map<String, Integer> docs = new LinkedHashMap<>();
        for (int i = cache.size() - 1; i >= 0; i--) {          // newest first
            Row r = cache.get(i);
            if (DOCUMENT.equals(r.type)) {
                Integer c = docs.get(r.source);
                docs.put(r.source, c == null ? 1 : c + 1);
            } else {
                String prefix = FACT.equals(r.type) ? "Fact: " : "Chat: ";
                out.add(new UiItem(prefix + trunc(r.content, 110), r.id, null, r.content));
            }
        }
        for (Map.Entry<String, Integer> e : docs.entrySet()) {
            out.add(new UiItem("Document: " + e.getKey() + " (" + e.getValue() + " chunks)",
                    -1, e.getKey(), e.getValue() + " chunks indexed from " + e.getKey()));
        }
        return out;
    }

    // ------------------------------------------------------------ helpers

    private static List<String> keywords(String text) {
        List<String> out = new ArrayList<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{M}\\p{N}]+")) {
            if (w.length() >= 3 && !STOP.contains(w) && !out.contains(w)) out.add(w);
            if (out.size() >= 10) break;
        }
        return out;
    }

    public static float dot(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        float s = 0f;
        for (int i = 0; i < n; i++) s += a[i] * b[i];
        return s;
    }

    private static String trunc(String s, int max) {
        s = s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static byte[] toBytes(float[] f) {
        ByteBuffer bb = ByteBuffer.allocate(f.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asFloatBuffer().put(f);
        return bb.array();
    }

    private static float[] toFloats(byte[] b) {
        float[] f = new float[b.length / 4];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(f);
        return f;
    }

    /** Keeps only the newest `keep` chat memories; facts and documents are never touched. */
    public synchronized int pruneEpisodic(int keep) {
        ensureLoaded();
        List<Row> chat = new ArrayList<>();
        for (Row r : cache) if (EPISODIC.equals(r.type)) chat.add(r);      // oldest first
        int extra = chat.size() - keep;
        if (extra <= 0) return 0;

        final Set<Long> ids = new HashSet<>();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < extra; i++) {
                long id = chat.get(i).id;
                ids.add(id);
                db.delete("memory", "id=?", new String[]{String.valueOf(id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        cache.removeIf(r -> ids.contains(r.id));
        return extra;
    }
}