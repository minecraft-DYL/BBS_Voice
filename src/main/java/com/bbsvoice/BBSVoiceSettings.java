package com.bbsvoice;

import com.bbsvoice.tts.edge.EdgeVoice;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;

/**
 * Addon settings, stored in {@code config/bbs/settings/bbsvoice.json} and editable from BBS'
 * settings screen.
 *
 * <p>There is no sample rate option any more: the engine decides the format (24 kHz mono mp3, decoded
 * to 24 kHz PCM), so offering a knob would be a lie.</p>
 */
public class BBSVoiceSettings
{
    /** Edge voice short name used for freshly created clips. */
    public static ValueString defaultVoice;

    /** Default gain applied to freshly created clips. */
    public static ValueFloat defaultVolume;

    /** Default pace applied to freshly created clips. */
    public static ValueFloat defaultSpeed;

    /** Render missing utterances in the background as soon as a clip needs them. */
    public static ValueBoolean autoRender;

    /** Play back per actor tracks with a 3D position. */
    public static ValueBoolean spatial;

    /** How many cached {@code .wav} files may stay in the cache folder. */
    public static ValueInt maxCacheFiles;

    public static void register(SettingsBuilder builder)
    {
        builder.category("bbsvoice");

        defaultVoice = builder.getString("default_voice", EdgeVoice.DEFAULT_ID);
        defaultVolume = builder.getFloat("default_volume", 1F, 0F, 4F);
        defaultSpeed = builder.getFloat("default_speed", 1F, 0.25F, 4F);
        autoRender = builder.getBoolean("auto_render", true);
        spatial = builder.getBoolean("spatial", true);
        maxCacheFiles = builder.getInt("max_cache_files", 256, 16, 4096);
    }

    /**
     * @return the configured default speaker id. It is resolved through
     *         {@link com.bbsvoice.tts.VoiceCatalog}, so it may name a character voice as well as an
     *         Edge one.
     */
    public static String defaultVoice()
    {
        return defaultVoice == null ? EdgeVoice.DEFAULT_ID : defaultVoice.get();
    }

    public static float defaultVolume()
    {
        return defaultVolume == null ? 1F : defaultVolume.get();
    }

    public static float defaultSpeed()
    {
        return defaultSpeed == null ? 1F : defaultSpeed.get();
    }

    public static boolean autoRender()
    {
        return autoRender == null || autoRender.get();
    }

    public static boolean spatial()
    {
        return spatial == null || spatial.get();
    }

    public static int maxCacheFiles()
    {
        return maxCacheFiles == null ? 256 : Math.max(16, maxCacheFiles.get());
    }
}
