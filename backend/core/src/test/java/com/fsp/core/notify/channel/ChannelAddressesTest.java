package com.fsp.core.notify.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** E.164 normalisation + email plausibility for derived channel targets (V29 §12.4). */
class ChannelAddressesTest {

    @Test
    void normalisesCommonPhoneShapesToInternationalForm() {
        assertEquals("+264611234567", ChannelAddresses.normaliseE164("+264 61 123 4567"));
        assertEquals("+264611234567", ChannelAddresses.normaliseE164("+264-61-123-4567"));
        assertEquals("+264611234567", ChannelAddresses.normaliseE164("00 264 61 123 4567"));
        assertEquals("+264611234567", ChannelAddresses.normaliseE164("+264(61)1234567"));
    }

    @Test
    void rejectsNonInternationalOrMalformedNumbers() {
        assertNull(ChannelAddresses.normaliseE164("0811234567"));       // no + → not a target
        assertNull(ChannelAddresses.normaliseE164("++264811234567"));   // malformed
        assertNull(ChannelAddresses.normaliseE164("+26481"));           // too short (7 digits)
        assertNull(ChannelAddresses.normaliseE164(""));                 // blank
        assertNull(ChannelAddresses.normaliseE164(null));
    }

    @Test
    void acceptsValidAndRejectsBrokenEmails() {
        assertTrue(ChannelAddresses.isPlausibleEmail("checker@example.com"));
        assertTrue(ChannelAddresses.isPlausibleEmail("a.b+c@sub.example.co.uk"));
        assertFalse(ChannelAddresses.isPlausibleEmail("no-at-sign"));
        assertFalse(ChannelAddresses.isPlausibleEmail("a@ b")); // whitespace
        assertFalse(ChannelAddresses.isPlausibleEmail(""));
        assertFalse(ChannelAddresses.isPlausibleEmail(null));
    }
}
