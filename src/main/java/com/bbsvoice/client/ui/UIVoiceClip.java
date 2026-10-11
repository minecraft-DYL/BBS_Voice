package com.bbsvoice.client.ui;

import com.bbsvoice.voice.VoiceClip;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;

/**
 * Editor panel of the film level voice clip.
 */
public class UIVoiceClip extends UIVoiceClipBase<VoiceClip>
{
    public UIVoiceClip(VoiceClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }
}
