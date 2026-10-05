package com.example.aichatclient;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.Manifest;
import android.widget.AdapterView;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AiChat";
    private static final int N_CTX = 4096;
    private static final int N_THREADS = 4;
    private static final int HISTORY_CHAR_BUDGET = 2500;
    private static final int MEMORY_CHAR_BUDGET = 1500;
    private static final int TOP_K = 5;
    private static final float MIN_SIM = 0.30f;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a helpful assistant. Keep answers short and clear.";
    private static final String EXTRACT_PROMPT =
            "You extract lasting personal facts about the user from their message, such as "
                    + "their name, preferences, plans, projects, studies or devices. Write each fact "
                    + "as one short sentence starting with \"The user\", one per line. Only use facts "
                    + "the message clearly states. If there are none, write exactly: NONE";
    private static final Pattern REMEMBER = Pattern.compile(
            "(?is)^\\s*(?:please\\s+)?remember(?:\\s+that)?\\s*[:,]?\\s+(.+)$");

    // ---- views
    private DrawerLayout drawerLayout;
    private View contentRoot, drawerPanel, userRow;
    private View pickerLayout, chatLayout, memoryLayout, settingsLayout, loadingLayout;
    private EditText searchBox, input, settingsNameEdit, systemPromptEdit;
    private ListView modelList, memoryListView, convList;
    private Spinner templateSpinner;
    private TextView emptyText, loadingText, chatTitle, modelTitle, statusText, memoryStatus,
            convEmpty, avatarText, drawerUserName, maxTokensLabel, tempLabel, aboutText;
    private Button importBtn, sendBtn, menuBtn, newChatTopBtn, memoryBackBtn, addNoteBtn,
            importDocBtn, clearAllBtn, embedderBtn, drawerNewChatBtn, drawerSettingsBtn,
            settingsBackBtn, resetPromptBtn, settingsModelBtn, settingsMemoryBtn, deleteAllChatsBtn;
    private SwitchCompat useMemorySwitch, learnFactsSwitch;
    private SeekBar maxTokensSeek, tempSeek;
    private RecyclerView chatList;

    // ---- model list
    private final List<ModelItem> allModels = new ArrayList<>();
    private final List<ModelItem> shownModels = new ArrayList<>();
    private ArrayAdapter<ModelItem> modelAdapter;

    // ---- chat + conversations
    private final List<Message> messages = new ArrayList<>();
    private ChatAdapter chatAdapter;
    private ConversationStore chatStore;
    private final List<ConversationStore.Conv> convs = new ArrayList<>();
    private ArrayAdapter<ConversationStore.Conv> convAdapter;
    private long currentConvId = -1;          // -1 = a new chat that has no messages yet

    // ---- memory
    private MemoryStore store;
    private volatile Embedder embedder;
    private volatile String embedderError = null;
    private SharedPreferences prefs;
    private final List<MemoryStore.UiItem> memoryItems = new ArrayList<>();
    private final List<String> memoryLabels = new ArrayList<>();
    private ArrayAdapter<String> memoryAdapter;
    private int memoryCount = 0;

    private final LlamaBridge llama = new LlamaBridge();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ChatTemplate template = ChatTemplate.CHATML;
    private boolean generating = false;
    private String loadedModelName = null;

    private final ActivityResultLauncher<String[]> importLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) importModel(uri);
            });
    private final ActivityResultLauncher<String[]> docLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) importDocument(uri);
            });
    private final ActivityResultLauncher<String[]> embedderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty()) importEmbedderFiles(uris);
            });

    // ---- voice
    private VoiceController voice;
    private Button micBtn;
    private Spinner voiceLangSpinner;
    private SwitchCompat offlineOnlySwitch, autoSendSwitch;
    private final ActivityResultLauncher<String> micPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (voice != null) voice.onPermissionResult(granted, input.getText().toString());
            });



    // ================================================================ settings values

    private String systemPrompt() {
        String s = prefs.getString("system_prompt", DEFAULT_SYSTEM_PROMPT);
        return s.trim().isEmpty() ? DEFAULT_SYSTEM_PROMPT : s;
    }

    private int maxTokens() { return prefs.getInt("max_tokens", 512); }

    private float temperature() { return prefs.getInt("temp_pct", 70) / 100f; }

    private String userName() {
        String n = prefs.getString("user_name", "").trim();
        return n.isEmpty() ? "You" : n;
    }

    // ================================================================ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE);
        store = new MemoryStore(this);
        chatStore = new ConversationStore(this);

        drawerLayout = findViewById(R.id.drawerLayout);
        contentRoot = findViewById(R.id.contentRoot);
        drawerPanel = findViewById(R.id.drawerPanel);

        ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (v, insets) -> {
            Insets b = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return insets;
        });
        ViewCompat.setOnApplyWindowInsetsListener(drawerPanel, (v, insets) -> {
            Insets b = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return insets;
        });

        pickerLayout = findViewById(R.id.pickerLayout);
        chatLayout = findViewById(R.id.chatLayout);
        memoryLayout = findViewById(R.id.memoryLayout);
        settingsLayout = findViewById(R.id.settingsLayout);
        loadingLayout = findViewById(R.id.loadingLayout);
        searchBox = findViewById(R.id.searchBox);
        input = findViewById(R.id.input);
        settingsNameEdit = findViewById(R.id.settingsNameEdit);
        systemPromptEdit = findViewById(R.id.systemPromptEdit);
        modelList = findViewById(R.id.modelList);
        memoryListView = findViewById(R.id.memoryListView);
        convList = findViewById(R.id.convList);
        templateSpinner = findViewById(R.id.templateSpinner);
        emptyText = findViewById(R.id.emptyText);
        loadingText = findViewById(R.id.loadingText);
        chatTitle = findViewById(R.id.chatTitle);
        modelTitle = findViewById(R.id.modelTitle);
        statusText = findViewById(R.id.statusText);
        memoryStatus = findViewById(R.id.memoryStatus);
        convEmpty = findViewById(R.id.convEmpty);
        avatarText = findViewById(R.id.avatarText);
        drawerUserName = findViewById(R.id.drawerUserName);
        maxTokensLabel = findViewById(R.id.maxTokensLabel);
        tempLabel = findViewById(R.id.tempLabel);
        aboutText = findViewById(R.id.aboutText);
        userRow = findViewById(R.id.userRow);
        importBtn = findViewById(R.id.importBtn);
        sendBtn = findViewById(R.id.sendBtn);
        menuBtn = findViewById(R.id.menuBtn);
        newChatTopBtn = findViewById(R.id.newChatTopBtn);
        memoryBackBtn = findViewById(R.id.memoryBackBtn);
        addNoteBtn = findViewById(R.id.addNoteBtn);
        importDocBtn = findViewById(R.id.importDocBtn);
        clearAllBtn = findViewById(R.id.clearAllBtn);
        embedderBtn = findViewById(R.id.embedderBtn);
        drawerNewChatBtn = findViewById(R.id.drawerNewChatBtn);
        drawerSettingsBtn = findViewById(R.id.drawerSettingsBtn);
        settingsBackBtn = findViewById(R.id.settingsBackBtn);
        resetPromptBtn = findViewById(R.id.resetPromptBtn);
        settingsModelBtn = findViewById(R.id.settingsModelBtn);
        settingsMemoryBtn = findViewById(R.id.settingsMemoryBtn);
        deleteAllChatsBtn = findViewById(R.id.deleteAllChatsBtn);
        useMemorySwitch = findViewById(R.id.useMemorySwitch);
        learnFactsSwitch = findViewById(R.id.learnFactsSwitch);
        maxTokensSeek = findViewById(R.id.maxTokensSeek);
        tempSeek = findViewById(R.id.tempSeek);
        chatList = findViewById(R.id.chatList);

        // ---- chat format spinner
        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Auto-detect", "ChatML (Qwen, most models)", "Llama 3", "Gemma", "Phi-3"});
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        templateSpinner.setAdapter(sa);

        // ---- model picker
        modelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, shownModels);
        modelList.setAdapter(modelAdapter);
        modelList.setOnItemClickListener((p, v, pos, id) -> loadModel(shownModels.get(pos)));
        modelList.setOnItemLongClickListener((p, v, pos, id) -> {
            confirmDelete(shownModels.get(pos));
            return true;
        });
        searchBox.addTextChangedListener(watcher(s -> applyFilter()));
        importBtn.setOnClickListener(v -> importLauncher.launch(new String[]{"*/*"}));

        // ---- chat list
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        chatList.setLayoutManager(lm);
        chatList.setItemAnimator(null);
        chatAdapter = new ChatAdapter(messages, m -> voice.toggleSpeak(m));
        chatList.setAdapter(chatAdapter);

        sendBtn.setOnClickListener(v -> {
            if (generating) llama.stop();
            else sendMessage();
        });
        menuBtn.setOnClickListener(v -> {
            hideKeyboard();
            refreshConversationList();
            drawerLayout.openDrawer(GravityCompat.START);
        });
        newChatTopBtn.setOnClickListener(v -> startNewChat());
        modelTitle.setOnClickListener(v -> goToPicker());

        // ---- drawer
        convAdapter = new ArrayAdapter<ConversationStore.Conv>(this,
                R.layout.item_conversation, convs) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ConversationStore.Conv c = getItem(position);
                boolean current = c != null && c.id == currentConvId;
                v.setBackgroundColor(current ? 0x33888888 : 0x00000000);
                return v;
            }
        };
        convList.setAdapter(convAdapter);
        convList.setOnItemClickListener((p, v, pos, id) -> openConversation(convs.get(pos)));
        convList.setOnItemLongClickListener((p, v, pos, id) -> {
            conversationMenu(convs.get(pos));
            return true;
        });
        drawerNewChatBtn.setOnClickListener(v -> startNewChat());
        userRow.setOnClickListener(v -> editNameDialog());
        drawerSettingsBtn.setOnClickListener(v -> showSettings());

        // ---- settings screen
        settingsNameEdit.setText(prefs.getString("user_name", ""));
        settingsNameEdit.addTextChangedListener(watcher(s -> {
            prefs.edit().putString("user_name", s.trim()).apply();
            updateUserUi();
        }));
        systemPromptEdit.setText(systemPrompt());
        systemPromptEdit.addTextChangedListener(watcher(s ->
                prefs.edit().putString("system_prompt", s).apply()));
        resetPromptBtn.setOnClickListener(v -> systemPromptEdit.setText(DEFAULT_SYSTEM_PROMPT));

        maxTokensSeek.setProgress((maxTokens() - 64) / 64);
        updateMaxTokensLabel();
        maxTokensSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                prefs.edit().putInt("max_tokens", 64 + p * 64).apply();
                updateMaxTokensLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        tempSeek.setProgress(prefs.getInt("temp_pct", 70));
        updateTempLabel();
        tempSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                prefs.edit().putInt("temp_pct", p).apply();
                updateTempLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        settingsBackBtn.setOnClickListener(v -> showChat());
        settingsModelBtn.setOnClickListener(v -> goToPicker());
        settingsMemoryBtn.setOnClickListener(v -> showMemory());
        deleteAllChatsBtn.setOnClickListener(v -> confirmDeleteAllChats());

        // ---- memory screen
        memoryAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, memoryLabels);
        memoryListView.setAdapter(memoryAdapter);
        memoryListView.setOnItemClickListener((p, v, pos, id) -> {
            MemoryStore.UiItem it = memoryItems.get(pos);
            new AlertDialog.Builder(this).setMessage(it.full)
                    .setPositiveButton("OK", null).show();
        });
        memoryListView.setOnItemLongClickListener((p, v, pos, id) -> {
            confirmDeleteMemory(memoryItems.get(pos));
            return true;
        });
        useMemorySwitch.setChecked(prefs.getBoolean("use_memory", true));
        useMemorySwitch.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean("use_memory", on).apply());
        learnFactsSwitch.setChecked(prefs.getBoolean("learn_facts", false));
        learnFactsSwitch.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean("learn_facts", on).apply());
        memoryBackBtn.setOnClickListener(v -> showSettings());
        addNoteBtn.setOnClickListener(v -> addNoteDialog());
        importDocBtn.setOnClickListener(v -> docLauncher.launch(new String[]{"*/*"}));
        clearAllBtn.setOnClickListener(v -> confirmClearAll());
        embedderBtn.setOnClickListener(v -> embedderLauncher.launch(new String[]{"*/*"}));

        // ---- voice
        micBtn = findViewById(R.id.micBtn);
        voiceLangSpinner = findViewById(R.id.voiceLangSpinner);
        offlineOnlySwitch = findViewById(R.id.offlineOnlySwitch);
        autoSendSwitch = findViewById(R.id.autoSendSwitch);

        voice = new VoiceController(this, prefs, new VoiceController.Callbacks() {
            @Override public void onListeningChanged(boolean listening) {
                micBtn.setText(listening ? "⏹" : "🎤");
                input.setHint(listening ? "Listening…" : "Message");
                if (listening) statusText.setText("Listening…");
            }
            @Override public void onPartialText(String text) {
                setInputText(text);
            }
            @Override public void onFinalText(String text) {
                setInputText(text);
                statusText.setText("Voice captured. Edit it or tap Send.");
                if (autoSendSwitch.isChecked()) sendMessage();
            }
            @Override public void onMessage(String message) {
                statusText.setText(message);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
            @Override public void onSpeakingChanged(Message speaking) {
                chatAdapter.setSpeaking(speaking);
            }
            @Override public void requestMicPermission() {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            }
        });
        micBtn.setOnClickListener(v -> voice.toggleMic(input.getText().toString()));

        ArrayAdapter<String> la = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, VoiceController.LANGUAGE_LABELS);
        la.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voiceLangSpinner.setAdapter(la);
        voiceLangSpinner.setSelection(prefs.getInt("voice_lang", 0));
        voiceLangSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt("voice_lang", pos).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        offlineOnlySwitch.setChecked(prefs.getBoolean("voice_offline_only", false));
        offlineOnlySwitch.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean("voice_offline_only", on).apply());
        autoSendSwitch.setChecked(prefs.getBoolean("voice_auto_send", false));
        autoSendSwitch.setOnCheckedChangeListener((b, on) ->
                prefs.edit().putBoolean("voice_auto_send", on).apply());

        // ---- back button
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else if (memoryLayout.getVisibility() == View.VISIBLE) {
                    showSettings();
                } else if (settingsLayout.getVisibility() == View.VISIBLE) {
                    showChat();
                } else if (pickerLayout.getVisibility() == View.VISIBLE && llama.isLoaded()) {
                    showChat();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        updateUserUi();
        refreshConversationList();
        showPicker();
        refreshModels();
        executor.execute(this::loadEmbedderBlocking);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        llama.stop();
        executor.execute(() -> {
            llama.close();
            voice.shutdown();
            Embedder e = embedder;
            if (e != null) e.close();
            store.close();
        });
        executor.shutdown();
        chatStore.close();
    }

    private static TextWatcher watcher(Consumer<String> onChange) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { onChange.accept(s.toString()); }
        };
    }

    private void hideKeyboard() {
        View v = getCurrentFocus();
        if (v != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    // ================================================================ conversations (drawer)

    private void refreshConversationList() {
        convs.clear();
        convs.addAll(chatStore.listConversations());
        convAdapter.notifyDataSetChanged();
        convEmpty.setVisibility(convs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void startNewChat() {
        voice.stopSpeaking();
        if (generating) llama.stop();
        currentConvId = -1;
        messages.clear();
        chatAdapter.notifyDataSetChanged();
        chatTitle.setText("New chat");
        statusText.setText("New chat · format: " + template.label);
        refreshConversationList();
        drawerLayout.closeDrawer(GravityCompat.START);
        showChat();
    }

    private void openConversation(ConversationStore.Conv c) {
        voice.stopSpeaking();
        if (generating) llama.stop();
        currentConvId = c.id;
        messages.clear();
        messages.addAll(chatStore.loadMessages(c.id));
        chatAdapter.notifyDataSetChanged();
        if (!messages.isEmpty()) chatList.scrollToPosition(messages.size() - 1);
        chatTitle.setText(c.title);
        statusText.setText("Ready · format: " + template.label);
        convAdapter.notifyDataSetChanged();           // updates the highlight
        drawerLayout.closeDrawer(GravityCompat.START);
        showChat();
    }

    /** Creates the conversation in the database when the first message is sent. */
    private void ensureConversation(String firstMessage) {
        if (currentConvId >= 0) return;
        String title = makeTitle(firstMessage);
        currentConvId = chatStore.createConversation(title);
        chatTitle.setText(title);
        refreshConversationList();
    }

    private static String makeTitle(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= 40 ? t : t.substring(0, 40).trim() + "…";
    }

    private void conversationMenu(ConversationStore.Conv c) {
        new AlertDialog.Builder(this)
                .setTitle(c.title)
                .setItems(new CharSequence[]{"Rename", "Delete"}, (d, which) -> {
                    if (which == 0) renameDialog(c);
                    else confirmDeleteConversation(c);
                })
                .show();
    }

    private void renameDialog(ConversationStore.Conv c) {
        EditText et = new EditText(this);
        et.setText(c.title);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("Rename conversation")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (t.isEmpty()) return;
                    chatStore.rename(c.id, t);
                    if (c.id == currentConvId) chatTitle.setText(t);
                    refreshConversationList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteConversation(ConversationStore.Conv c) {
        new AlertDialog.Builder(this)
                .setTitle("Delete conversation?")
                .setMessage(c.title)
                .setPositiveButton("Delete", (d, w) -> {
                    chatStore.delete(c.id);
                    if (c.id == currentConvId) resetCurrentChat();
                    refreshConversationList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteAllChats() {
        new AlertDialog.Builder(this)
                .setTitle("Delete all conversations?")
                .setMessage("This cannot be undone. Your memory (facts and documents) is not affected.")
                .setPositiveButton("Delete all", (d, w) -> {
                    if (generating) llama.stop();
                    chatStore.deleteAll();
                    resetCurrentChat();
                    refreshConversationList();
                    Toast.makeText(this, "All conversations deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void resetCurrentChat() {
        voice.stopSpeaking();
        currentConvId = -1;
        messages.clear();
        chatAdapter.notifyDataSetChanged();
        chatTitle.setText("New chat");
    }

    // ================================================================ user profile + settings

    private void updateUserUi() {
        String n = userName();
        drawerUserName.setText(n);
        avatarText.setText(new String(Character.toChars(Character.toUpperCase(n.codePointAt(0)))));
    }

    private void editNameDialog() {
        EditText et = new EditText(this);
        et.setText(prefs.getString("user_name", ""));
        et.setHint("Your name");
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("Your name")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    settingsNameEdit.setText(et.getText().toString().trim());   // saves + updates UI
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateMaxTokensLabel() {
        maxTokensLabel.setText("Max reply length: " + maxTokens() + " tokens");
    }

    private void updateTempLabel() {
        tempLabel.setText(String.format(Locale.US,
                "Creativity (temperature): %.2f", temperature()));
    }

    private void showSettings() {
        showOnly(settingsLayout);
        aboutText.setText("Model: " + (loadedModelName == null ? "none loaded" : loadedModelName)
                + "\nEverything runs on this phone. Chats and memory never leave the device.");
    }

    // ================================================================ model list

    private void refreshModels() {
        allModels.clear();
        Set<String> seen = new HashSet<>();
        File[] dirs = {getExternalFilesDir("models"), getExternalFilesDir(null)};
        for (File d : dirs) {
            if (d == null) continue;
            File[] files = d.listFiles(
                    (dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".gguf"));
            if (files == null) continue;
            for (File f : files) {
                if (seen.add(f.getAbsolutePath())) allModels.add(new ModelItem(f));
            }
        }
        Collections.sort(allModels,
                (a, b) -> a.file.getName().compareToIgnoreCase(b.file.getName()));
        applyFilter();
    }

    private void applyFilter() {
        String q = searchBox.getText().toString().trim().toLowerCase(Locale.ROOT);
        shownModels.clear();
        for (ModelItem m : allModels) {
            if (q.isEmpty() || m.file.getName().toLowerCase(Locale.ROOT).contains(q)) {
                shownModels.add(m);
            }
        }
        modelAdapter.notifyDataSetChanged();

        if (shownModels.isEmpty()) {
            File dir = getExternalFilesDir("models");
            emptyText.setText(allModels.isEmpty()
                    ? "No models yet. Tap the button below to import a .gguf file, "
                    + "or copy one to:\n" + (dir == null ? "" : dir.getAbsolutePath())
                    : "No models match your search.");
            emptyText.setVisibility(View.VISIBLE);
        } else {
            emptyText.setVisibility(View.GONE);
        }
    }

    private void confirmDelete(ModelItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Delete model?")
                .setMessage(item.file.getName())
                .setPositiveButton("Delete", (d, w) -> {
                    item.file.delete();
                    refreshModels();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ================================================================ import model

    private void importModel(Uri uri) {
        String name = queryName(uri);
        long size = querySize(uri);
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            Toast.makeText(this, "Please choose a .gguf model file", Toast.LENGTH_LONG).show();
            return;
        }
        File outDir = getExternalFilesDir("models");
        if (outDir == null) return;
        File out = new File(outDir, name);
        File tmp = new File(outDir, name + ".part");

        showLoading("Copying " + name + "…");
        executor.execute(() -> {
            String err = copyToFile(uri, tmp, name, size);
            if (err == null && !tmp.renameTo(out)) err = "Could not save the file";
            if (err != null) tmp.delete();
            final String result = err;
            runOnUiThread(() -> {
                hideLoading();
                refreshModels();
                Toast.makeText(this,
                        result == null ? "Model imported" : "Import failed: " + result,
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private String copyToFile(Uri uri, File dest, String name, long total) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) return "Could not open the file";
            byte[] buf = new byte[1 << 20];
            long done = 0, lastUi = 0;
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
                done += r;
                long now = System.currentTimeMillis();
                if (now - lastUi > 500) {
                    lastUi = now;
                    final String msg = total > 0
                            ? String.format(Locale.US, "Copying %s… %d%%", name, done * 100 / total)
                            : "Copying " + name + "…";
                    runOnUiThread(() -> loadingText.setText(msg));
                }
            }
            return null;
        } catch (IOException e) {
            return e.getMessage() == null ? "Copy failed" : e.getMessage();
        }
    }

    private String queryName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        }
        return null;
    }

    private long querySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        }
        return -1;
    }

    // ================================================================ load model

    private ChatTemplate pickTemplate(String fileName) {
        switch (templateSpinner.getSelectedItemPosition()) {
            case 1: return ChatTemplate.CHATML;
            case 2: return ChatTemplate.LLAMA3;
            case 3: return ChatTemplate.GEMMA;
            case 4: return ChatTemplate.PHI3;
            default: return ChatTemplate.detect(fileName);
        }
    }

    private void loadModel(ModelItem item) {
        template = pickTemplate(item.file.getName());
        showLoading("Loading " + item.file.getName() + "…");
        executor.execute(() -> {
            llama.close();
            long t0 = System.currentTimeMillis();
            boolean ok = llama.load(item.file.getAbsolutePath(), N_CTX, N_THREADS);
            long secs = (System.currentTimeMillis() - t0) / 1000;
            runOnUiThread(() -> {
                hideLoading();
                if (ok) {
                    loadedModelName = item.file.getName();
                    modelTitle.setText(loadedModelName);
                    statusText.setText("Ready · loaded in " + secs + " s · format: " + template.label);
                    showChat();
                } else {
                    loadedModelName = null;
                    Toast.makeText(this,
                            "Failed to load model. Check Logcat (tag: LlamaJni).",
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // ================================================================ chatting

    private void sendMessage() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || generating || !llama.isLoaded()) return;
        voice.stopSpeaking();
        input.setText("");

        ensureConversation(text);
        final long convId = currentConvId;

        Message userMsg = new Message(Message.USER, text);
        messages.add(userMsg);
        chatAdapter.notifyItemInserted(messages.size() - 1);
        chatStore.addMessage(convId, userMsg);

        // "remember that ..." is handled directly, no LLM needed
        Matcher m = REMEMBER.matcher(text);
        if (m.matches()) {
            String fact = m.group(1).trim();
            Message ack = new Message(Message.AI, "Saving…");
            messages.add(ack);
            chatAdapter.notifyItemInserted(messages.size() - 1);
            chatList.scrollToPosition(messages.size() - 1);
            saveFactAsync(fact, r -> {
                ack.text = r;
                chatAdapter.notifyDataSetChanged();
                chatStore.addMessage(convId, ack);
                refreshConversationList();
            });
            return;
        }

        Message reply = new Message(Message.AI, "");
        reply.streaming = true;
        messages.add(reply);
        chatAdapter.notifyItemInserted(messages.size() - 1);
        chatList.scrollToPosition(messages.size() - 1);

        final List<Message> window = historyWindow();
        final boolean useMemory = useMemorySwitch.isChecked();
        final boolean learn = learnFactsSwitch.isChecked();
        final String sysPrompt = systemPrompt();
        final int maxNew = maxTokens();
        final float temp = temperature();
        setGenerating(true);
        statusText.setText("Thinking…");

        executor.execute(() -> {
            long start = System.currentTimeMillis();

            // 1) retrieve relevant memories
            Embedder emb = embedder;
            float[] qVec = null;
            String memBlock = "";
            if (emb != null) {
                try {
                    qVec = emb.embed(text);
                    if (useMemory) memBlock = buildMemoryBlock(text, qVec, window);
                } catch (Exception ex) {
                    Log.e(TAG, "retrieval failed", ex);
                }
            }

            // 2) generate the answer
            String prompt = template.build(sysPrompt + memBlock, window);
            boolean[] first = {true};
            int rc = llama.generate(prompt, maxNew, temp, bytes -> {
                String piece = new String(bytes, StandardCharsets.UTF_8);
                boolean isFirst = first[0];
                first[0] = false;
                runOnUiThread(() -> {
                    if (isFirst) statusText.setText("Generating…");
                    reply.text += piece;
                    notifyMessage(reply);
                });
            });
            long ms = System.currentTimeMillis() - start;
            runOnUiThread(() -> finishGeneration(reply, convId, rc, ms));

            // 3) save to memory (the reply is already on screen)
            if (rc >= 0 && emb != null && qVec != null) rememberTurn(text, qVec, learn);
        });
    }

    /** Recent messages (without the empty reply placeholder), trimmed to the char budget. */
    private List<Message> historyWindow() {
        int end = messages.size() - 1;
        List<Message> window = new ArrayList<>();
        int chars = 0;
        for (int i = end - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.error) continue;
            if (!window.isEmpty() && chars + m.text.length() > HISTORY_CHAR_BUDGET) break;
            chars += m.text.length();
            window.add(0, m);
        }
        while (window.size() > 1 && window.get(0).role != Message.USER) window.remove(0);
        return window;
    }

    private String buildMemoryBlock(String query, float[] qVec, List<Message> window) {
        Set<String> inHistory = new HashSet<>();
        for (Message m : window) inHistory.add(m.text);

        StringBuilder sb = new StringBuilder();
        int used = 0, count = 0;
        for (MemoryStore.Hit h : store.search(query, qVec, TOP_K, MIN_SIM)) {
            String c = h.row.content;
            if (inHistory.contains(c)) continue;
            String line = "- "
                    + (MemoryStore.DOCUMENT.equals(h.row.type) ? "(" + h.row.source + ") " : "")
                    + c + "\n";
            if (count > 0 && used + line.length() > MEMORY_CHAR_BUDGET) break;
            sb.append(line);
            used += line.length();
            count++;
        }
        if (count == 0) return "";
        return "\n\nRelevant information from your memory and documents:\n" + sb
                + "Use it when it helps answer the user.";
    }

    private void rememberTurn(String userText, float[] vec, boolean learnFacts) {
        try {
            boolean statement = userText.length() >= 25 && !userText.endsWith("?");
            if (!statement) return;
            if (store.maxSimilarity(vec) < 0.95f) {
                store.add(MemoryStore.EPISODIC, "chat", userText, vec, 0.3f);
            }
            if (learnFacts) extractFacts(userText);
        } catch (Exception e) {
            Log.e(TAG, "rememberTurn failed", e);
        }
    }

    private void extractFacts(String userText) throws Exception {
        Embedder e = embedder;
        if (e == null) return;
        runOnUiThread(() -> statusText.setText("Learning from this message…"));

        List<Message> one = new ArrayList<>();
        one.add(new Message(Message.USER, userText));
        String prompt = template.build(EXTRACT_PROMPT, one);

        StringBuilder out = new StringBuilder();
        int rc = llama.generate(prompt, 80, 0.2f,
                bytes -> out.append(new String(bytes, StandardCharsets.UTF_8)));

        int saved = 0;
        if (rc >= 0) {
            for (String raw : out.toString().split("\n")) {
                String line = raw.trim().replaceFirst("^[-*•\\d.)\\s]+", "").trim();
                if (line.length() < 12 || line.length() > 200) continue;
                if (!line.toLowerCase(Locale.ROOT).startsWith("the user")) continue;
                float[] v = e.embed(line);
                if (store.maxSimilarity(v) > 0.88f) continue;
                store.add(MemoryStore.FACT, "learned", line, v, 0.7f);
                if (++saved >= 3) break;
            }
        }
        final int n = saved;
        runOnUiThread(() -> {
            if (!generating) {
                statusText.setText(n > 0
                        ? "Learned " + n + " new fact" + (n > 1 ? "s" : "") : "Ready");
            }
        });
    }

    private void saveFactAsync(String fact, Consumer<String> onResult) {
        executor.execute(() -> {
            String result;
            Embedder e = embedder;
            if (e == null) {
                result = "Memory isn't set up yet. Open Settings → Open memory and set up the embedder files.";
            } else {
                try {
                    float[] v = e.embed(fact);
                    store.add(MemoryStore.FACT, "user", fact, v, 0.9f);
                    result = "Okay, I'll remember that: " + fact;
                } catch (Exception ex) {
                    Log.e(TAG, "saveFact failed", ex);
                    result = "Could not save that memory.";
                }
            }
            final String r = result;
            runOnUiThread(() -> onResult.accept(r));
        });
    }

    private void finishGeneration(Message reply, long convId, int rc, long ms) {
        reply.streaming = false;
        setGenerating(false);
        if (rc < 0) {
            if (reply.text.trim().isEmpty()) {
                reply.text = errorText(rc);
                reply.error = true;
            }
            statusText.setText("Stopped: " + errorText(rc));
        } else {
            reply.text = reply.text.trim();
            if (reply.text.isEmpty()) reply.text = "(no response)";
            statusText.setText(String.format(Locale.US, "%d tokens in %.1f s", rc, ms / 1000.0));
        }
        chatStore.addMessage(convId, reply);       // saved to the conversation it belongs to
        refreshConversationList();
        notifyMessage(reply);
    }

    private static String errorText(int rc) {
        switch (rc) {
            case -1: return "The model is not loaded.";
            case -2: return "The conversation is too long for the model. Tap + to start a new chat.";
            case -3: return "Could not read the prompt.";
            default: return "Generation failed.";
        }
    }

    /** Refreshes one message bubble (does nothing if that chat is no longer on screen). */
    private void notifyMessage(Message m) {
        int idx = messages.lastIndexOf(m);
        if (idx < 0) return;
        chatAdapter.notifyItemChanged(idx);
        chatList.scrollToPosition(idx);
    }

    private void setGenerating(boolean g) {
        generating = g;
        sendBtn.setText(g ? "Stop" : "Send");
        if (g) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ================================================================ embedder

    private void loadEmbedderBlocking() {
        File dir = getExternalFilesDir("embedder");
        File onnx = new File(dir, "model.onnx");
        File vocab = new File(dir, "vocab.txt");

        Embedder old = embedder;
        embedder = null;
        if (old != null) old.close();

        if (!onnx.exists() || !vocab.exists()) {
            embedderError = "Embedder files not found";
        } else {
            try {
                embedder = new Embedder(onnx, vocab);
                embedderError = null;
            } catch (Throwable t) {
                Log.e(TAG, "embedder load failed", t);
                embedderError = "Embedder failed to load: " + t.getMessage();
            }
        }
        runOnUiThread(this::updateMemoryStatus);
    }

    private void importEmbedderFiles(List<Uri> uris) {
        File dir = getExternalFilesDir("embedder");
        if (dir == null) return;
        showLoading("Copying embedder files…");
        executor.execute(() -> {
            int copied = copyEmbedderFiles(uris, dir);
            runOnUiThread(() -> {
                hideLoading();
                Toast.makeText(this, copied >= 2
                                ? "Files copied. Loading embedder…"
                                : "Select both the .onnx file and vocab.txt",
                        Toast.LENGTH_LONG).show();
            });
            if (copied >= 2) loadEmbedderBlocking();
        });
    }

    private int copyEmbedderFiles(List<Uri> uris, File dir) {
        int copied = 0;
        for (Uri u : uris) {
            String n = queryName(u);
            if (n == null) continue;
            String lower = n.toLowerCase(Locale.ROOT);
            String target = lower.endsWith(".onnx") ? "model.onnx"
                    : lower.equals("vocab.txt") ? "vocab.txt" : null;
            if (target == null) continue;
            File tmp = new File(dir, target + ".part");
            String err = copyToFile(u, tmp, n, querySize(u));
            if (err == null && tmp.renameTo(new File(dir, target))) copied++;
            else tmp.delete();
        }
        return copied;
    }

    // ================================================================ memory screen

    private void showMemory() {
        showOnly(memoryLayout);
        if (embedder == null) executor.execute(this::loadEmbedderBlocking);
        refreshMemoryList();
    }

    private void refreshMemoryList() {
        executor.execute(() -> {
            List<MemoryStore.UiItem> items = store.listForUi();
            int count = store.count();
            runOnUiThread(() -> {
                memoryItems.clear();
                memoryItems.addAll(items);
                memoryLabels.clear();
                for (MemoryStore.UiItem it : items) memoryLabels.add(it.label);
                memoryAdapter.notifyDataSetChanged();
                memoryCount = count;
                updateMemoryStatus();
            });
        });
    }

    private void updateMemoryStatus() {
        if (embedder != null) {
            memoryStatus.setText("Embedder ready · " + memoryCount + " memories");
        } else {
            String why = embedderError != null ? embedderError : "Embedder not loaded";
            memoryStatus.setText(why + ". Tap \"Set up embedder files\" and pick "
                    + "the .onnx model and vocab.txt.");
        }
    }

    private void addNoteDialog() {
        EditText et = new EditText(this);
        et.setHint("e.g. My exam is on 5 October");
        new AlertDialog.Builder(this)
                .setTitle("Add a note to memory")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (t.isEmpty()) return;
                    saveFactAsync(t, r -> {
                        Toast.makeText(this, r, Toast.LENGTH_SHORT).show();
                        refreshMemoryList();
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteMemory(MemoryStore.UiItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Delete from memory?")
                .setMessage(item.label)
                .setPositiveButton("Delete", (d, w) -> executor.execute(() -> {
                    if (item.id >= 0) store.deleteById(item.id);
                    else store.deleteSource(item.source);
                    runOnUiThread(this::refreshMemoryList);
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
                .setTitle("Clear all memory?")
                .setMessage("This deletes every fact, chat memory and imported document.")
                .setPositiveButton("Clear", (d, w) -> executor.execute(() -> {
                    store.clearAll();
                    runOnUiThread(this::refreshMemoryList);
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ================================================================ documents (RAG)

    private void importDocument(Uri uri) {
        if (embedder == null) {
            Toast.makeText(this, "Set up the embedder files first", Toast.LENGTH_LONG).show();
            return;
        }
        String name = queryName(uri);
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".markdown") || lower.endsWith(".csv"))) {
            Toast.makeText(this, "For now only .txt, .md and .csv files are supported",
                    Toast.LENGTH_LONG).show();
            return;
        }
        showLoading("Reading " + name + "…");
        executor.execute(() -> {
            String msg = indexDocument(uri, name);
            runOnUiThread(() -> {
                hideLoading();
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                refreshMemoryList();
            });
        });
    }

    private String indexDocument(Uri uri, String name) {
        Embedder e = embedder;
        if (e == null) return "Embedder not ready";
        try {
            String text = readText(uri, 3_000_000);
            List<String> chunks = TextChunker.chunk(text, 700, 100);
            if (chunks.isEmpty()) return "The file is empty";

            List<MemoryStore.NewItem> items = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                final String progress = "Indexing " + name + "… " + (i + 1) + "/" + chunks.size();
                runOnUiThread(() -> loadingText.setText(progress));
                String chunk = chunks.get(i);
                items.add(new MemoryStore.NewItem(
                        MemoryStore.DOCUMENT, name, chunk, e.embed(chunk), 0.5f));
            }
            store.deleteSource(name);
            int n = store.addAll(items);
            return "Indexed " + n + " chunks from " + name;
        } catch (Exception ex) {
            Log.e(TAG, "indexDocument failed", ex);
            return "Indexing failed: " + ex.getMessage();
        }
    }

    private String readText(Uri uri, int maxBytes) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open file");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) != -1 && bos.size() < maxBytes) bos.write(buf, 0, r);
            return bos.toString("UTF-8");
        }
    }

    // ================================================================ screens

    private void showOnly(View target) {
        pickerLayout.setVisibility(target == pickerLayout ? View.VISIBLE : View.GONE);
        chatLayout.setVisibility(target == chatLayout ? View.VISIBLE : View.GONE);
        memoryLayout.setVisibility(target == memoryLayout ? View.VISIBLE : View.GONE);
        settingsLayout.setVisibility(target == settingsLayout ? View.VISIBLE : View.GONE);
        if (target != chatLayout) drawerLayout.closeDrawer(GravityCompat.START, false);
        drawerLayout.setDrawerLockMode(target == chatLayout
                ? DrawerLayout.LOCK_MODE_UNLOCKED : DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
    }

    private void showPicker() { showOnly(pickerLayout); }

    private void showChat() { showOnly(chatLayout); }

    private void goToPicker() {
        if (generating) llama.stop();
        showPicker();
        refreshModels();
    }

    private void showLoading(String text) {
        loadingText.setText(text);
        loadingLayout.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        loadingLayout.setVisibility(View.GONE);
    }

    // ================================================================ helpers

    private static String humanSize(long b) {
        if (b >= (1L << 30)) return String.format(Locale.US, "%.1f GB", b / (double) (1L << 30));
        return String.format(Locale.US, "%d MB", b >> 20);
    }

    private static class ModelItem {
        final File file;

        ModelItem(File f) { file = f; }

        @Override
        public String toString() {
            return file.getName() + "\n" + humanSize(file.length());
        }
    }
    @Override
    protected void onStop() {
        super.onStop();
        if (voice != null) {
            voice.cancelListening();
            voice.stopSpeaking();
        }
    }

    private void setInputText(String t) {
        input.setText(t);
        input.setSelection(input.getText().length());
    }
}