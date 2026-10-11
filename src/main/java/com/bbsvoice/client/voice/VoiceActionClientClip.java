package com.bbsvoice.client.voice;

import com.bbsvoice.voice.VoiceActionClip;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.utils.clips.Clip;

/**
 * Client variant of the per actor voice clip. It lives in a replay's action track, so every actor
 * can have their own voice over line that follows them in 3D space.
 */
public class VoiceActionClientClip extends VoiceActionClip
{
    public VoiceActionClientClip()
    {
        super();
    }

    @Override
    public boolean isClient()
    {
        return true;
    }

    @Override
    protected void applyClientAction(IEntity entity, Film film, Replay replay, int tick)
    {
        /* Same as the film level clip: keep the action clip as long as its line. */
        VoiceClipFit.fitIfOurs(this.voice);

        VoicePlayback.tick(this.voice, true, tick - this.tick.get(), entity, this.duration.get());
    }

    @Override
    protected Clip create()
    {
        return new VoiceActionClientClip();
    }
}
