package com.ekraah.model;

public enum AuditAction {
    PROCESSED,
    IGNORED_NOT_ALLOWED_SENDER,
    IGNORED_UNVERIFIED_SENDER,
    IGNORED_NO_TRACKING,
    DUPLICATE,
    ERROR
}
