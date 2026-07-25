package com.example.core;

/**
 * Input validation. This class HAS a test (ValidatorTest) that executes almost every
 * line but asserts almost nothing — high line coverage, near-zero mutation score.
 * It is the eval's "covered but unverified" target.
 */
public final class Validator {

    private Validator() {
    }

    /** A username: 3–16 chars, letters/digits/underscore, must start with a letter. */
    public static boolean isValidUsername(String value) {
        if (value == null) {
            return false;
        }
        int length = value.length();
        if (length < 3 || length > 16) {
            return false;
        }
        if (!Character.isLetter(value.charAt(0))) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    /** Very small e-mail sanity check: one @, a dot in the domain, no spaces. */
    public static boolean isValidEmail(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.indexOf(' ') >= 0) {
            return false;
        }
        int at = value.indexOf('@');
        if (at <= 0 || at != value.lastIndexOf('@') || at == value.length() - 1) {
            return false;
        }
        String domain = value.substring(at + 1);
        int dot = domain.indexOf('.');
        return dot > 0 && dot < domain.length() - 1;
    }

    /** Normalises a phone number to digits only, keeping a leading +. */
    public static String normalisePhone(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            } else if (c == '+' && sb.length() == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Clamp a percentage into 0..100. */
    public static int clampPercent(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 100) {
            return 100;
        }
        return value;
    }
}
