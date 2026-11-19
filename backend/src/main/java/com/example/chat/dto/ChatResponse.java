package com.example.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public class ChatResponse {
    private String requestId;
    private String answer;
    private long elapsedMs;
    private String status;
    private UsageMetrics usage;

    // Solo en respuestas 400; se omite del JSON cuando es null.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ValidationError> errors;

    // Mensajes de historial enviados al modelo (feature 006). Solo en respuestas 200; se omite cuando es null.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer historyMessages;

    // Fragmentos del documento usados como contexto (Reto 3). Solo en respuestas 200; se omite cuando es null.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<SourceInfo> sources;

    public ChatResponse() {}

    public ChatResponse(String requestId, String answer, long elapsedMs, String status, UsageMetrics usage) {
        this.requestId = requestId;
        this.answer = answer;
        this.elapsedMs = elapsedMs;
        this.status = status;
        this.usage = usage;
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UsageMetrics getUsage() { return usage; }
    public void setUsage(UsageMetrics usage) { this.usage = usage; }
    public List<ValidationError> getErrors() { return errors; }
    public void setErrors(List<ValidationError> errors) { this.errors = errors; }
    public Integer getHistoryMessages() { return historyMessages; }
    public void setHistoryMessages(Integer historyMessages) { this.historyMessages = historyMessages; }
    public List<SourceInfo> getSources() { return sources; }
    public void setSources(List<SourceInfo> sources) { this.sources = sources; }
}
