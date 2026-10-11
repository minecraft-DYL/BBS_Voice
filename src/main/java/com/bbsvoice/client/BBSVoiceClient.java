package com.bbsvoice.client;

import com.bbsvoice.BBSVoice;
import com.bbsvoice.client.ui.UIVoiceActionClip;
import com.bbsvoice.client.ui.UIVoiceClip;
import com.bbsvoice.client.voice.VoiceActionClientClip;
import com.bbsvoice.client.voice.VoiceClientClip;
import com.bbsvoice.voice.VoiceActionClip;
import com.bbsvoice.voice.VoiceClip;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.clips.ClipFactoryData;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import net.fabricmc.api.ClientModInitializer;

import java.util.Collections;

/**
 * Client entrypoint.
 *
 * <p>Fabric runs every {@code main} entrypoint before any {@code client} entrypoint, which means the
 * clip factories already exist here (BBS builds them during its own common init). So the client
 * flavoured clip classes and their editor panels can be registered with plain public API calls —
 * no mixins, no access wideners.</p>
 */
public class BBSVoiceClient implements ClientModInitializer
{
    @Override
    public void onInitializeClient()
    {
        ClipFactoryData data = new ClipFactoryData(Icons.VOICE, BBSVoice.COLOR);

        /* Replace the common clip classes with the ones that can actually play audio. */
        BBSMod.getFactoryCameraClips().register(BBSVoice.VOICE_CLIP, VoiceClientClip.class, data);
        BBSMod.getFactoryActionClips().register(BBSVoice.VOICE_CLIP, VoiceActionClientClip.class, data);

        /* Editor panels for both the common and the client flavoured classes. */
        UIClip.register(VoiceClip.class, UIVoiceClip::new);
        UIClip.register(VoiceClientClip.class, UIVoiceClip::new);
        UIClip.register(VoiceActionClip.class, UIVoiceActionClip::new);
        UIClip.register(VoiceActionClientClip.class, UIVoiceActionClip::new);

        /* BBS reloads its strings before addons get a chance to add theirs, so register and reload. */
        L10n l10n = BBSModClient.getL10n();

        if (l10n != null)
        {
            l10n.register((lang) -> Collections.singletonList(new Link(BBSVoice.MOD_ID, "strings/" + lang + ".json")));
            l10n.reload();
        }

        /* Only does anything with -Dbbsvoice.selftest=1. */
        BBSVoiceSelfTest.register();
    }
}
