package com.parcelpilot.mail;

import com.parcelpilot.config.AppProperties;
import com.parcelpilot.dto.RawEmail;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.FlagTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * Connects to the demo Gmail inbox over IMAP every 2 minutes (configurable),
 * reads unseen messages through the pipeline and marks them seen.
 * Nothing is logged except counts and masked identifiers.
 */
@Service
public class ImapPoller {

    private static final Logger log = LoggerFactory.getLogger(ImapPoller.class);

    private final AppProperties props;
    private final GmailMessageProcessor processor;
    private volatile boolean loggedDisabled = false;

    public ImapPoller(AppProperties props, GmailMessageProcessor processor) {
        this.props = props;
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${app.mail.poll-interval-ms:120000}", initialDelay = 20_000)
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
            for (Message message : unread) {
                RawEmail raw = MimeUtils.safeToRawEmail(message);
                processor.process(raw);
                message.setFlag(jakarta.mail.Flags.Flag.SEEN, true); // even errors: never re-loop
                processed++;
            }
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
