package com.ekraah.repository;

import com.ekraah.model.MailFailure;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;

public interface MailFailureRepository extends MongoRepository<MailFailure, String> {

    long deleteByLastFailedAtBefore(Instant cutoff);
}
