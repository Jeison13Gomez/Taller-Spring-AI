package com.example.chat.dto;

/** Un parametro de la peticion que se rechazo y el motivo. */
public record ValidationError(String field, String message) {
}
