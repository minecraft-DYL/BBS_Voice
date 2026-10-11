package com.bbsvoice.tts.edge;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The {@code Sec-MS-GEC} handshake token the Edge read-aloud endpoint demands.
 *
 * <p>Without it the websocket upgrade comes back {@code 403 Forbidden}. The token is the uppercase
 * SHA-256 of the current Windows file time (in 100 ns ticks) floored to a five minute boundary,
 * concatenated with the public trusted client token.</p>
 *
 * <p>The arithmetic is deliberately done in {@code double} and printed from the {@code double}, not
 * from an exact {@code long}: the ticks value is around 1.3e17, far beyond the 2^53 a double can hold
 * exactly, so the service must be computing the string the same lossy way. Reproducing the reference
 * client bit for bit is what makes the token match; "correcting" it to exact integer arithmetic is
 * the one change that breaks it. The five minute floor is what makes it work at all: both sides land
 * on the same bucket even with a clock skew of minutes.</p>
 */
public final class SecMsGec
{
    /** Public token every Edge/Chromium client carries. Not a secret. */
    public static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";

    /** Chromium build the endpoint currently accepts. A stale value is rejected alongside a bad token. */
    public static final String CHROMIUM_VERSION = "143.0.3650.75";

    /** Seconds between the Windows file time epoch (1601) and the unix epoch. */
    private static final double WINDOWS_EPOCH_OFFSET_SECONDS = 11644473600D;

    /** The token is floored to this many seconds. */
    private static final double BUCKET_SECONDS = 300D;

    /** 100 ns ticks per second. */
    private static final double TICKS_PER_SECOND = 1.0E7D;

    private SecMsGec()
    {}

    public static String generate()
    {
        return generate(System.currentTimeMillis());
    }

    static String generate(long epochMillis)
    {
        return tokenFor(epochMillis);
    }

    /** Visible for the unit test, which pins a timestamp and compares against the reference output. */
    public static String tokenFor(long epochMillis)
    {
        double ticks = epochMillis / 1000D + WINDOWS_EPOCH_OFFSET_SECONDS;

        ticks -= ticks % BUCKET_SECONDS;
        ticks *= TICKS_PER_SECOND;

        return sha256HexUpper(BigDecimal.valueOf(ticks).setScale(0, RoundingMode.HALF_UP).toPlainString() + TRUSTED_CLIENT_TOKEN);
    }

    private static String sha256HexUpper(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            StringBuilder out = new StringBuilder(digest.length * 2);

            for (byte b : digest)
            {
                out.append(Character.forDigit((b >> 4) & 0xf, 16));
                out.append(Character.forDigit(b & 0xf, 16));
            }

            return out.toString().toUpperCase(java.util.Locale.ROOT);
        }
        catch (NoSuchAlgorithmException e)
        {
            /* SHA-256 is required of every Java platform. */
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
