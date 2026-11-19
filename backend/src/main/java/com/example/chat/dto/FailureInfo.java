package com.example.chat.dto;

/** Chequeo de formato que fallo ("json", "category", "confidence" o "justification") y el motivo. */
public record FailureInfo(String check, String message) {
}
