package com.bbsvoice.voice;

import com.bbsvoice.BBSVoiceSettings;
import mchorse.bbs_mod.camera.clips.CameraClip;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;

/**
 * Film level voice-over track ("影片级人声轨道").
 *
 * <p>It sits on the same timeline as {@code bbs:audio} and {@code bbs:subtitle} clips. The base
 * class deliberately does nothing during playback; all audio work happens in
 * {@code com.bbsvoice.client.voice.VoiceClientClip} which replaces this class in the client side
 * clip factory (the exact same trick BBS itself uses for its own audio clip).</p>
 */
public class VoiceClip extends CameraClip implements IVoiceClip
{
    public final VoiceData voice = new VoiceData("voice");

    public VoiceClip()
    {
        super();

        this.add(this.voice);
        this.voice.setVoice(BBSVoiceSettings.defaultEdgeVoice());
    }

    @Override
    public VoiceData getVoiceData()
    {
        return this.voice;
    }

    @Override
    protected void applyClip(ClipContext context, Position position)
    {}

    @Override
    protected Clip create()
    {
        return new VoiceClip();
    }
}
