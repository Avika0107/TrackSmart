package com.parcelpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * All tunables live here; every value has a default in application.yml and can be
 * overridden by env vars. No secret should ever be logged.
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Otp otp = new Otp();
    private final Mail mail = new Mail();
    private final Tracking tracking = new Tracking();
    private final Weather weather = new Weather();
    private final Refresh refresh = new Refresh();
    private final Cleanup cleanup = new Cleanup();
    private final Risk risk = new Risk();
    private final Seed seed = new Seed();
    private List<String> allowedSenders = List.of();

    public Jwt getJwt() { return jwt; }
    public Otp getOtp() { return otp; }
    public Mail getMail() { return mail; }
    public Tracking getTracking() { return tracking; }
    public Weather getWeather() { return weather; }
    public Refresh getRefresh() { return refresh; }
    public Cleanup getCleanup() { return cleanup; }
    public Risk getRisk() { return risk; }
    public Seed getSeed() { return seed; }
    public List<String> getAllowedSenders() { return allowedSenders; }
    public void setAllowedSenders(List<String> allowedSenders) { this.allowedSenders = allowedSenders; }

    public static class Jwt {
        private String secret;
        private long ttlMinutes = 720;
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getTtlMinutes() { return ttlMinutes; }
        public void setTtlMinutes(long ttlMinutes) { this.ttlMinutes = ttlMinutes; }
    }

    public static class Otp {
        private String sender = "demo";
        private String fixed = "";            // demo profile pins a fixed OTP for demos
        private int ttlMinutes = 5;
        private int maxPerWindow = 3;
        private int maxAttempts = 5;
        private String fast2smsKey = "";       // Fast2SMS API key (Dev API section of the dashboard)
        private String fast2smsSenderId = "";  // approved 6-letter DLT header; empty -> Quick SMS route (₹5/SMS!)
        private String fast2smsTemplateId = ""; // approved DLT OTP template ID whose {#var#} holds the code
        public String getSender() { return sender; }
        public void setSender(String sender) { this.sender = sender; }
        public String getFixed() { return fixed; }
        public void setFixed(String fixed) { this.fixed = fixed; }
        public int getTtlMinutes() { return ttlMinutes; }
        public void setTtlMinutes(int ttlMinutes) { this.ttlMinutes = ttlMinutes; }
        public int getMaxPerWindow() { return maxPerWindow; }
        public void setMaxPerWindow(int maxPerWindow) { this.maxPerWindow = maxPerWindow; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public String getFast2smsKey() { return fast2smsKey; }
        public void setFast2smsKey(String fast2smsKey) { this.fast2smsKey = fast2smsKey; }
        public String getFast2smsSenderId() { return fast2smsSenderId; }
        public void setFast2smsSenderId(String fast2smsSenderId) { this.fast2smsSenderId = fast2smsSenderId; }
        public String getFast2smsTemplateId() { return fast2smsTemplateId; }
        public void setFast2smsTemplateId(String fast2smsTemplateId) { this.fast2smsTemplateId = fast2smsTemplateId; }
    }

    public static class Mail {
        private String host = "imap.gmail.com";
        private String user;
        private String password;
        private String forwardTo;
        private long pollIntervalMs = 120_000;
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getForwardTo() { return forwardTo; }
        public void setForwardTo(String forwardTo) { this.forwardTo = forwardTo; }
        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
        public boolean configured() { return user != null && !user.isBlank() && password != null && !password.isBlank(); }
    }

    public static class Tracking {
        private String provider = "mock";
        private int dailyBudget = 200;
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public int getDailyBudget() { return dailyBudget; }
        public void setDailyBudget(int dailyBudget) { this.dailyBudget = dailyBudget; }
    }

    public static class Weather {
        private String provider = "mock";
        private String mockMode = "stormy";
        private String geocodeUrl = "https://geocoding-api.open-meteo.com/v1/search";
        private String forecastUrl = "https://api.open-meteo.com/v1/forecast";
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getMockMode() { return mockMode; }
        public void setMockMode(String mockMode) { this.mockMode = mockMode; }
        public String getGeocodeUrl() { return geocodeUrl; }
        public void setGeocodeUrl(String geocodeUrl) { this.geocodeUrl = geocodeUrl; }
        public String getForecastUrl() { return forecastUrl; }
        public void setForecastUrl(String forecastUrl) { this.forecastUrl = forecastUrl; }
    }

    public static class Refresh {
        private long intervalMs = 14_400_000;
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
    }

    public static class Cleanup {
        private String cron = "0 0 3 * * SUN";
        private int keepDeliveredDays = 30;
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public int getKeepDeliveredDays() { return keepDeliveredDays; }
        public void setKeepDeliveredDays(int keepDeliveredDays) { this.keepDeliveredDays = keepDeliveredDays; }
    }

    public static class Risk {
        private double heavyRainMm = 20;
        private int heavyRainPoints = 3;
        private List<Integer> thunderstormCodes = List.of(95, 96, 99);
        private int thunderstormPoints = 3;
        private double strongWindKmph = 40;
        private int strongWindPoints = 2;
        private double fogVisibilityKm = 1;
        private int fogPoints = 1;
        private double extremeHeatC = 42;
        private int extremeHeatPoints = 1;
        public double getHeavyRainMm() { return heavyRainMm; }
        public void setHeavyRainMm(double heavyRainMm) { this.heavyRainMm = heavyRainMm; }
        public int getHeavyRainPoints() { return heavyRainPoints; }
        public void setHeavyRainPoints(int heavyRainPoints) { this.heavyRainPoints = heavyRainPoints; }
        public List<Integer> getThunderstormCodes() { return thunderstormCodes; }
        public void setThunderstormCodes(List<Integer> thunderstormCodes) { this.thunderstormCodes = thunderstormCodes; }
        public int getThunderstormPoints() { return thunderstormPoints; }
        public void setThunderstormPoints(int thunderstormPoints) { this.thunderstormPoints = thunderstormPoints; }
        public double getStrongWindKmph() { return strongWindKmph; }
        public void setStrongWindKmph(double strongWindKmph) { this.strongWindKmph = strongWindKmph; }
        public int getStrongWindPoints() { return strongWindPoints; }
        public void setStrongWindPoints(int strongWindPoints) { this.strongWindPoints = strongWindPoints; }
        public double getFogVisibilityKm() { return fogVisibilityKm; }
        public void setFogVisibilityKm(double fogVisibilityKm) { this.fogVisibilityKm = fogVisibilityKm; }
        public int getFogPoints() { return fogPoints; }
        public void setFogPoints(int fogPoints) { this.fogPoints = fogPoints; }
        public double getExtremeHeatC() { return extremeHeatC; }
        public void setExtremeHeatC(double extremeHeatC) { this.extremeHeatC = extremeHeatC; }
        public int getExtremeHeatPoints() { return extremeHeatPoints; }
        public void setExtremeHeatPoints(int extremeHeatPoints) { this.extremeHeatPoints = extremeHeatPoints; }
    }

    public static class Seed {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
