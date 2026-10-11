package com.bbsvoice;

import com.bbsvoice.voice.VoiceActionClip;
import com.bbsvoice.voice.VoiceClip;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.events.BBSAddonMod;
import mchorse.bbs_mod.events.Subscribe;
import mchorse.bbs_mod.events.register.RegisterSettingsEvent;
import mchorse.bbs_mod.events.register.RegisterSourcePacksEvent;
import mchorse.bbs_mod.resources.packs.InternalAssetsSourcePack;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import net.fabricmc.api.ModInitializer;

/**
 * Common (client + server) entrypoint.
 *
 * <p>Two things happen here, both through BBS' public addon API — there are no mixins:</p>
 *
 * <ul>
 *   <li>{@link RegisterSourcePacksEvent}: the mod jar gets its own asset source pack so that the
 *       strings and the icon can be loaded through BBS' provider.</li>
 *   <li>{@link RegisterSettingsEvent}: BBS has already built the camera and action clip factories
 *       at this point, so the voice clips can simply be registered into them. That gives us a film
 *       level track (camera timeline) and a per actor track (every entity's replay).</li>
 * </ul>
 */
public class BBSVoiceMod implements ModInitializer, BBSAddonMod
{
    @Override
    public void onInitialize()
    {}

    @Subscribe
    public void onRegisterSourcePacks(RegisterSourcePacksEvent event)
    {
        event.provider.register(new InternalAssetsSourcePack(BBSVoice.MOD_ID, "assets/" + BBSVoice.MOD_ID, BBSVoiceMod.class));
    }

    @Subscribe
    public void onRegisterSettings(RegisterSettingsEvent event)
    {
        ClipFactoryData data = new ClipFactoryData(Icons.VOICE, BBSVoice.COLOR);

        BBSMod.getFactoryCameraClips().register(BBSVoice.VOICE_CLIP, VoiceClip.class, data);
        BBSMod.getFactoryActionClips().register(BBSVoice.VOICE_CLIP, VoiceActionClip.class, data);

        event.register(Icons.VOICE, BBSVoice.MOD_ID, BBSVoiceSettings::register);
    }
}
