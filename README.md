<div align="center">

# 🤖 AI Chat Client
### A fully offline, on-device AI agent for Android

**Run powerful LLMs locally on your Android phone — no internet, no cloud, no subscriptions.**

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-24%20(Android%207.0)-blue)](https://developer.android.com)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-36-blue)](https://developer.android.com)
[![AGP](https://img.shields.io/badge/AGP-9.0.1-orange)](https://developer.android.com/build)
[![Language](https://img.shields.io/badge/Language-Java-ED8B00?logo=java&logoColor=white)](https://www.java.com)
[![llama.cpp](https://img.shields.io/badge/Backend-llama.cpp-blueviolet)](https://github.com/ggml-org/llama.cpp)
[![ONNX Runtime](https://img.shields.io/badge/Embeddings-ONNX%20Runtime-lightgrey)](https://onnxruntime.ai)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

</div>

---

## ✨ What is this?

**AI Chat Client** is a native Android application that lets you download any GGUF-format language model and run it entirely on your phone — no Wi-Fi needed, no data leaves your device. It goes far beyond a simple chatbot: it has long-term memory, PDF understanding, voice I/O, vision (image input), multi-turn conversation management, and a smart context engine — all running offline on the CPU.

---

## 📸 Feature Highlights

| Category | Feature |
|---|---|
| 🧠 **Inference** | On-device LLM via llama.cpp (GGUF format) |
| 👁️ **Vision** | Image input via multimodal projector (mmproj) |
| 🗣️ **Voice** | Speech-to-text input + Text-to-speech output |
| 📄 **Documents** | PDF chat via RAG (text PDFs + scanned/OCR) |
| 💾 **Memory** | Persistent long-term memory with semantic search |
| 💬 **Conversations** | Multiple saved chat sessions with history |
| 🔄 **Summarization** | Auto-rolling conversation summaries |
| ⚙️ **Templates** | ChatML, Llama 3, Gemma, Phi-3, SmolVLM |
| 🔧 **Customizable** | System prompt, temperature, max tokens |

---

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                        MainActivity.java                     │
│            (UI orchestration, 1900+ lines)                   │
├───────────┬────────────┬──────────┬──────────┬──────────────┤
│LlamaBridge│VisionBridge│ Embedder │  Voice   │PdfTextExtract│
│  (JNI ↓) │  (JNI ↓)  │(ONNX RT) │Controller│  (PdfBox +  │
│           │            │MiniLM-L6 │ STT/TTS  │  ML Kit OCR)│
├───────────┴────────────┴──────────┴──────────┴──────────────┤
│          llama_jni.cpp  ←→  llama.cpp (C++ submodule)       │
│       (ARM64 optimized: armv8.2-a+dotprod)                  │
├─────────────────────────────────────────────────────────────┤
│        SQLite (MemoryStore + ConversationStore)              │
│                 Persistent on-device storage                 │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔧 Core Components

### 🦙 `LlamaBridge.java` — LLM Inference Engine
The heart of the app. A clean JNI wrapper over `llama.cpp` that exposes:
- `load()` — loads any GGUF model into a 4096-token context
- `generate()` — streams tokens through a callback in real-time
- `loadVision()` / `generateWithImage()` — optional multimodal projector for image input
- `stop()` — interrupt generation mid-stream
- Runs on **4 CPU threads**, **arm64-v8a** only (optimized for modern ARMv8.2 cores)

### 🧬 `Embedder.java` — Semantic Embedding
Runs a **MiniLM-L6-v2** (or compatible) sentence-embedding model via **ONNX Runtime** to convert text into 384-dimensional float vectors. Used for semantic search over memories and documents. Features:
- WordPiece tokenizer (pure Java, no Python needed)
- Mean-pooling + L2 normalization
- Max 256 tokens per input
- 2 intra-op threads

### 🧠 `MemoryStore.java` — Long-Term Memory
A SQLite-backed vector store with **hybrid search** (65% semantic, 15% keyword, 10% recency, 10% importance):
- **Facts** — things the model extracts from your messages (name, preferences, plans)
- **Episodic** — conversation memories (auto-pruned to the newest 400)
- **Documents** — indexed chunks from PDFs and text files
- In-memory cache for fast cosine similarity search

### 💬 `ContextBuilder.java` — Smart Prompt Assembly
Packs the most useful information into a fixed token budget before each inference:
| Budget Slot | Tokens |
|---|---|
| Total prompt | 1500 |
| Summary | 220 |
| Memories / RAG | 420 |
| Recent history | 250+ |

Logic: pulls the rolling summary → retrieves top-K memories → fills remaining budget with the newest messages.

### 📝 `ConversationStore.java` — Chat Persistence
SQLite-backed multi-session management. Stores full message history, rolling summary, and per-conversation PDF attachment. Auto-titles conversations from the first user message.

### 🗣️ `VoiceController.java` — Voice I/O
Full speech-to-text and text-to-speech, entirely using Android's built-in APIs:
- `SpeechRecognizer` — live partial results while you speak
- `TextToSpeech` — speaks the AI's replies aloud
- Language selection: Device default / English (India) / English (US) / Hindi
- Auto-send toggle and offline-only mode
- Animated "Speak now" dialog with waveform

### 👁️ `VisionBridge.java` + `ImageUtil.java` — Image Understanding
Load a **multimodal projector** (`.gguf` mmproj file) next to your model and start sending images:
- Images are resized to 512px max side before inference
- RGB byte array passed to native `nativeGenerateImage()` via JNI
- Automatic projector discovery: scans for `mmproj*.gguf` in the model directory

### 📄 `PdfTextExtractor.java` — PDF Reading
Dual-mode PDF parser supporting up to 400 pages:
| Mode | Technology | Trigger |
|---|---|---|
| Digital text | **PdfBox-Android** | ≥30 chars per page |
| Scanned / image | **ML Kit OCR** | <30 chars per page |

OCR pages are upscaled 2× before recognition for better accuracy.

### 🔄 `ChatTemplate.java` — Prompt Formatting
Auto-detects the correct chat template from the model filename:
| Template | Models |
|---|---|
| **ChatML** | Qwen, Qwen-VL, most others (default) |
| **Llama 3** | Meta Llama 3.x |
| **Gemma** | Google Gemma 2/3/3n/4 |
| **Phi-3** | Microsoft Phi-3/3.5 |
| **SmolVLM** | HuggingFace SmolVLM |

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug or newer
- Android device or emulator with **arm64-v8a** (API 24+)
- CMake 3.22.1 (included via Android Studio's SDK Manager)
- NDK (any recent version)

### Build & Install

```bash
# Clone with submodules (llama.cpp is a git submodule)
git clone --recurse-submodules https://github.com/YOUR_USERNAME/AiChatClient.git

# Or if already cloned:
git submodule update --init --recursive

# Build from Android Studio, or via CLI:
./gradlew app:assembleDebug
```

> [!IMPORTANT]
> The llama.cpp submodule at `app/src/main/cpp/llama.cpp` **must** be initialized before building. The CMake build will fail with a clear error if `mtmd.h` is not found.

### Download a Model

1. Download any **GGUF** quantized model from [Hugging Face](https://huggingface.co/models?search=gguf) (Q4_K_M is a good balance of size/quality)
2. Recommended starter models:
   - **Qwen2.5-1.5B-Instruct-Q4_K_M.gguf** (~1 GB, ChatML)
   - **Llama-3.2-1B-Instruct-Q4_K_M.gguf** (~0.8 GB, Llama 3)
   - **gemma-2-2b-it-Q4_K_M.gguf** (~1.5 GB, Gemma)
   - **Phi-3.5-mini-instruct-Q4_K_M.gguf** (~2.2 GB, Phi-3)
3. Tap **📁 Import Model** in the app and select the file

### (Optional) Enable Vision

1. Download the matching `mmproj-*.gguf` for a vision-capable model (e.g. Qwen2-VL, MiniCPM-V, SmolVLM)
2. Place it in the same folder as your model — the app auto-discovers it
3. The camera icon will appear in the input bar

### (Optional) Enable Semantic Memory

1. Download a MiniLM-L6-v2 ONNX model: `model.onnx` + `vocab.txt`
2. Tap **⚡ Load Embedder** in the Memory screen and select both files
3. Memory search and PDF indexing become active

---

## 📂 Project Structure

```
app/src/main/
├── java/com/example/aichatclient/
│   ├── MainActivity.java        # UI + all feature wiring (~1900 lines)
│   ├── LlamaBridge.java         # JNI wrapper for llama.cpp text inference
│   ├── VisionBridge.java        # JNI wrapper for multimodal image inference
│   ├── Embedder.java            # ONNX Runtime sentence embedder (MiniLM)
│   ├── WordPieceTokenizer.java  # Pure-Java tokenizer for the embedder
│   ├── MemoryStore.java         # SQLite vector store (facts, episodic, docs)
│   ├── ConversationStore.java   # SQLite chat session persistence
│   ├── ContextBuilder.java      # Token-budget-aware prompt assembler
│   ├── ChatTemplate.java        # ChatML / Llama3 / Gemma / Phi3 / SmolVLM
│   ├── ChatAdapter.java         # RecyclerView adapter for the message list
│   ├── PdfTextExtractor.java    # PdfBox text + ML Kit OCR for scanned PDFs
│   ├── ImageUtil.java           # Bitmap resize & RGB conversion for vision
│   ├── ImageUtils.java          # Additional image helpers
│   ├── VoiceController.java     # SpeechRecognizer + TextToSpeech
│   ├── VoiceListeningDialog.java# "Speak now" animated dialog
│   ├── TextChunker.java         # Splits documents into embeddable chunks
│   ├── TokenEstimator.java      # Fast approximate token counter
│   └── Message.java             # Chat message data class
│
├── cpp/
│   ├── CMakeLists.txt           # NDK build: llama.cpp + mtmd + llama_jni.so
│   ├── engine.h                 # C++ engine header
│   └── llama.cpp/               # llama.cpp git submodule (full library)
│
└── res/
    ├── layout/
    │   ├── activity_main.xml    # Full UI layout (~1100 lines)
    │   ├── item_message.xml     # Chat bubble
    │   ├── item_conversation.xml# Sidebar conversation row
    │   ├── bottom_sheet_attach.xml # Attach file bottom sheet
    │   └── dialog_voice.xml     # Voice listening dialog
    └── drawable/                # Custom backgrounds, icons, shapes
```

---

## 🧰 Dependencies

| Library | Purpose | Version |
|---|---|---|
| **llama.cpp** | On-device LLM inference (C++) | git submodule |
| **ONNX Runtime Android** | Sentence embedding inference | 1.22.0 |
| **PdfBox-Android** | Digital PDF text extraction | 2.0.27.0 |
| **ML Kit Text Recognition** | OCR for scanned PDFs | 16.0.1 |
| **Material Components** | UI components & theming | 1.14.0 |
| **AndroidX AppCompat** | Activity & lifecycle support | 1.8.0 |
| **ConstraintLayout** | Flexible XML layouts | 2.2.2 |

---

## ⚙️ Configuration & Settings

All settings are persisted via `SharedPreferences`:

| Setting | Default | Description |
|---|---|---|
| System Prompt | "You are a helpful assistant..." | Custom AI persona |
| Max Tokens | Configurable via SeekBar | Max tokens per reply |
| Temperature | Configurable via SeekBar | Creativity (0 = deterministic) |
| Use Memory | Toggle | Enable long-term memory retrieval |
| Learn Facts | Toggle | Auto-extract facts from your messages |
| Voice Language | Device default | STT recognition language |
| Offline STT only | Off | Force on-device speech recognition |
| Auto-send voice | Off | Send STT result without confirmation |

---

## 🧩 How Memory Works

```
User message → Embedder → 384-dim vector
                               ↓
                    MemoryStore.search()
                    (Hybrid scoring)
                    ├── 65%  cosine similarity
                    ├── 15%  keyword overlap
                    ├── 10%  recency decay (exp(-days/30))
                    └── 10%  importance weight
                               ↓
                    Top-K hits → ContextBuilder
                               ↓
                    Injected into prompt as "Things you know"
```

Memory types:
- **Fact**: "The user is studying computer science." (auto-extracted or manually added)
- **Episodic**: Previous Q&A turns (capped at 400 newest)
- **Document**: Chunked text from indexed PDFs/files

---

## 📄 PDF Chat (RAG)

Attach a PDF to any conversation via the **＋** button:

```
PDF file
   ↓ PdfTextExtractor
   ↓   ├── Digital pages → PdfBox (fast)
   ↓   └── Scanned pages → ML Kit OCR (~2× upscaled)
   ↓ TextChunker (paragraph-aware splitting)
   ↓ Embedder (each chunk → float[384])
   ↓ MemoryStore (stored as DOCUMENT type)
   
On query:
   User question → Embedder → search top-4 chunks
   Relevant excerpts → injected into prompt with page numbers
```

---

## 🎤 Voice Flow

```
Mic button tap
      ↓
VoiceController.startListening()
      ↓
SpeechRecognizer (Android API)
   ├── onPartialResults → live preview in input box
   └── onResults → final text
            ↓
     Auto-send OR wait for confirmation
            ↓
     LLM generates reply
            ↓
     TextToSpeech.speak() → reply read aloud
```

---

## 🔄 Rolling Summary

Conversations are automatically condensed to avoid context overflow:

```
Every 2000 chars of unsummarized chat:
  → Run summary prompt against the LLM
  → "Update the running summary with the new messages..."
  → Keep the newest 900 chars verbatim
  → Inject summary into every future prompt (capped at 220 tokens)
```

---

## 🏎️ Performance Notes

- Compiled for **arm64-v8a** only with `armv8.2-a+dotprod` optimizations
- Context window: **4096 tokens**
- Recommended RAM: 4 GB+ free for 1–2B models, 6 GB+ for 3–7B models
- NDK build type: **Release** (`CMAKE_BUILD_TYPE=Release`)
- STL: **c++_shared** (`ANDROID_STL=c++_shared`)
- Multimodal library (`mtmd`) built alongside llama.cpp

---

## 🛣️ Roadmap / Ideas

- [ ] Vulkan / GPU acceleration via llama.cpp's GPU backend
- [ ] Speculative decoding for faster generation
- [ ] Voice Activity Detection (VAD) for hands-free mode
- [ ] Export / import conversations as JSON
- [ ] Quantization choice at import time
- [ ] Multiple models loaded simultaneously (draft + main)
- [ ] Home screen widget for quick queries

---

## 🤝 Contributing

Pull requests are welcome! Please open an issue first to discuss what you'd like to change.

1. Fork the repo
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 📜 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

The bundled **llama.cpp** library is licensed under the [MIT License](https://github.com/ggml-org/llama.cpp/blob/master/LICENSE).

---

<div align="center">

Made with ❤️ for the on-device AI community

*All inference happens locally. Your conversations never leave your device.*

</div>
