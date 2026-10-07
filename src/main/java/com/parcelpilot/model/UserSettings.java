package com.parcelpilot.model;

public class UserSettings {
    private String theme = "light";            // light | dark
    private boolean notificationsOn = true;

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
    public boolean isNotificationsOn() { return notificationsOn; }
    public void setNotificationsOn(boolean notificationsOn) { this.notificationsOn = notificationsOn; }
}
