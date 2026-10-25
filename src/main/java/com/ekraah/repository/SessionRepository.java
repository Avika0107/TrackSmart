package com.ekraah.repository;

import com.ekraah.model.Session;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SessionRepository extends MongoRepository<Session, String> {

    Optional<Session> findByTokenHash(String tokenHash);

    Optional<Session> findByPreviousTokenHash(String previousTokenHash);

    List<Session> findByUserIdAndRevokedAtIsNull(String userId);

    void deleteByUserId(String userId);

    long deleteByExpiresAtBefore(Instant cutoff);
}
