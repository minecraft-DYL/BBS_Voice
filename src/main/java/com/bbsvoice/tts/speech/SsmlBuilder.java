package com.bbsvoice.tts.speech;

import java.util.List;

/**
 * Renders a prosody plan into the SSML document handed to the Edge read-aloud service.
 *
 * <p>Kept as plain string building on purpose: it is the piece most likely to silently produce invalid
 * markup — a bad document is answered with a close frame rather than a Java exception — so it stays
 * free of any Minecraft or network dependency and is covered by unit tests.</p>
 *
 * <p>Every span carries all three prosody attributes. That is not decoration: the engine's own
 * {@code <prosody>} handling is what turns the automation curves into intonation, and a span with only
 * one attribute would make the other two fall back to the document default instead of the curve.</p>
 */
public final class SsmlBuilder
{
    /** Language of the document; the voice element below is what actually picks the speaker. */
    public static final String DEFAULT_LANG = "zh-CN";

    /**
     * Most {@code <prosody>} elements the Edge service accepts in one document.
     *
     * <p>Measured, not documented anywhere by Microsoft: one and two elements render; three, four,
     * five and six are all answered with {@code 1007 SSML is invalid}, even when every element carries
     * identical attributes and the text is one character per element. Nesting them, or adding a
     * {@code <break>}, is refused as well.</p>
     *
     * <p>{@link ProsodyPlanner} merges clauses down to this many groups, so a plan that reaches this
     * method is always valid. Exceeding it here is a programming error and is reported as one, rather
     * than being sent to the service to come back as a cryptic websocket close.</p>
     */
    public static final int MAX_SPANS = 2;

    private SsmlBuilder()
    {}

    /**
     * @param voice  Edge voice short name, e.g. {@code zh-CN-XiaoxiaoNeural}
     * @param locale the voice's locale, e.g. {@code zh-CN}
     * @param spans  the utterance, in order; an empty plan yields a document that speaks nothing
     * @throws IllegalArgumentException when there are more than {@link #MAX_SPANS} spans
     */
    public static String build(String voice, String locale, List<ProsodySpan> spans)
    {
        if (spans.size() > MAX_SPANS)
        {
            throw new IllegalArgumentException("the Edge service rejects more than " + MAX_SPANS
                + " prosody elements in one document, got " + spans.size() + " (merge the plan with ProsodyPlanner)");
        }

        StringBuilder out = new StringBuilder(256);

        out.append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='");
        out.append(escapeAttribute(locale == null || locale.isEmpty() ? DEFAULT_LANG : locale));
        out.append("'><voice name='");
        out.append(escapeAttribute(voice));
        out.append("'>");

        for (ProsodySpan span : spans)
        {
            out.append("<prosody rate='");
            out.append(TtsProsody.rate(span.speed));
            out.append("' pitch='");
            out.append(TtsProsody.pitch(span.pitch));
            out.append("' volume='");
            out.append(TtsProsody.volume(span.volume));
            out.append("'>");
            out.append(escapeText(span.text));
            out.append("</prosody>");
        }

        out.append("</voice></speak>");

        return out.toString();
    }

    /** Convenience overload for a zh-CN voice. */
    public static String build(String voice, List<ProsodySpan> spans)
    {
        return build(voice, DEFAULT_LANG, spans);
    }

    /** Escapes the three characters that would break out of element content. */
    public static String escapeText(String text)
    {
        if (text == null)
        {
            return "";
        }

        StringBuilder out = new StringBuilder(text.length() + 8);

        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);

            switch (c)
            {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }

        return out.toString();
    }

    /** Escapes the two characters that would break out of a single-quoted attribute. */
    public static String escapeAttribute(String text)
    {
        if (text == null)
        {
            return "";
        }

        StringBuilder out = new StringBuilder(text.length() + 8);

        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);

            switch (c)
            {
                case '&' -> out.append("&amp;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }

        return out.toString();
    }
}
