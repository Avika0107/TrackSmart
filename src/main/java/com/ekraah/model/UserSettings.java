package com.ekraah.model;

public class UserSettings {
    private String theme = "light";            // light | dark
    private boolean notificationsOn = true;
    private String defaultCity;                 // optional; pre-fills city-based weather lookups

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
    public boolean isNotificationsOn() { return notificationsOn; }
    public void setNotificationsOn(boolean notificationsOn) { this.notificationsOn = notificationsOn; }
    public String getDefaultCity() { return defaultCity; }
    public void setDefaultCity(String defaultCity) { this.defaultCity = defaultCity; }
}
