package com.bbsvoice.voice;

import com.bbsvoice.BBSVoiceSettings;
import mchorse.bbs_mod.actions.types.ActionClip;
import mchorse.bbs_mod.utils.clips.Clip;

/**
 * Per-actor voice-over track ("演员级人声轨道").
 *
 * <p>BBS stores per-actor clips in {@code Replay.actions}, whose factory is the action clip
 * factory. By registering this clip there every actor gets its own voice track in the replay
 * editor. On the client side {@code VoiceActionClientClip} replaces it and plays the rendered
 * voice-over from the actor's position.</p>
 */
public class VoiceActionClip extends ActionClip implements IVoiceClip
{
    public final VoiceData voice = new VoiceData("voice");

    public VoiceActionClip()
    {
        super();

        /* Actions fire once at their start tick by default; a voice-over needs every tick so the
         * volume/speed curves can be automated continuously. */
        this.frequency.set(1);

        this.add(this.voice);
        this.voice.setVoice(BBSVoiceSettings.defaultEdgeVoice());
    }

    @Override
    public VoiceData getVoiceData()
    {
        return this.voice;
    }

    @Override
    protected Clip create()
    {
        return new VoiceActionClip();
    }
}
