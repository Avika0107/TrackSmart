package com.parcelpilot.util;

/** Never log raw phone numbers or tracking numbers; always pass through these. */
public final class Mask {

    private Mask() {}

    public static String phone(String phone) {
        if (phone == null || phone.length() < 4) return "***";
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 6) return "***";
        return digits.substring(0, 2) + "****" + digits.substring(digits.length() - 4);
    }

    public static String tracking(String awb) {
        if (awb == null || awb.length() < 4) return "****";
        return "****" + awb.substring(awb.length() - 4);
    }

    public static String email(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        return (at <= 2 ? "*" : email.substring(0, 2)) + "***" + email.substring(at);
    }
}
