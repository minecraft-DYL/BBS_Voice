package com.bbsvoice.client.voice;

import com.bbsvoice.voice.ClipFitRules;
import com.bbsvoice.voice.VoiceData;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.utils.clips.Clip;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Sizes a voice clip to the line that was rendered into it.
 *
 * <p>Why this exists, and the rules it applies, are documented on {@link ClipFitRules}; this class is
 * only the bridge to BBS: it walks from the voice data up to the owning clip and writes the length.
 * Both clip flavours go through it, so a film level line and a per actor line behave the same.</p>
 *
 * <p>Clips are tracked weakly, so the map never keeps a film alive after it is closed.</p>
 */
public final class VoiceClipFit
{
    /** The length this addon gave each clip, or absent when it never sized that clip. */
    private static final Map<VoiceData, Integer> APPLIED = Collections.synchronizedMap(new WeakHashMap<>());

    private VoiceClipFit()
    {}

    /** A fresh render landed for this line: size its clip to the line the user just wrote. */
    public static boolean onRendered(VoiceData data, int ticks)
    {
        return fit(data, ticks, true);
    }

    /** A recording was just attached to this clip: size the clip to the take. */
    public static boolean onAudioAttached(VoiceData data)
    {
        return fit(data, VoiceAudioCache.audioTicks(data), true);
    }

    /** Called while the clip plays: keeps a clip this addon sized in step with its line. */
    public static void fitIfOurs(VoiceData data)
    {
        fit(data, VoiceAudioCache.audioTicks(data), false);
    }

    /** Forgets what was applied (used by tests, and when a clip is duplicated into a new one). */
    public static void forget(VoiceData data)
    {
        APPLIED.remove(data);
    }

    private static boolean fit(VoiceData data, int ticks, boolean authoring)
    {
        if (data == null || ticks <= 0)
        {
            return false;
        }

        Clip clip = clipOf(data);

        if (clip == null)
        {
            return false;
        }

        int current = clip.duration.get();

        if (!ClipFitRules.shouldGrow(current, ticks, APPLIED.get(data), data.autoFit.get(), authoring))
        {
            return false;
        }

        clip.duration.set(ticks);
        APPLIED.put(data, ticks);

        return true;
    }

    /** @return the clip this voice belongs to, or null when it is not attached to one yet. */
    private static Clip clipOf(VoiceData data)
    {
        BaseValue parent = data.getParent();

        while (parent != null && !(parent instanceof Clip))
        {
            parent = parent.getParent();
        }

        return (Clip) parent;
    }
}
