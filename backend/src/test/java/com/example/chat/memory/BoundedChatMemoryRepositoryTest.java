package com.example.chat.memory;

import com.example.chat.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedChatMemoryRepositoryTest {

    private MutableClock clock;
    private BoundedChatMemoryRepository repository;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        repository = repositoryWith(3);
    }

    private BoundedChatMemoryRepository repositoryWith(int maxSessions) {
        MemorySettings settings = new MemorySettings();
        settings.setIdleTimeout(Duration.ofMinutes(30));
        settings.setMaxSessions(maxSessions);
        return new BoundedChatMemoryRepository(settings, clock);
    }

    private static List<Message> turn(String question, String answer) {
        return new ArrayList<>(List.of(new UserMessage(question), new AssistantMessage(answer)));
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }

    @Test
    void savedMessagesComeBackInTheSameOrder() {
        repository.saveAll("a", turn("q1", "a1"));
        assertEquals(List.of("q1", "a1"), texts(repository.findByConversationId("a")));
    }

    @Test
    void saveAllReplacesWhatWasStored() {
        repository.saveAll("a", turn("q1", "a1"));
        repository.saveAll("a", turn("q2", "a2"));
        assertEquals(List.of("q2", "a2"), texts(repository.findByConversationId("a")));
    }

    @Test
    void twoIdsNeverSeeEachOthersMessages() {
        repository.saveAll("a", turn("qa", "aa"));
        repository.saveAll("b", turn("qb", "ab"));
        assertEquals(List.of("qa", "aa"), texts(repository.findByConversationId("a")));
        assertEquals(List.of("qb", "ab"), texts(repository.findByConversationId("b")));
    }

    @Test
    void theReturnedListIsACopyAndTheStoredOneIsNotAffectedByTheCaller() {
        List<Message> original = turn("q1", "a1");
        repository.saveAll("a", original);
        original.add(new UserMessage("added after saving"));
        List<Message> read = repository.findByConversationId("a");
        assertEquals(2, read.size(), "cambiar la lista guardada por el llamante no cambia el almacen");
        try {
            read.add(new UserMessage("added to the copy"));
        } catch (UnsupportedOperationException ignored) {
            // una lista inmutable tambien protege el almacen
        }
        assertEquals(2, repository.findByConversationId("a").size());
    }

    @Test
    void anUnknownIdReturnsAnEmptyList() {
        assertTrue(repository.findByConversationId("nobody").isEmpty());
    }

    @Test
    void deleteRemovesOneIdOnly() {
        repository.saveAll("a", turn("qa", "aa"));
        repository.saveAll("b", turn("qb", "ab"));
        repository.deleteByConversationId("a");
        assertTrue(repository.findByConversationId("a").isEmpty());
        assertEquals(2, repository.findByConversationId("b").size());
    }

    @Test
    void anIdIdleForLongerThanTheTimeoutIsGoneOnTheNextRead() {
        repository.saveAll("a", turn("q1", "a1"));
        clock.advance(Duration.ofMinutes(31));
        assertTrue(repository.findByConversationId("a").isEmpty());
        assertFalse(repository.findConversationIds().contains("a"));
    }

    @Test
    void anIdIdleForLessThanTheTimeoutIsKept() {
        repository.saveAll("a", turn("q1", "a1"));
        clock.advance(Duration.ofMinutes(29));
        assertEquals(2, repository.findByConversationId("a").size());
    }

    @Test
    void aReadRefreshesTheLastUseTime() {
        repository.saveAll("a", turn("q1", "a1"));
        clock.advance(Duration.ofMinutes(20));
        repository.findByConversationId("a");
        clock.advance(Duration.ofMinutes(20));
        assertEquals(2, repository.findByConversationId("a").size(),
                "40 minutos desde el guardado, pero solo 20 desde la ultima lectura");
    }

    @Test
    void aWriteRefreshesTheLastUseTime() {
        repository.saveAll("a", turn("q1", "a1"));
        clock.advance(Duration.ofMinutes(20));
        repository.saveAll("a", turn("q2", "a2"));
        clock.advance(Duration.ofMinutes(20));
        assertEquals(List.of("q2", "a2"), texts(repository.findByConversationId("a")));
    }

    @Test
    void theFourthSessionDropsTheLeastRecentlyUsedNotTheOldestCreated() {
        repository.saveAll("a", turn("qa", "aa"));
        repository.saveAll("b", turn("qb", "ab"));
        repository.saveAll("c", turn("qc", "ac"));
        repository.findByConversationId("a");
        repository.saveAll("d", turn("qd", "ad"));

        assertTrue(repository.findByConversationId("b").isEmpty(), "b era la menos usada");
        assertEquals(2, repository.findByConversationId("a").size());
        assertEquals(2, repository.findByConversationId("c").size());
        assertEquals(2, repository.findByConversationId("d").size());
    }

    @Test
    void theNumberOfSessionsNeverExceedsTheCap() {
        for (int i = 0; i < 50; i++) {
            repository.saveAll("s" + i, turn("q", "a"));
            assertTrue(repository.findConversationIds().size() <= 3,
                    "hay " + repository.findConversationIds().size() + " sesiones tras guardar la " + i);
        }
    }

    @Test
    void updatingAnExistingSessionNeverEvictsAnotherOne() {
        repository.saveAll("a", turn("qa", "aa"));
        repository.saveAll("b", turn("qb", "ab"));
        repository.saveAll("c", turn("qc", "ac"));
        repository.saveAll("a", turn("qa2", "aa2"));
        assertEquals(3, repository.findConversationIds().size());
    }
}
