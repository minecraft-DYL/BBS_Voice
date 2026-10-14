package com.bbsvoice.tts;

import com.bbsvoice.tts.edge.EdgeVoice;
import com.bbsvoice.tts.external.ExternalVoice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one speaker list: the Edge voices plus the character voices, in the order the picker shows them
 * (Mandarin, dialects, Cantonese, Taiwan, the multilingual ones, then the characters at the end).
 *
 * <p>The catalog exists because two engines now answer to one list. Keeping the order here rather than
 * in the menu means a film's choice, a language file and the render dispatch all agree on what "the
 * speaker" is; keeping the lookup forgiving means a film saved with an id that no longer exists still
 * plays as something instead of going silent.</p>
 */
public final class VoiceCatalog
{
    private static final List<Voice> ALL = build();
    private static final Map<String, Voice> BY_ID = index();

    private VoiceCatalog()
    {}

    /** Every selectable speaker. Immutable, and the picker's order. */
    public static List<Voice> all()
    {
        return ALL;
    }

    /**
     * @param id a voice id, possibly from an older version of the mod
     * @return the matching voice, or {@link EdgeVoice#defaultVoice()} when nothing matches (never null)
     */
    public static Voice byId(String id)
    {
        Voice voice = id == null ? null : BY_ID.get(id);

        return voice == null ? EdgeVoice.defaultVoice() : voice;
    }

    public static boolean exists(String id)
    {
        return id != null && BY_ID.containsKey(id);
    }

    /**
     * @return whether that id is rendered by the external service rather than by Edge. The render
     *         cache dispatches on this, so a character voice never ends up in the Edge handshake.
     */
    public static boolean isExternal(String id)
    {
        return ExternalVoice.isExternal(id);
    }

    private static List<Voice> build()
    {
        List<Voice> voices = new ArrayList<>(EdgeVoice.all().length + ExternalVoice.all().size());

        Collections.addAll(voices, EdgeVoice.all());
        voices.addAll(ExternalVoice.all());

        return List.copyOf(voices);
    }

    private static Map<String, Voice> index()
    {
        Map<String, Voice> byId = new LinkedHashMap<>();

        for (Voice voice : ALL)
        {
            byId.put(voice.id(), voice);
        }

        return Collections.unmodifiableMap(byId);
    }
}
