package com.bbsvoice.tts;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a failed render should tell the user.
 *
 * <p>"合成失败" on its own is useless with a networked engine: the user cannot tell a rate limit from an
 * anti-abuse refusal from their own wifi, and they are not going to read the game log. So the two
 * backends put the HTTP status (or the transport exception) into their message, and this maps it onto
 * one short piece of advice that fits in the panel's status line. The raw message goes in the tooltip
 * for anyone who wants the detail.</p>
 *
 * <p>Deliberately pure Java - no Minecraft, no language files - so the mapping is unit tested against
 * the exact strings the backends throw. If a message is reworded, the test that pins it fails rather
 * than the hint silently degrading to "something went wrong".</p>
 */
public enum FailureHint
{
    /** The service is rating the client by IP. Retrying immediately just earns another 429. */
    RATE_LIMITED("bbsvoice.hint.rate_limited"),

    /** The service refused the request outright - the Edge endpoint's anti-abuse check, usually. */
    BLOCKED("bbsvoice.hint.blocked"),

    /** The audio came back empty: the voice cannot read this text, or the service swallowed it. */
    UNSUPPORTED_LINE("bbsvoice.hint.unsupported_line"),

    /** 5xx: their side, not the user's. */
    SERVICE_DOWN("bbsvoice.hint.service_down"),

    /** No answer in time (slow network, or something is throttling). */
    TIMEOUT("bbsvoice.hint.timeout"),

    /** DNS or TCP never got anywhere: network, proxy or firewall. */
    OFFLINE("bbsvoice.hint.offline"),

    /** Silence or an empty stream where audio was expected. */
    EMPTY_AUDIO("bbsvoice.hint.empty_audio"),

    /** Answered with neither audio nor a download url - the third-party shape moved. */
    ODD_ANSWER("bbsvoice.hint.odd_answer"),

    /** The bytes arrived but did not decode locally. */
    LOCAL_AUDIO("bbsvoice.hint.local_audio"),

    /** A third-party voice failed for a reason that is not an HTTP status. */
    EXTERNAL_DOWN("bbsvoice.hint.external_down"),

    /** Nothing matched; show the raw message and say so. */
    UNKNOWN("bbsvoice.hint.unknown");

    /** {@code HTTP 429}: how both backends write it ({@code HTTP 429 - ...}, {@code host answered HTTP 429}). */
    private static final Pattern HTTP_STATUS = Pattern.compile("http\\s+(\\d{3})");

    private final String labelKey;

    FailureHint(String labelKey)
    {
        this.labelKey = labelKey;
    }

    /** @return the translation key holding the one-line advice. */
    public String labelKey()
    {
        return this.labelKey;
    }

    /**
     * @param external whether the failing speaker is a third-party voice rather than an Edge one
     * @param reason   the message the backend threw, or {@code null} when there is none
     *
     * @return the closest advice; {@link #UNKNOWN} when nothing matches
     */
    public static FailureHint classify(boolean external, String reason)
    {
        if (reason == null || reason.isBlank())
        {
            return UNKNOWN;
        }

        String text = reason.toLowerCase(Locale.ROOT);
        int status = statusIn(text);

        /*
         * Status first: it is unambiguous, and the words around it are not. "Timed out" can appear in a
         * 429 retry log line, and a refusal message mentions the websocket, not the reason.
         */
        if (status == 429)
        {
            return RATE_LIMITED;
        }

        if (status == 401 || status == 403)
        {
            return BLOCKED;
        }

        if (status >= 500 && status <= 599)
        {
            return SERVICE_DOWN;
        }

        if (text.contains("timed out") || text.contains("timeout"))
        {
            return TIMEOUT;
        }

        if (text.contains("unknownhost") || text.contains("no such host") || text.contains("connection refused")
            || text.contains("network is unreachable") || text.contains("name or service not known")
            || text.contains("temporary failure in name resolution") || text.contains("connect exception"))
        {
            return OFFLINE;
        }

        /* Edge's own words for "this voice cannot read that": it streams the document and then closes. */
        if (text.contains("no audio was received") || text.contains("unsupported voice")
            || text.contains("please verify that your parameters") || text.contains("1007"))
        {
            return UNSUPPORTED_LINE;
        }

        if (text.contains("streamed no audio") || text.contains("no decodable frames") || text.contains("no mp3 data"))
        {
            return EMPTY_AUDIO;
        }

        if (text.contains("silence"))
        {
            return EMPTY_AUDIO;
        }

        if (text.contains("neither audio nor a url") || text.contains("not audio"))
        {
            return ODD_ANSWER;
        }

        if (text.contains("could not decode") || text.contains("not a wav") || text.contains("truncated wav")
            || text.contains("wrote ") && text.contains("bytes"))
        {
            return LOCAL_AUDIO;
        }

        /* Nothing recognisable from a third party voice is still worth a hint: switch back to Edge. */
        return external ? EXTERNAL_DOWN : UNKNOWN;
    }

    /** @return the HTTP status written into the message, or {@code -1} when it does not name one. */
    private static int statusIn(String text)
    {
        Matcher matcher = HTTP_STATUS.matcher(text);

        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }
}
