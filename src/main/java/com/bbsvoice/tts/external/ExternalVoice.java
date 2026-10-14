package com.bbsvoice.tts.external;

import com.bbsvoice.tts.Voice;

import java.util.List;

/**
 * A character voice rendered by a third party HTTP service rather than by Edge.
 *
 * <p>Edge lists 322 voices and not one of them is a character. The voices that word means — 曼波 and
 * the rest of the 必剪/剪映 crowd — are either proprietary services with no API at all, or local models
 * that need a modern GPU. Both are out of reach here: a mod cannot ask its users for a graphics card,
 * and reverse engineering a closed service is not something to ship.</p>
 *
 * <p>What is left, and what this class models, is the openly reachable HTTP services that already
 * speak those voices. An entry is an id, a name and a list of URL templates; all request and response
 * handling lives in {@link ExternalTtsBackend}. More than one template means fallbacks, tried in
 * order — a free service that gets popular starts rate limiting, and it moves.</p>
 */
public final class ExternalVoice implements Voice
{
    /** Where the spoken line goes in a template. */
    public static final String TEXT = "{text}";

    /** Every external id starts with this, so an id can never be mistaken for an Edge voice. */
    public static final String PREFIX = "ext:";

    /**
     * 曼波 — the voice this whole feature was asked for, and the only public one found.
     *
     * <p>Checked live on 2026-10-14: {@code GET .../mbAIsc?format=wav&text=...} answers
     * {@code {"code":200,"url":"https://media.milorapart.top/voice/<hash>.wav"}}, and that wav is real
     * speech (48 kHz, mono, 16 bit, peak at 74% of full scale, 3.4s for a 12 character line). No key,
     * no account, no cookie. The second template is the mirror the community client carries as a
     * fallback.</p>
     */
    public static final ExternalVoice MANBO = new ExternalVoice("ext:manbo", "曼波 · 角色配音",
        "https://api.milorapart.top/apis/mbAIsc?format=wav&text=" + TEXT,
        "https://apis.uctb.cn/api/mbAIsc?format=wav&text=" + TEXT);

    private static final List<ExternalVoice> VOICES = List.of(MANBO);

    public final String id;
    public final String labelKey;
    public final String labelFallback;

    /** URL templates in fallback order; each contains {@link #TEXT}. */
    public final List<String> endpoints;

    private ExternalVoice(String id, String labelFallback, String... endpoints)
    {
        this.id = id;
        this.labelKey = "bbsvoice.voice." + id;
        this.labelFallback = labelFallback;
        this.endpoints = List.of(endpoints);
    }

    public static List<ExternalVoice> all()
    {
        return VOICES;
    }

    /**
     * @param id the id stored in the film
     * @return the matching voice, or {@link #MANBO} when the id names nothing — the same forgiving
     *         behaviour {@link com.bbsvoice.tts.edge.EdgeVoice#byId(String)} has, so removing a voice
     *         from this list cannot turn an old film silent
     */
    public static ExternalVoice byId(String id)
    {
        for (ExternalVoice voice : VOICES)
        {
            if (voice.id.equals(id))
            {
                return voice;
            }
        }

        return MANBO;
    }

    public static boolean isExternal(String id)
    {
        return id != null && id.startsWith(PREFIX);
    }

    @Override
    public String id()
    {
        return this.id;
    }

    @Override
    public String labelKey()
    {
        return this.labelKey;
    }

    @Override
    public String labelFallback()
    {
        return this.labelFallback;
    }
}
