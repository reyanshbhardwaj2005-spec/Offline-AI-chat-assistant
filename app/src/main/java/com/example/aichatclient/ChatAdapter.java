package com.example.aichatclient;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.LruCache;
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
    private final LruCache<String, BitmapDrawable> thumbs = new LruCache<>(24);

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

        CharSequence content = m.text.isEmpty() ? "…" : m.text;
        if (m.imagePath != null) content = withImage(h.bubble, m.imagePath, content);
        h.bubble.setText(content);

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

    /** Puts the attached picture on top of the message text, inside the same bubble. */
    private CharSequence withImage(View v, String path, CharSequence text) {
        float density = v.getResources().getDisplayMetrics().density;
        int targetW = (int) (220 * density);

        BitmapDrawable d = thumbs.get(path);
        if (d == null) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0) return text;               // file missing: show text only
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(path, o);
            if (bmp == null) return text;
            d = new BitmapDrawable(v.getResources(), bmp);
            thumbs.put(path, d);
        }
        Bitmap b = d.getBitmap();
        int w = targetW;
        int h = Math.min((int) (260 * density), (int) (w * (b.getHeight() / (float) b.getWidth())));
        d.setBounds(0, 0, w, h);

        SpannableStringBuilder sb = new SpannableStringBuilder(" ");
        sb.setSpan(new ImageSpan(d, ImageSpan.ALIGN_BOTTOM), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (text.length() > 0) sb.append("\n").append(text);
        return sb;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
