package com.example.chat.classification;

/**
 * Resultado de clasificar un texto. Se usan tipos envoltorio para que un campo ausente en la respuesta
 * del modelo llegue como null y se pueda nombrar, en lugar de fallar al deserializar.
 *
 * @param category      una de las cuatro categorias
 * @param confidence    decimal de 0.0 a 1.0
 * @param justification explicacion breve, no vacia
 */
public record Classification(Category category, Double confidence, String justification) {
}
