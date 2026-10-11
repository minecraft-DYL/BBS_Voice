package com.bbsvoice.client.voice;

import com.bbsvoice.voice.VoiceClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;

/**
 * Client variant of the film level voice clip. Camera clips are the ones that live on the film
 * timeline next to audio and subtitle clips.
 */
public class VoiceClientClip extends VoiceClip
{
    public VoiceClientClip()
    {
        super();
    }

    @Override
    public boolean isGlobal()
    {
        return true;
    }

    @Override
    protected void applyClip(ClipContext context, Position position)
    {
        /* Before playing: a line longer than its clip would be cut off at the clip's right edge. */
        VoiceClipFit.fitIfOurs(this.voice);

        VoicePlayback.tick(this.voice, context.playing, context.relativeTick + context.transition, null, this.duration.get());
    }

    @Override
    public void shutdown(ClipContext context)
    {
        VoicePlayback.stop(this.voice);
    }

    @Override
    protected Clip create()
    {
        return new VoiceClientClip();
    }
}
