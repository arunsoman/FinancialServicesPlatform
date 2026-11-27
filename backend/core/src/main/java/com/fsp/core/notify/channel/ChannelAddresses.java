package com.fsp.core.notify.channel;

import java.util.regex.Pattern;

/**
 * Contact normalisation/validation for the derived email/SMS targets (V29). Targets come
 * from the person profile — never from the wire — so malformed contacts simply make a
 * person unreachable on that channel (via = NONE), never an API error.
 */
public final class ChannelAddresses {

    private ChannelAddresses() {}

    /** ITU E.164: +[country code][subscriber number], 8–15 total digits (no '+' digit). */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    /** Normalise a human-entered phone to E.164: strip separators/spaces, restore '+'. */
    public static String normaliseE164(String raw) {
        if (raw == null) return null;
        String compact = raw.replaceAll("[\\s()\\-.]", "");
        if (compact.isEmpty()) return null;
        // leading 00 international prefix → '+'
        if (compact.startsWith("00")) compact = "+" + compact.substring(2);
        if (!compact.startsWith("+")) return null; // profile must store international format
        return E164.matcher(compact).matches() ? compact : null;
    }

    /** Email must round-trip through the profile; a blank/whitespace-only value is not a target. */
    public static boolean isPlausibleEmail(String email) {
        return email != null && email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    }
}
