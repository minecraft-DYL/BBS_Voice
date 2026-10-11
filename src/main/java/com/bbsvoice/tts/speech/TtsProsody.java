package com.bbsvoice.tts.speech;

import java.util.Locale;

/**
 * Converts the mod's multipliers into the SSML prosody attributes the Edge service understands.
 *
 * <p>Three deliberate choices, each of which the service is picky about:</p>
 *
 * <ul>
 *   <li><b>Rate is sent as a bare multiplier</b> ({@code rate='1.25'}), not a percentage. The value is
 *       already a multiplier and this is the form the engine's own documentation uses.</li>
 *   <li><b>Pitch is sent as a signed percentage</b> ({@code pitch='+20%'}); the service rejects a bare
 *       number here, and {@code +}/{@code -} is the relative form.</li>
 *   <li><b>Volume is sent relative</b> ({@code volume='+0%'}). The absolute form of this attribute only
 *       goes up to 100%, which is exactly "unchanged", so it cannot express a curve that pushes a line
 *       louder — and the emotion presets do exactly that.</li>
 * </ul>
 *
 * <p>The clamps are the useful range of the neural voices, and are what stops a user's 4x speed slider
 * from being baked in as gibberish: speed here only carries the <em>curve</em> (typically 0.65-1.25),
 * because the plain speed is a live playback effect, and the pitch is clamped to a deviation that
 * still sounds like emphasis rather than a different speaker.</p>
 */
public final class TtsProsody
{
    /** Slowest rate worth baking; a curve below this is clamped instead of producing sludge. */
    public static final float MIN_SPEED = 0.5F;

    /** Fastest rate worth baking. */
    public static final float MAX_SPEED = 2F;

    /** Largest pitch deviation baked; beyond this the voice stops sounding like the chosen speaker. */
    public static final float MAX_PITCH_DEVIATION = 0.5F;

    /** Loudest relative volume baked. */
    public static final float MAX_VOLUME = 2F;

    private TtsProsody()
    {}

    public static float clampSpeed(float speed)
    {
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
    }

    /** @return the {@code rate} attribute value, e.g. {@code "1.25"} */
    public static String rate(float speed)
    {
        return trim(clampSpeed(speed));
    }

    /** @return the {@code pitch} attribute value, e.g. {@code "+20%"} / {@code "-15%"} */
    public static String pitch(float pitch)
    {
        float deviation = Math.max(-MAX_PITCH_DEVIATION, Math.min(MAX_PITCH_DEVIATION, pitch - 1F));

        return signed(Math.round(deviation * 100F)) + "%";
    }

    /** @return the {@code volume} attribute value, e.g. {@code "+15%"} / {@code "-30%"} */
    public static String volume(float volume)
    {
        float clamped = Math.max(0F, Math.min(MAX_VOLUME, volume));

        return signed(Math.round((clamped - 1F) * 100F)) + "%";
    }

    private static String signed(long percent)
    {
        return (percent >= 0 ? "+" : "") + percent;
    }

    /** Trailing-zero-free decimal, so the SSML reads {@code 1.25} rather than {@code 1.25000000}. */
    private static String trim(float value)
    {
        if (value == Math.round(value))
        {
            return Long.toString(Math.round(value));
        }

        String text = String.format(Locale.ROOT, "%.3f", value);

        while (text.endsWith("0"))
        {
            text = text.substring(0, text.length() - 1);
        }

        return text;
    }
}
