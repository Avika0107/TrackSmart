package com.ekraah.security;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * OTPs shared by every instance. Documents expire on their own via a TTL index on purgeAt
 * (MongoConfig), set to whichever ends later: the code or the request-rate window.
 */
@Component
public class MongoOtpStore implements OtpStore {

    @Document("otps")
    static class OtpDoc {
        @Id String phone;
        String hash;
        Instant expiresAt;
        int attempts;
        Instant windowStart;
        int requests;
        Instant purgeAt;
    }

    private final MongoTemplate mongo;

    public MongoOtpStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<Entry> get(String phone) {
        OtpDoc d = mongo.findById(phone, OtpDoc.class);
        return d == null ? Optional.empty()
                : Optional.of(new Entry(d.hash, d.expiresAt, d.attempts, d.windowStart, d.requests));
    }

    @Override
    public void put(String phone, Entry e) {
        OtpDoc d = new OtpDoc();
        d.phone = phone;
        d.hash = e.hash();
        d.expiresAt = e.expiresAt();
        d.attempts = e.attempts();
        d.windowStart = e.windowStart();
        d.requests = e.requests();
        Instant windowEnd = e.windowStart().plus(OtpService.WINDOW);
        d.purgeAt = e.expiresAt().isAfter(windowEnd) ? e.expiresAt() : windowEnd;
        mongo.save(d);
    }

    @Override
    public void remove(String phone) {
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(
                org.springframework.data.mongodb.core.query.Criteria.where("_id").is(phone)), OtpDoc.class);
    }

}
