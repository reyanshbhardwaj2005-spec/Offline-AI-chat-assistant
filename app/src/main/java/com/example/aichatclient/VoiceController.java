package com.example.aichatclient;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Speech-to-text (microphone) and text-to-speech (read aloud) in one place. */
public class VoiceController {

    public static final String[] LANGUAGE_LABELS =
            {"Device language", "English (India)", "English (US)", "Hindi"};

    public interface Callbacks {
        void onListeningChanged(boolean listening);
        void onPartialText(String text);          // live text while you speak
        void onFinalText(String text);            // final recognized text
        void onMessage(String message);           // errors and hints
        void onSpeakingChanged(Message speaking); // null when speech stopped
        void requestMicPermission();
    }

    private final AppCompatActivity act;
    private final SharedPreferences prefs;
    private final Callbacks cb;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ---- speech to text
    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private boolean usedOnDevice = false;
    private String baseText = "";
    private int session = 0;

    // ---- text to speech
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private Message speaking = null;
    private volatile int token = 0;

    public VoiceController(AppCompatActivity act, SharedPreferences prefs, Callbacks cb) {
        this.act = act;
        this.prefs = prefs;
        this.cb = cb;

        tts = new TextToSpeech(act.getApplicationContext(), status -> {
            ttsReady = (status == TextToSpeech.SUCCESS);
            if (!ttsReady) return;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }

                @Override public void onDone(String utteranceId) {
                    if (utteranceId != null && utteranceId.endsWith(":last")) {
                        finishSpeaking(tokenOf(utteranceId));
                    }
                }

                @Override public void onError(String utteranceId) {
                    finishSpeaking(tokenOf(utteranceId));
                }
            });
        });
    }

    // ================================================================ language

    private Locale selectedLocale() {
        switch (prefs.getInt("voice_lang", 0)) {
            case 1: return Locale.forLanguageTag("en-IN");
            case 2: return Locale.forLanguageTag("en-US");
            case 3: return Locale.forLanguageTag("hi-IN");
            default: return Locale.getDefault();
        }
    }

    // ================================================================ speech to text

    public boolean isListening() { return listening; }

    public void toggleMic(String currentText) {
        if (listening) {
            stopListening();
            return;
        }
        stopSpeaking();
        if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            cb.requestMicPermission();
            return;
        }
        startListening(currentText, false);
    }

    public void onPermissionResult(boolean granted, String currentText) {
        if (granted) startListening(currentText, false);
        else cb.onMessage("Microphone permission is needed for voice input.");
    }

    private void startListening(String currentText, boolean forceSystem) {
        cancelListening();
        boolean strictOffline = prefs.getBoolean("voice_offline_only", false);

        SpeechRecognizer r = null;
        usedOnDevice = false;
        if (!forceSystem && Build.VERSION.SDK_INT >= 33
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(act)) {
            r = SpeechRecognizer.createOnDeviceSpeechRecognizer(act);
            usedOnDevice = true;
        } else if (strictOffline) {
            cb.onMessage("On-device speech recognition isn't available on this phone. "
                    + "Turn off \"Offline voice only\" in Settings to use the system recognizer.");
            return;
        } else if (SpeechRecognizer.isRecognitionAvailable(act)) {
            r = SpeechRecognizer.createSpeechRecognizer(act);
        }
        if (r == null) {
            cb.onMessage("Speech recognition isn't available on this phone.");
            return;
        }

        recognizer = r;
        baseText = (currentText == null || currentText.trim().isEmpty())
                ? "" : currentText.trim() + " ";
        final int sid = ++session;
        r.setRecognitionListener(makeListener(sid));

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLocale().toLanguageTag());
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, act.getPackageName());
        try {
            r.startListening(i);
            setListening(true);
        } catch (Exception e) {
            releaseRecognizer();
            cb.onMessage("Could not start voice input.");
        }
    }

    /** Finish: the recognizer delivers the final text. */
    public void stopListening() {
        if (recognizer != null && listening) {
            try { recognizer.stopListening(); } catch (Exception ignored) { }
        }
    }

    /** Abort and discard. */
    public void cancelListening() {
        SpeechRecognizer r = recognizer;
        if (r != null) {
            try { r.cancel(); } catch (Exception ignored) { }
        }
        releaseRecognizer();
        session++;                      // ignore any late callbacks from the old recognizer
        if (listening) setListening(false);
    }

    private void releaseRecognizer() {
        final SpeechRecognizer r = recognizer;
        recognizer = null;
        if (r != null) main.post(r::destroy);
    }

    private void setListening(boolean value) {
        listening = value;
        cb.onListeningChanged(value);
    }

    private RecognitionListener makeListener(final int sid) {
        return new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int eventType, Bundle params) { }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (sid != session) return;
                String t = firstResult(partialResults);
                if (t != null) cb.onPartialText(baseText + t);
            }

            @Override
            public void onResults(Bundle results) {
                if (sid != session) return;
                String t = firstResult(results);
                setListening(false);
                releaseRecognizer();
                if (t == null || t.trim().isEmpty()) {
                    cb.onMessage("Didn't catch that. Tap the mic and try again.");
                    return;
                }
                cb.onFinalText(baseText + t);
            }

            @Override
            public void onError(int error) {
                if (sid != session) return;
                boolean wasOnDevice = usedOnDevice;
                setListening(false);
                releaseRecognizer();
                if (error == SpeechRecognizer.ERROR_CLIENT) return;      // we cancelled it

                // On-device language pack missing: fall back to the system recognizer
                if ((error == 12 || error == 13) && wasOnDevice
                        && !prefs.getBoolean("voice_offline_only", false)) {
                    startListening(baseText, true);
                    return;
                }
                cb.onMessage(errorMessage(error));
            }
        };
    }

    private static String firstResult(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    private static String errorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Didn't catch that. Tap the mic and try again.";
            case SpeechRecognizer.ERROR_AUDIO:
                return "Audio recording problem. Check your microphone.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Microphone permission is needed for voice input.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Speech recognition needs an offline language pack (or a network). "
                        + "In the Google app, look for Settings → Voice → Offline speech "
                        + "recognition and download your language.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "The recognizer is busy. Try again in a moment.";
            case 12:
            case 13:
                return "That language isn't available offline. Download its offline speech "
                        + "pack, or pick another voice language in Settings.";
            default:
                return "Voice input failed (error " + error + ").";
        }
    }

    // ================================================================ text to speech

    public Message getSpeaking() { return speaking; }

    public void toggleSpeak(Message m) {
        if (speaking == m) stopSpeaking();
        else speakMessage(m);
    }

    private void speakMessage(Message m) {
        if (!ttsReady) {
            cb.onMessage("Text-to-speech is still starting. Try again in a moment.");
            return;
        }
        cancelListening();                          // don't let the mic hear the speaker

        String clean = cleanForSpeech(m.text);
        if (clean.isEmpty()) return;

        Locale loc = mostlyDevanagari(clean) ? Locale.forLanguageTag("hi-IN") : selectedLocale();
        int r = tts.setLanguage(loc);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            cb.onMessage("No voice installed for " + loc.getDisplayName() + ". Install one in "
                    + "Settings → General management → Language → Text-to-speech.");
            return;
        }

        tts.stop();
        final int myToken = ++token;
        speaking = m;
        cb.onSpeakingChanged(m);

        int max = Math.min(1500, TextToSpeech.getMaxSpeechInputLength() - 50);
        List<String> parts = split(clean, max);
        for (int i = 0; i < parts.size(); i++) {
            String id = myToken + ":" + i + (i == parts.size() - 1 ? ":last" : "");
            int res = tts.speak(parts.get(i),
                    i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, id);
            if (res == TextToSpeech.ERROR) {
                stopSpeaking();
                cb.onMessage("Could not start speech.");
                return;
            }
        }
    }

    public void stopSpeaking() {
        token++;                                    // late callbacks from the old speech are ignored
        if (tts != null) tts.stop();
        if (speaking != null) {
            speaking = null;
            cb.onSpeakingChanged(null);
        }
    }

    private void finishSpeaking(final int forToken) {
        act.runOnUiThread(() -> {
            if (forToken == token && speaking != null) {
                speaking = null;
                cb.onSpeakingChanged(null);
            }
        });
    }

    private static int tokenOf(String id) {
        try {
            return Integer.parseInt(id.substring(0, id.indexOf(':')));
        } catch (Exception e) {
            return -1;
        }
    }

    public void shutdown() {
        cancelListening();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    // ================================================================ text helpers

    private static String cleanForSpeech(String text) {
        return text
                .replaceAll("```[\\s\\S]*?```", " code block ")
                .replaceAll("https?://\\S+", " link ")
                .replaceAll("[*_#`>~|]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean mostlyDevanagari(String s) {
        int dev = 0, letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (c >= 0x0900 && c <= 0x097F) dev++;
            }
        }
        return letters > 0 && dev * 2 > letters;
    }

    /** Splits long text at sentence ends so each piece fits the TTS engine's limit. */
    private static List<String> split(String text, int max) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + max);
            if (end < text.length()) {
                int cut = -1;
                for (int k = end; k > i + max / 2; k--) {
                    char c = text.charAt(k - 1);
                    if (c == '.' || c == '!' || c == '?' || c == '।') { cut = k; break; }
                }
                if (cut < 0) {
                    for (int k = end; k > i + max / 2; k--) {
                        if (text.charAt(k - 1) == ' ') { cut = k; break; }
                    }
                }
                if (cut > 0) end = cut;
            }
            String piece = text.substring(i, end).trim();
            if (!piece.isEmpty()) out.add(piece);
            i = end;
        }
        return out;
    }
}