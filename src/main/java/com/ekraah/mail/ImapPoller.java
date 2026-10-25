package com.ekraah.mail;

import com.ekraah.config.AppProperties;
import com.ekraah.dto.RawEmail;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.FlagTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * Connects to the demo Gmail inbox over IMAP every 2 minutes (configurable),
 * reads unseen messages through the pipeline and marks them seen — except ones that failed to
 * process, which stay unread for a retry on the next poll (MailRetries, at most 5 attempts).
 * Nothing is logged except counts and masked identifiers.
 */
@Service
public class ImapPoller {

    private static final Logger log = LoggerFactory.getLogger(ImapPoller.class);

    private final AppProperties props;
    private final GmailMessageProcessor processor;
    private final MailRetries retries;
    private volatile boolean loggedDisabled = false;

    public ImapPoller(AppProperties props, GmailMessageProcessor processor, MailRetries retries) {
        this.props = props;
        this.processor = processor;
        this.retries = retries;
    }

    @Scheduled(fixedDelayString = "${app.mail.poll-interval-ms:120000}", initialDelay = 20_000)
    @SchedulerLock(name = "imapPoll", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")   // one poller across instances
    public void scheduledPoll() {
        pollOnce(false);
    }

    /** Manual trigger for the admin/dev endpoint. @return number of emails processed, -1 on error. */
    public int pollNow() {
        return pollOnce(true);
    }

    private int pollOnce(boolean manual) {
        if (!props.getMail().configured()) {
            if (manual || !loggedDisabled) {
                log.info("IMAP polling disabled (MAIL_USER / MAIL_APP_PASSWORD not set)");
                loggedDisabled = true;
            }
            return 0;
        }
        try {
            Properties p = new Properties();
            p.put("mail.store.protocol", "imaps");
            p.put("mail.imaps.ssl.enable", "true");
            Session session = Session.getInstance(p);
            Store store = session.getStore("imaps");
            store.connect(props.getMail().getHost(), props.getMail().getUser(), props.getMail().getPassword());

            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            Message[] unread = inbox.search(new FlagTerm(new jakarta.mail.Flags(jakarta.mail.Flags.Flag.SEEN), false));
            int processed = 0;
            int retrying = 0;
            for (Message message : unread) {
                RawEmail raw = MimeUtils.safeToRawEmail(message);
                String outcome = processor.process(raw);
                if (retries.retryLater(raw.messageId(), outcome)) {
                    retrying++;                       // stays unread: the next poll tries again
                    continue;
                }
                message.setFlag(jakarta.mail.Flags.Flag.SEEN, true);
                processed++;
            }
            if (retrying > 0) log.warn("IMAP poll: {} email(s) failed and will be retried", retrying);
            inbox.close(true);
            store.close();
            if (processed > 0 || manual) {
                log.info("IMAP poll ({}): processed {} new message(s)", manual ? "manual" : "scheduled", processed);
            }
            return processed;
        } catch (Exception e) {
            log.warn("IMAP poll failed: {}", e.getMessage());
            return -1;
        }
    }
}
