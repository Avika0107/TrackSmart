package com.ekraah.repository;

import com.ekraah.model.Alert;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface AlertRepository extends MongoRepository<Alert, String> {

    List<Alert> findTop30ByUserIdOrderByCreatedAtDesc(String userId);

    List<Alert> findByUserIdAndReadFalse(String userId);

    long countByUserIdAndReadFalse(String userId);

    boolean existsByUserIdAndTrackingNumberAndKindAndCreatedAtAfter(String userId, String trackingNumber,
                                                                  Alert.Kind kind, Instant after);

    void deleteByUserId(String userId);

    long deleteByCreatedAtBefore(Instant cutoff);
}
