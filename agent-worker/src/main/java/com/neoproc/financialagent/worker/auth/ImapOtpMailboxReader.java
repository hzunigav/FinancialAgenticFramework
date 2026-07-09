package com.neoproc.financialagent.worker.auth;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.Properties;

/**
 * Retrieves an email OTP over IMAPS. The mailbox is a deployment decision — this
 * class takes only connection config, so the same reader works against a
 * dedicated service inbox or a Gmail account with IMAP + an app-password (Gmail
 * host {@code imap.gmail.com:993}). Wire the concrete config from Secrets
 * Manager when the mailbox is chosen.
 *
 * <p>Poll strategy: reconnect-free where possible — hold the store open and, on
 * each poll, reopen the folder to pick up newly-delivered mail, coarse-search
 * server-side for <i>unseen messages since the freshness cutoff</i> (accent-safe
 * — we do NOT push the accented subject through IMAP SEARCH), then filter
 * precisely in Java via {@link OtpQuery} (from / subject / freshness / code
 * regex). The first matching message is consumed (flagged {@code \Seen}) so its
 * code is never returned twice, and its code is returned. Times out into an
 * {@link OtpUnavailableException} (→ retryable AUTH failure) if nothing matches.
 */
public final class ImapOtpMailboxReader implements OtpMailboxReader {

    private static final Logger log = LoggerFactory.getLogger(ImapOtpMailboxReader.class);

    /** IMAP connection config. {@code folder} defaults to INBOX; SSL/IMAPS assumed. */
    public record ImapConfig(String host, int port, String username, String password, String folder) {
        public ImapConfig(String host, int port, String username, String password) {
            this(host, port, username, password, "INBOX");
        }

        public String folderOrDefault() {
            return folder == null || folder.isBlank() ? "INBOX" : folder;
        }
    }

    private final ImapConfig config;

    public ImapOtpMailboxReader(ImapConfig config) {
        this.config = config;
    }

    @Override
    public String awaitCode(OtpQuery query) {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", config.host());
        props.put("mail.imaps.port", String.valueOf(config.port()));
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.connectiontimeout", "15000");
        props.put("mail.imaps.timeout", "15000");

        Session session = Session.getInstance(props);
        Instant deadline = Instant.now().plus(query.timeout());

        Store store = null;
        try {
            store = session.getStore("imaps");
            store.connect(config.host(), config.port(), config.username(), config.password());
            log.info("otp mailbox connected host={} user={} folder={}",
                    config.host(), maskUser(config.username()), config.folderOrDefault());

            do {
                String code = pollOnce(store, query);
                if (code != null) {
                    return code;
                }
                sleep(query.pollInterval());
            } while (Instant.now().isBefore(deadline));

            throw new OtpUnavailableException(
                    "No matching OTP email arrived within " + query.timeout()
                            + " (from~=" + query.fromContains() + ", subject~=" + query.subjectContains() + ")");
        } catch (MessagingException e) {
            throw new OtpUnavailableException(
                    "IMAP mailbox unreachable/error: " + e.getMessage(), e);
        } finally {
            closeQuietly(store);
        }
    }

    /** One folder scan; returns a code if a fresh, matching, unseen message is present, else null. */
    private String pollOnce(Store store, OtpQuery query) throws MessagingException {
        Folder inbox = store.getFolder(config.folderOrDefault());
        inbox.open(Folder.READ_WRITE);
        try {
            // Coarse server-side filter only (unseen + received-since). We keep
            // the accented subject/from OUT of the IMAP SEARCH to dodge server
            // charset quirks, and apply from/subject/code precisely in Java.
            SearchTerm term = new AndTerm(
                    new FlagTerm(new Flags(Flags.Flag.SEEN), false),
                    new ReceivedDateTerm(ComparisonTerm.GE, Date.from(query.notBefore())));
            Message[] found = inbox.search(term);

            // Newest first — if two codes are outstanding, trust the latest.
            java.util.Arrays.sort(found, Comparator.comparingLong(
                    (Message m) -> receivedEpoch(m)).reversed());

            for (Message m : found) {
                if (!query.fromMatches(firstFrom(m)) || !query.subjectMatches(subject(m))) {
                    continue;
                }
                if (!query.freshEnough(receivedInstant(m))) {
                    continue;
                }
                String body = extractText(m);
                var code = query.extractCode(body);
                if (code.isPresent()) {
                    m.setFlag(Flags.Flag.SEEN, true);   // consume — never return twice
                    log.info("otp code retrieved from message subject=\"{}\"", subject(m));
                    return code.get();
                }
            }
            return null;
        } finally {
            inbox.close(false);
        }
    }

    // --- message field helpers (all null-tolerant) --------------------------

    private static String firstFrom(Message m) {
        try {
            var from = m.getFrom();
            return from != null && from.length > 0 ? from[0].toString() : null;
        } catch (MessagingException e) {
            return null;
        }
    }

    private static String subject(Message m) {
        try {
            return m.getSubject();
        } catch (MessagingException e) {
            return null;
        }
    }

    private static Instant receivedInstant(Message m) {
        try {
            Date d = m.getReceivedDate();
            if (d == null) {
                d = m.getSentDate();
            }
            return d != null ? d.toInstant() : null;
        } catch (MessagingException e) {
            return null;
        }
    }

    private static long receivedEpoch(Message m) {
        Instant i = receivedInstant(m);
        return i != null ? i.toEpochMilli() : 0L;
    }

    /** Recursively pulls readable text (plain preferred, else html) from a part. */
    static String extractText(Part part) {
        try {
            if (part.isMimeType("text/*")) {
                Object content = part.getContent();
                return content != null ? content.toString() : "";
            }
            if (part.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) part.getContent();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < mp.getCount(); i++) {
                    sb.append(extractText(mp.getBodyPart(i))).append('\n');
                }
                return sb.toString();
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String maskUser(String user) {
        if (user == null || user.isBlank()) return "(none)";
        int at = user.indexOf('@');
        String local = at > 0 ? user.substring(0, at) : user;
        String domain = at > 0 ? user.substring(at) : "";
        return (local.length() <= 2 ? local.charAt(0) + "*" : local.charAt(0) + "***" + local.charAt(local.length() - 1))
                + domain;
    }

    private static void sleep(java.time.Duration d) {
        try {
            Thread.sleep(Math.max(1, d.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OtpUnavailableException("Interrupted while waiting for OTP email");
        }
    }

    private static void closeQuietly(Store store) {
        if (store != null) {
            try {
                store.close();
            } catch (MessagingException ignore) {
                // best effort
            }
        }
    }
}
