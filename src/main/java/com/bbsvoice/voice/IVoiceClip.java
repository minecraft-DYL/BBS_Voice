package com.bbsvoice.voice;

/**
 * Implemented by every clip that carries voice-over data. Used by the client UI so
 * that the film-level track and the per-actor track can share one editor.
 */
public interface IVoiceClip
{
    VoiceData getVoiceData();
}
