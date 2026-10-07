package com.parcelpilot.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class RiskInfo {
    private RiskLevel level;
    private int score;
    private List<String> reasons = new ArrayList<>();
    private String message;
    private Instant computedAt;

    public static RiskInfo none() {
        RiskInfo r = new RiskInfo();
        r.level = RiskLevel.LOW;
        r.score = 0;
        r.message = "";
        r.computedAt = Instant.now();
        return r;
    }

    public RiskLevel getLevel() { return level; }
    public void setLevel(RiskLevel level) { this.level = level; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public List<String> getReasons() { return reasons; }
    public void setReasons(List<String> reasons) { this.reasons = reasons; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getComputedAt() { return computedAt; }
    public void setComputedAt(Instant computedAt) { this.computedAt = computedAt; }
}
