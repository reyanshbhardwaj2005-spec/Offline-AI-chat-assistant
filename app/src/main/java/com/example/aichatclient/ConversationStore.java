package com.example.aichatclient;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class ConversationStore extends SQLiteOpenHelper {

    public static class Conv {
        public final long id;
        public final String title;

        Conv(long id, String title) {
            this.id = id;
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public ConversationStore(Context ctx) {
        super(ctx, "chats.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversations ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "conv_id INTEGER NOT NULL,"
                + "role INTEGER NOT NULL,"
                + "text TEXT NOT NULL,"
                + "error INTEGER NOT NULL DEFAULT 0,"
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_messages_conv ON messages(conv_id, id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { }

    public synchronized long createConversation(String title) {
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        cv.put("created_at", now);
        cv.put("updated_at", now);
        return getWritableDatabase().insert("conversations", null, cv);
    }

    public synchronized void addMessage(long convId, Message m) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            cv.put("conv_id", convId);
            cv.put("role", m.role);
            cv.put("text", m.text);
            cv.put("error", m.error ? 1 : 0);
            cv.put("created_at", now);
            db.insert("messages", null, cv);

            ContentValues up = new ContentValues();
            up.put("updated_at", now);
            db.update("conversations", up, "id=?", new String[]{String.valueOf(convId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<Message> loadMessages(long convId) {
        List<Message> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT role, text, error FROM messages WHERE conv_id=? ORDER BY id",
                new String[]{String.valueOf(convId)})) {
            while (c.moveToNext()) {
                Message m = new Message(c.getInt(0), c.getString(1));
                m.error = c.getInt(2) == 1;
                out.add(m);
            }
        }
        return out;
    }

    /** Most recently active first. */
    public synchronized List<Conv> listConversations() {
        List<Conv> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, title FROM conversations ORDER BY updated_at DESC, id DESC", null)) {
            while (c.moveToNext()) out.add(new Conv(c.getLong(0), c.getString(1)));
        }
        return out;
    }

    public synchronized void rename(long id, String title) {
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        getWritableDatabase().update("conversations", cv, "id=?",
                new String[]{String.valueOf(id)});
    }

    public synchronized void delete(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String[] arg = {String.valueOf(id)};
            db.delete("messages", "conv_id=?", arg);
            db.delete("conversations", "id=?", arg);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void deleteAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("messages", null, null);
            db.delete("conversations", null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }
}