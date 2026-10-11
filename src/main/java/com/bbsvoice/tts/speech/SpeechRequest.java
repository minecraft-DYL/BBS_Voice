package com.bbsvoice.tts.speech;

import java.util.List;

/**
 * Everything a speech backend needs in order to produce one utterance.
 *
 * <p>The prosody plan is built by the caller, because that is where the curves live; a backend only
 * renders. Keeping it that way means the planning logic can be unit tested without Windows, without
 * Minecraft and without an installed voice.</p>
 */
public final class SpeechRequest
{
    /** Installed system voice name, e.g. {@code Microsoft Huihui Desktop}. */
    public final String voice;

    /** The utterance, already cut into prosody spans, in order. */
    public final List<ProsodySpan> spans;

    public SpeechRequest(String voice, List<ProsodySpan> spans)
    {
        this.voice = voice;
        this.spans = spans;
    }

    /** The plain text of the whole utterance, used by the fallback synthesiser. */
    public String text()
    {
        StringBuilder out = new StringBuilder();

        for (ProsodySpan span : this.spans)
        {
            out.append(span.text);
        }

        return out.toString();
    }

    public boolean isEmpty()
    {
        return this.spans.isEmpty();
    }
}
