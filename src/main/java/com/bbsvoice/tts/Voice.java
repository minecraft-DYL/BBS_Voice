package com.bbsvoice.tts;

/**
 * One entry of the speaker list, whichever engine renders it.
 *
 * <p>The list used to be {@link com.bbsvoice.tts.edge.EdgeVoice} alone. Character voices are not Edge
 * voices — Edge has none — and they arrive over plain HTTP instead, but the picker, the film data and
 * the language files need one vocabulary for both. Only what those three places need is here;
 * everything engine specific stays in the concrete classes.</p>
 */
public interface Voice
{
    /** Stable id: saved inside the film, used as the cache key's voice field, sent to the engine. */
    String id();

    /** Translation key of the display name, e.g. {@code bbsvoice.voice.zh-CN-XiaoxiaoNeural}. */
    String labelKey();

    /** Name shown when no language file has an entry for {@link #labelKey()}. */
    String labelFallback();
}
