package com.parcelpilot.service;

import com.parcelpilot.model.AuditAction;
import com.parcelpilot.model.AuditLog;
import com.parcelpilot.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) {
        this.repo = repo;
    }

    /** Audit failures must never break the mail pipeline. */
    public void record(String userId, String senderDomain, AuditAction action, String summary) {
        try {
            repo.save(new AuditLog(userId, senderDomain, action, summary));
        } catch (Exception e) {
            log.warn("Audit write failed: {}", e.getMessage());
        }
    }

    public List<AuditLog> forUser(String userId) {
        return repo.findByUserId(userId, Sort.by(Sort.Direction.DESC, "time"));
    }

    public void deleteForUser(String userId) {
        repo.deleteAll(repo.findByUserId(userId, Sort.unsorted()));
    }
}
