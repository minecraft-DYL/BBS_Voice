package com.bbsvoice.tts.edge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Everything that decides what one rendered line sounds like, plus the cache key derived from it.
 *
 * <p>Kept free of Minecraft and BBS so the cache key can be unit tested — and it is worth testing,
 * because a wrong key is invisible: it either re-renders (and hits the network) on every play, or
 * worse, serves audio that no longer matches the text.</p>
 *
 * <p>In the key, and therefore a re-render when changed:</p>
 * <ul>
 *   <li>the text,</li>
 *   <li>the Edge voice,</li>
 *   <li>the prosody fingerprint — pitch and the three automation curves are baked into the samples by
 *       the SSML, not applied at playback, so editing them <em>must</em> invalidate the cache,</li>
 *   <li>the engine version, so that a future change to the pipeline retires the old renders.</li>
 * </ul>
 *
 * <p>Out of the key, because they are applied live during playback: gain, pace, offset, fit-to-clip.</p>
 */
public final class EdgeTtsRequest
{
    /**
     * Bump this whenever the pipeline would produce different audio for the same inputs — a new
     * output format, a different SSML shape, a decoder change. Old cache files then stop matching.
     */
    public static final String ENGINE_VERSION = "edge-tts-v1";

    /** The line to speak. */
    public final String text;

    /** Edge voice short name, e.g. {@code zh-CN-XiaoxiaoNeural}. */
    public final String voice;

    /** Compact rendering of the baked prosody plan. */
    public final String prosody;

    public EdgeTtsRequest(String text, String voice, String prosody)
    {
        this.text = text == null ? "" : text;
        this.voice = voice == null ? EdgeVoice.DEFAULT_ID : voice;
        this.prosody = prosody == null ? "" : prosody;
    }

    /** Stable cache key (SHA-1 prefix) for the rendered wav. */
    public String signature()
    {
        /*
         * Length prefixed rather than joined with a separator. A separator is ambiguous as soon as any
         * field can contain it, and the text is whatever the user typed: with a plain "|" join,
         * ("v", "", "a|b") and ("v", "|a", "b") hash to the same key, and the second render would then
         * be served for the first line. The lengths make every combination unambiguous.
         */
        StringBuilder raw = new StringBuilder(64);

        field(raw, ENGINE_VERSION);
        field(raw, this.voice);
        field(raw, this.prosody);
        field(raw, this.text);

        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(raw.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(20);

            for (int i = 0; i < 10; i++)
            {
                out.append(String.format("%02x", digest[i]));
            }

            return out.toString();
        }
        catch (Exception e)
        {
            return Integer.toHexString(raw.toString().hashCode());
        }
    }

    private static void field(StringBuilder out, String value)
    {
        out.append(value.length()).append(':').append(value).append(';');
    }
}
