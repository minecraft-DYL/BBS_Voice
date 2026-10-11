package com.bbsvoice.client.ui;

import com.bbsvoice.voice.VoiceActionClip;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;

/**
 * Editor panel of the per actor voice clip (replay action track).
 */
public class UIVoiceActionClip extends UIVoiceClipBase<VoiceActionClip>
{
    public UIVoiceActionClip(VoiceActionClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }
}
