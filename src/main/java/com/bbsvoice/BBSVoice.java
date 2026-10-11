package com.bbsvoice;

import mchorse.bbs_mod.resources.Link;

/**
 * Shared constants for the BBS Voice addon.
 */
public class BBSVoice
{
    public static final String MOD_ID = "bbsvoice";

    /** Clip type id used inside BBS' camera/work clip factory (film level). */
    public static final Link VOICE_CLIP = Link.create(MOD_ID + ":voice");

    /** Clip colour used in the timeline. */
    public static final int COLOR = 0x8e44ad;

    /** Folder (relative to {@code config/bbs/assets}) where rendered voice-over WAVs are cached. */
    public static final String VOICE_CACHE_FOLDER = "audio/bbsvoice";

    public static String version()
    {
        return "1.0.0";
    }
}
