package com.example.aichatclient;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {

    public interface SpeakListener {
        void onSpeak(Message m);
    }

    private final List<Message> items;
    private final SpeakListener listener;
    private Message speaking = null;

    public ChatAdapter(List<Message> items, SpeakListener listener) {
        this.items = items;
        this.listener = listener;
    }

    /** Which message is being read aloud right now (null = none). */
    public void setSpeaking(Message m) {
        Message old = speaking;
        speaking = m;
        int a = old == null ? -1 : items.indexOf(old);
        int b = m == null ? -1 : items.indexOf(m);
        if (a >= 0) notifyItemChanged(a);
        if (b >= 0) notifyItemChanged(b);
    }

    static class VH extends RecyclerView.ViewHolder {
        final View container;
        final TextView bubble, speak;

        VH(View v) {
            super(v);
            container = v.findViewById(R.id.container);
            bubble = v.findViewById(R.id.bubble);
            speak = v.findViewById(R.id.speakBtn);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Message m = items.get(position);
        boolean user = m.role == Message.USER;

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16 * h.bubble.getResources().getDisplayMetrics().density);
        int bgColor = m.error ? 0xFFFEE2E2 : (user ? 0xFF2563EB : 0xFFE5E7EB);
        int fgColor = m.error ? 0xFF991B1B : (user ? 0xFFFFFFFF : 0xFF111827);
        bg.setColor(bgColor);
        h.bubble.setBackground(bg);
        h.bubble.setTextColor(fgColor);
        h.bubble.setText(m.text.isEmpty() ? "…" : m.text);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.container.getLayoutParams();
        lp.gravity = user ? Gravity.END : Gravity.START;
        h.container.setLayoutParams(lp);

        // speaker button: only under finished AI replies
        boolean canSpeak = !user && !m.error && !m.streaming && !m.text.trim().isEmpty();
        h.speak.setVisibility(canSpeak ? View.VISIBLE : View.GONE);
        boolean isSpeaking = (m == speaking);
        h.speak.setText(isSpeaking ? "⏹  Stop" : "🔊");
        h.speak.setContentDescription(isSpeaking ? "Stop reading" : "Read aloud");
        h.speak.setOnClickListener(v -> listener.onSpeak(m));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}