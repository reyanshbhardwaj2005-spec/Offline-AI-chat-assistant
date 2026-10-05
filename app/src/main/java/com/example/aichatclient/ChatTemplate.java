package com.example.aichatclient;

import java.util.List;
import java.util.Locale;

public enum ChatTemplate {
    CHATML("ChatML"), LLAMA3("Llama 3"), GEMMA("Gemma"), PHI3("Phi-3");

    public final String label;

    ChatTemplate(String label) {
        this.label = label;
    }

    public static ChatTemplate detect(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.contains("gemma")) return GEMMA;
        if (n.contains("llama-3") || n.contains("llama3") || n.contains("llama_3")) return LLAMA3;
        if (n.contains("phi-3") || n.contains("phi3") || n.contains("phi_3")) return PHI3;
        return CHATML;   // Qwen and most others
    }

    /** msgs must start with a user message and end with the latest user message. */
    public String build(String system, List<Message> msgs) {
        StringBuilder sb = new StringBuilder();
        // Note: the BOS token is added automatically by the native tokenizer.
        switch (this) {
            case LLAMA3: {
                sb.append("<|start_header_id|>system<|end_header_id|>\n\n")
                        .append(system).append("<|eot_id|>");
                for (Message m : msgs) {
                    sb.append("<|start_header_id|>")
                            .append(m.role == Message.USER ? "user" : "assistant")
                            .append("<|end_header_id|>\n\n")
                            .append(m.text).append("<|eot_id|>");
                }
                sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n");
                break;
            }
            case GEMMA: {
                boolean first = true;
                for (Message m : msgs) {
                    boolean user = m.role == Message.USER;
                    sb.append("<start_of_turn>").append(user ? "user" : "model").append("\n");
                    if (first && user) sb.append(system).append("\n\n");   // Gemma has no system role
                    first = false;
                    sb.append(m.text).append("<end_of_turn>\n");
                }
                sb.append("<start_of_turn>model\n");
                break;
            }
            case PHI3: {
                sb.append("<|system|>\n").append(system).append("<|end|>\n");
                for (Message m : msgs) {
                    sb.append(m.role == Message.USER ? "<|user|>\n" : "<|assistant|>\n")
                            .append(m.text).append("<|end|>\n");
                }
                sb.append("<|assistant|>\n");
                break;
            }
            default: {   // CHATML
                sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n");
                for (Message m : msgs) {
                    sb.append("<|im_start|>")
                            .append(m.role == Message.USER ? "user" : "assistant")
                            .append("\n").append(m.text).append("<|im_end|>\n");
                }
                sb.append("<|im_start|>assistant\n");
            }
        }
        return sb.toString();
    }
}