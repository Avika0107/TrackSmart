package com.ekraah.mail;

import com.ekraah.model.AuditAction;
import com.ekraah.model.MailFailure;
import com.ekraah.repository.MailFailureRepository;
import com.ekraah.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides whether a polled email that failed to process stays unread for another attempt.
 * The poller used to mark everything read ("never re-loop"), so a temporary MongoDB outage
 * silently lost those emails. Now a failed email stays in the inbox (its content is never copied
 * here) and is retried on the next poll, up to MAX_ATTEMPTS, then given up and audited.
 */
@Service
public class MailRetries {

    private static final Logger log = LoggerFactory.getLogger(MailRetries.class);
    static final int MAX_ATTEMPTS = 5;
    static final String ERROR = "ERROR";

    private final MailFailureRepository failures;
    private final AuditService audit;

    public MailRetries(MailFailureRepository failures, AuditService audit) {
        this.failures = failures;
        this.audit = audit;
    }

    /** @return true to leave the email unread so the next poll retries it. */
    public boolean retryLater(String messageId, String outcome) {
        if (!ERROR.equals(outcome)) {
            if (messageId != null) forget(messageId);   // a retry succeeded: drop the bookkeeping
            return false;
        }
        if (messageId == null) return false;            // can't be told apart from other mail: don't loop
        try {
            MailFailure f = failures.findById(messageId).orElseGet(() -> {
                MailFailure n = new MailFailure();
                n.setMessageId(messageId);
                n.setFirstFailedAt(Instant.now());
                return n;
            });
            f.setAttempts(f.getAttempts() + 1);
            f.setLastFailedAt(Instant.now());
            if (f.getAttempts() >= MAX_ATTEMPTS) {
                f.setGaveUp(true);
                failures.save(f);
                audit.record(null, null, AuditAction.ERROR,
                        "An email failed " + MAX_ATTEMPTS + " times and was skipped — it is still in the inbox");
                log.error("Giving up on an email after {} failed attempts", MAX_ATTEMPTS);
                return false;
            }
            failures.save(f);
            log.warn("Email processing failed (attempt {}/{}); it stays unread for the next poll",
                    f.getAttempts(), MAX_ATTEMPTS);
            return true;
        } catch (Exception e) {
            // Usually the same outage that failed the processing: keep the email for later.
            log.warn("Could not record the failed attempt ({}); leaving the email unread", e.getMessage());
            return true;
        }
    }

    private void forget(String messageId) {
        try {
            failures.deleteById(messageId);
        } catch (Exception ignored) { /* bookkeeping only */ }
    }

    /** Weekly cleanup: old give-ups are only diagnostic. */
    public long deleteOlderThan(Duration age) {
        return failures.deleteByLastFailedAtBefore(Instant.now().minus(age));
    }
}
