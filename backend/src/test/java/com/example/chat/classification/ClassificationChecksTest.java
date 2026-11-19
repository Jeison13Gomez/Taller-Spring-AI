package com.example.chat.classification;

import com.example.chat.dto.FailureInfo;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassificationChecksTest {

    private static final BeanOutputConverter<Classification> CONVERTER = new BeanOutputConverter<>(Classification.class);

    private static String failedCheck(Classification classification) {
        Optional<FailureInfo> failure = ClassificationChecks.check(classification);
        assertTrue(failure.isPresent(), "se esperaba un fallo para " + classification);
        assertFalse(failure.get().message().isBlank());
        return failure.get().check();
    }

    /** Convierte con el conversor real de Spring AI y devuelve el chequeo que falla. */
    private static String checkOfConversionOf(String modelAnswer) {
        RuntimeException error = assertThrows(RuntimeException.class, () -> CONVERTER.convert(modelAnswer));
        FailureInfo failure = ClassificationChecks.fromConversionError(error);
        assertFalse(failure.message().isBlank());
        return failure.check();
    }

    @Test
    void aValidClassificationPasses() {
        assertTrue(ClassificationChecks.check(new Classification(Category.queja, 0.9, "x")).isEmpty());
        assertTrue(ClassificationChecks.check(new Classification(Category.otro, 0.0, "x")).isEmpty());
        assertTrue(ClassificationChecks.check(new Classification(Category.consulta, 1.0, "x")).isEmpty());
    }

    @Test
    void aMissingCategoryFails() {
        assertEquals("category", failedCheck(new Classification(null, 0.9, "x")));
    }

    @Test
    void aMissingOrOutOfRangeConfidenceFails() {
        assertEquals("confidence", failedCheck(new Classification(Category.queja, null, "x")));
        assertEquals("confidence", failedCheck(new Classification(Category.queja, 1.5, "x")));
        assertEquals("confidence", failedCheck(new Classification(Category.queja, -0.1, "x")));
        assertEquals("confidence", failedCheck(new Classification(Category.queja, Double.NaN, "x")));
    }

    @Test
    void aMissingOrBlankJustificationFails() {
        assertEquals("justification", failedCheck(new Classification(Category.queja, 0.9, null)));
        assertEquals("justification", failedCheck(new Classification(Category.queja, 0.9, "")));
        assertEquals("justification", failedCheck(new Classification(Category.queja, 0.9, "   ")));
    }

    @Test
    void theFirstFailedCheckIsReportedInFieldOrder() {
        assertEquals("category", failedCheck(new Classification(null, 5.0, "")));
    }

    @Test
    void textThatIsNotJsonOrIsEmptyMapsToJson() {
        assertEquals("json", checkOfConversionOf("queja"));
        assertEquals("json", checkOfConversionOf("Aqui esta: {\"category\":\"queja\"}"));
        assertEquals("json", checkOfConversionOf(""));
    }

    @Test
    void aNullAnswerMapsToJson() {
        Supplier<Object> convertNull = () -> CONVERTER.convert((String) null);
        RuntimeException error = assertThrows(RuntimeException.class, convertNull::get);

        assertEquals("json", ClassificationChecks.fromConversionError(error).check());
    }

    @Test
    void aCategoryOutsideTheSetOrWithOtherCaseMapsToCategory() {
        assertEquals("category", checkOfConversionOf("{\"category\":\"spam\",\"confidence\":0.9,\"justification\":\"x\"}"));
        assertEquals("category", checkOfConversionOf("{\"category\":\"Queja\",\"confidence\":0.9,\"justification\":\"x\"}"));
    }

    @Test
    void aConfidenceThatIsNotANumberMapsToConfidence() {
        assertEquals("confidence", checkOfConversionOf("{\"category\":\"queja\",\"confidence\":\"alta\",\"justification\":\"x\"}"));
    }

    @Test
    void aFieldWithTheWrongKindOfValueMapsToThatField() {
        assertEquals("justification", checkOfConversionOf("{\"category\":\"queja\",\"confidence\":0.9,\"justification\":{\"a\":1}}"));
    }
}
