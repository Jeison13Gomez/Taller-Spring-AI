package com.example.chat.dto;

import com.example.chat.classification.Classification;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Respuesta de /classify. Los campos que no aplican a cada caso (exito, error de validacion,
 * fallo de formato) son null y no aparecen en el JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClassifyResponse {
    private String requestId;
    private String status;
    private Classification result;
    private String strategy;
    private Boolean retried;
    private Integer retries;
    private Integer attempts;
    private Long elapsedMs;
    private UsageMetrics usage;
    private FailureInfo failure;
    private UsageMetrics consumed;
    private List<ValidationError> errors;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Classification getResult() { return result; }
    public void setResult(Classification result) { this.result = result; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public Boolean getRetried() { return retried; }
    public void setRetried(Boolean retried) { this.retried = retried; }
    public Integer getRetries() { return retries; }
    public void setRetries(Integer retries) { this.retries = retries; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public Long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(Long elapsedMs) { this.elapsedMs = elapsedMs; }
    public UsageMetrics getUsage() { return usage; }
    public void setUsage(UsageMetrics usage) { this.usage = usage; }
    public FailureInfo getFailure() { return failure; }
    public void setFailure(FailureInfo failure) { this.failure = failure; }
    public UsageMetrics getConsumed() { return consumed; }
    public void setConsumed(UsageMetrics consumed) { this.consumed = consumed; }
    public List<ValidationError> getErrors() { return errors; }
    public void setErrors(List<ValidationError> errors) { this.errors = errors; }
}
