package io.github.tvoverlaysuite;

import java.security.SecureRandom;

/** Pairing codes: 6 characters from an alphabet without look-alikes (no 0/O, 1/I/L). Plain Java. */
public final class Pairing {
    private Pairing() { }

    public static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int LENGTH = 6;
    private static final SecureRandom RNG = new SecureRandom();

    public static String newCode() {
        char[] c = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) c[i] = ALPHABET.charAt(RNG.nextInt(ALPHABET.length()));
        return new String(c);
    }

    public static boolean valid(String code) {
        if (code == null || code.length() != LENGTH) return false;
        for (int i = 0; i < LENGTH; i++) if (ALPHABET.indexOf(code.charAt(i)) < 0) return false;
        return true;
    }

    /** "ABC123" -> "ABC 123" for reading aloud / typing. */
    public static String pretty(String code) {
        return code == null || code.length() != LENGTH ? "" : code.substring(0, 3) + " " + code.substring(3);
    }
}
