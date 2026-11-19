package com.example.chat.memory;

import com.example.chat.ModelResult;
import com.example.chat.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Estas pruebas simulan al asesor de Spring AI escribiendo en el repositorio dentro de la accion
 * (guarda la pregunta y la respuesta): lo que se prueba aqui es el bloqueo y la restauracion.
 */
class ConversationMemoryTest {

    private BoundedChatMemoryRepository repository;
    private SessionLocks locks;
    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        MemorySettings settings = new MemorySettings();
        repository = new BoundedChatMemoryRepository(settings, new MutableClock());
        locks = new SessionLocks();
        memory = new ConversationMemory(settings, repository, locks);
    }

    private void append(String id, String question, String answer) {
        List<Message> current = new ArrayList<>(repository.findByConversationId(id));
        current.add(new UserMessage(question));
        if (answer != null) {
            current.add(new AssistantMessage(answer));
        }
        repository.saveAll(id, current);
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }

    @Test
    void theActionGetsTheMemoryAdvisorAndTheHistorySizeZeroForANewSession() {
        ModelResult result = memory.runTurn("s1", (advisor, history) -> {
            assertInstanceOf(MessageChatMemoryAdvisor.class, advisor);
            assertEquals(0, history);
            return new ModelResult("hello", null);
        });
        assertEquals("hello", result.answer());
        assertEquals(0, result.historyMessages());
    }

    @Test
    void theHistorySizeIsTheNumberOfStoredMessagesAndIsReturnedInTheResult() {
        append("s1", "q1", "a1");
        append("s1", "q2", "a2");
        ModelResult result = memory.runTurn("s1", (advisor, history) -> {
            assertEquals(4, history);
            return new ModelResult("a3", null);
        });
        assertEquals(4, result.historyMessages());
    }

    @Test
    void aSuccessfulTurnIsKept() {
        memory.runTurn("s1", (advisor, history) -> {
            append("s1", "q1", "a1");
            return new ModelResult("a1", null);
        });
        assertEquals(List.of("q1", "a1"), texts(repository.findByConversationId("s1")));
    }

    @Test
    void whenTheActionThrowsTheHistoryIsRestoredAndTheExceptionPropagates() {
        append("s1", "q1", "a1");
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                memory.runTurn("s1", (advisor, history) -> {
                    append("s1", "q2", null); // el asesor guarda la pregunta antes de llamar al modelo
                    throw new IllegalStateException("boom");
                }));
        assertEquals("boom", error.getMessage());
        assertEquals(List.of("q1", "a1"), texts(repository.findByConversationId("s1")));
    }

    @Test
    void aBlankAnswerRestoresTheHistory() {
        append("s1", "q1", "a1");
        ModelResult result = memory.runTurn("s1", (advisor, history) -> {
            append("s1", "q2", "");
            return new ModelResult("   ", null);
        });
        assertEquals("   ", result.answer());
        assertEquals(List.of("q1", "a1"), texts(repository.findByConversationId("s1")));
    }

    @Test
    void aNullAnswerRestoresTheHistory() {
        append("s1", "q1", "a1");
        memory.runTurn("s1", (advisor, history) -> {
            append("s1", "q2", null);
            return new ModelResult(null, null);
        });
        assertEquals(List.of("q1", "a1"), texts(repository.findByConversationId("s1")));
    }

    @Test
    void aSessionThatDidNotExistStaysAbsentAfterAFailedFirstTurn() {
        assertThrows(IllegalStateException.class, () ->
                memory.runTurn("new", (advisor, history) -> {
                    append("new", "q1", null);
                    throw new IllegalStateException("boom");
                }));
        assertFalse(repository.findConversationIds().contains("new"));
        assertTrue(repository.findByConversationId("new").isEmpty());
    }

    @Test
    void aBlankFirstTurnLeavesNoSessionBehind() {
        memory.runTurn("new", (advisor, history) -> {
            append("new", "q1", "");
            return new ModelResult("", null);
        });
        assertFalse(repository.findConversationIds().contains("new"));
    }

    @Test
    void turnsOfTheSameSessionNeverOverlap() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            futures.add(pool.submit(() -> memory.runTurn("same", (advisor, history) -> {
                maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                Thread.yield();
                running.decrementAndGet();
                return new ModelResult("ok", null);
            })));
        }
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, maxRunning.get(), "dos turnos de la misma sesion se ejecutaron a la vez");
    }

    @Test
    void twoHundredConcurrentTurnsOfOneSessionKeepEveryMessageInOrder() throws Exception {
        MemorySettings big = new MemorySettings();
        big.setMaxSessions(10);
        BoundedChatMemoryRepository bigRepository = new BoundedChatMemoryRepository(big, new MutableClock());
        ConversationMemory concurrent = new ConversationMemory(big, bigRepository, new SessionLocks());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            final int n = i;
            futures.add(pool.submit(() -> concurrent.runTurn("same", (advisor, history) -> {
                List<Message> current = new ArrayList<>(bigRepository.findByConversationId("same"));
                current.add(new UserMessage("q" + n));
                current.add(new AssistantMessage("a" + n));
                bigRepository.saveAll("same", current);
                return new ModelResult("a" + n, null);
            })));
        }
        for (Future<?> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<Message> stored = bigRepository.findByConversationId("same");
        assertEquals(400, stored.size());
        for (int i = 0; i < stored.size(); i++) {
            MessageType expected = i % 2 == 0 ? MessageType.USER : MessageType.ASSISTANT;
            assertEquals(expected, stored.get(i).getMessageType(), "posicion " + i);
            if (i % 2 == 1) {
                assertEquals("a" + stored.get(i - 1).getText().substring(1), stored.get(i).getText(),
                        "cada respuesta sigue a su pregunta");
            }
        }
    }

    @Test
    void turnsOfDifferentSessionsRunAtTheSameTime() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondFinished = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> first = pool.submit(() -> memory.runTurn("one", (advisor, history) -> {
            firstStarted.countDown();
            try {
                assertTrue(secondFinished.await(5, TimeUnit.SECONDS),
                        "la otra sesion debio terminar mientras esta seguia dentro del modelo");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ModelResult("one", null);
        }));
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        Future<?> second = pool.submit(() -> memory.runTurn("two", (advisor, history) -> {
            secondFinished.countDown();
            return new ModelResult("two", null);
        }));
        second.get(5, TimeUnit.SECONDS);
        first.get(5, TimeUnit.SECONDS);
        pool.shutdown();
    }

    @Test
    void noLockIsLeftBehindAfterTheTurnsFinishNorAfterAFailure() {
        memory.runTurn("a", (advisor, history) -> new ModelResult("ok", null));
        assertThrows(IllegalStateException.class, () ->
                memory.runTurn("b", (advisor, history) -> {
                    throw new IllegalStateException("boom");
                }));
        assertEquals(0, locks.size());
    }

    @Test
    void theResultOfAFailedBlankTurnStillCarriesTheHistorySize() {
        append("s1", "q1", "a1");
        ModelResult result = memory.runTurn("s1", (advisor, history) -> new ModelResult("", null));
        assertNull(result.usage());
        assertEquals(2, result.historyMessages());
    }
}
