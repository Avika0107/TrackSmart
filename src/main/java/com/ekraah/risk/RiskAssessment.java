package com.ekraah.risk;

import com.ekraah.model.RiskLevel;

import java.util.List;

/**
 * Transparent, rule-based result (NOT a trained ML model — no accuracy is claimed).
 */
public record RiskAssessment(
        RiskLevel level,
        int score,
        List<String> reasons,
        String city,
        String message) {
}
