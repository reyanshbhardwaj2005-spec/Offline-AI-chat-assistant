package com.example.aichatclient;

public class Message {
    public static final int USER = 0;
    public static final int AI = 1;

    public final int role;
    public String text;
    public boolean error = false;
    public boolean streaming = false;

    public Message(int role, String text) {
        this.role = role;
        this.text = text;
    }
}