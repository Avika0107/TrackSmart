package com.ekraah.mail;

import com.ekraah.model.AuditAction;
import com.ekraah.model.MailFailure;
import com.ekraah.repository.MailFailureRepository;
import com.ekraah.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A failed email stays unread for retries (up to 5); success or other outcomes are final. */
class MailRetriesTest {

    private final Map<String, MailFailure> db = new HashMap<>();
    private MailFailureRepository repo;
    private AuditService audit;
    private MailRetries retries;

    @BeforeEach
    void setUp() {
        repo = mock(MailFailureRepository.class);
        audit = mock(AuditService.class);
        when(repo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(db.get(inv.<String>getArgument(0))));
        when(repo.save(any())).thenAnswer(inv -> {
            MailFailure f = inv.getArgument(0);
            db.put(f.getMessageId(), f);
            return f;
        });
        retries = new MailRetries(repo, audit);
    }

    @Test
    void failedEmailIsRetriedUntilTheFifthAttemptThenGivenUp() {
        for (int i = 1; i < MailRetries.MAX_ATTEMPTS; i++) {
            assertThat(retries.retryLater("m1", "ERROR")).as("attempt " + i).isTrue();
        }
        assertThat(retries.retryLater("m1", "ERROR")).isFalse();      // 5th failure: mark read, stop looping
        assertThat(db.get("m1").isGaveUp()).isTrue();
        verify(audit).record(eq(null), eq(null), eq(AuditAction.ERROR), any());
    }

    @Test
    void successfulRetryForgetsTheFailure() {
        retries.retryLater("m2", "ERROR");
        assertThat(retries.retryLater("m2", "CREATED")).isFalse();
        verify(repo).deleteById("m2");
    }

    @Test
    void ignoredOutcomesAreFinal() {
        assertThat(retries.retryLater("m3", "IGNORED_SENDER")).isFalse();
        assertThat(retries.retryLater("m3", "UNVERIFIED_SENDER")).isFalse();
    }

    @Test
    void emailWithoutMessageIdIsNeverLooped() {
        assertThat(retries.retryLater(null, "ERROR")).isFalse();
        verify(repo, never()).save(any());
    }

    @Test
    void whenTheDatabaseIsDownTheEmailStaysUnread() {
        when(repo.findById(anyString())).thenThrow(new RuntimeException("mongo down"));
        assertThat(retries.retryLater("m4", "ERROR")).isTrue();
    }
}
