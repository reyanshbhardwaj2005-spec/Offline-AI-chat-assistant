package com.example.aichatclient;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.TextView;

/**
 * "Speak now" popup: red mic circle with a softly pulsing lavender halo.
 * Call show() when listening starts and dismiss() when it ends.
 */
public class VoiceListeningDialog {

    private final Context context;
    private final Runnable onCancel;     // called when the user taps outside / presses back
    private Dialog dialog;
    private ObjectAnimator pulse;

    public VoiceListeningDialog(Context context, Runnable onCancel) {
        this.context = context;
        this.onCancel = onCancel;
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public void show() { show("Speak now"); }

    public void show(String text) {
        if (isShowing()) return;
        if (context instanceof Activity) {
            Activity a = (Activity) context;
            if (a.isFinishing() || a.isDestroyed()) return;
        }

        View view = LayoutInflater.from(context).inflate(R.layout.dialog_voice, null);
        TextView label = view.findViewById(R.id.voiceText);
        label.setText(text);
        View halo = view.findViewById(R.id.voiceHalo);

        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(view);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnCancelListener(d -> {
            stopPulse();
            if (onCancel != null) onCancel.run();
        });

        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.9f);
            w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();

        pulse = ObjectAnimator.ofPropertyValuesHolder(halo,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.08f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.08f));
        pulse.setDuration(700);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.start();
    }

    public void dismiss() {
        stopPulse();
        if (dialog != null) {
            dialog.setOnCancelListener(null);   // a normal dismiss must not count as "cancel"
            if (dialog.isShowing()) {
                try { dialog.dismiss(); } catch (IllegalArgumentException ignored) { }
            }
            dialog = null;
        }
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
    }
}
