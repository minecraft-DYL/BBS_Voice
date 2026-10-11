package com.bbsvoice.tts.speech;

import java.io.File;
import java.io.IOException;

/**
 * Produces an utterance as a wav file.
 *
 * <p>An interface rather than a direct call so the render pipeline can be exercised with a fake in
 * tests, and so the caller can fall back to a different engine when this one cannot run.</p>
 */
public interface SpeechBackend
{
    /** For logging, e.g. {@code system-speech}. */
    String name();

    /**
     * @param request what to say
     * @param out     the wav file to create; callers pass a path inside the voice cache
     * @throws IOException if the backend is unavailable or produced nothing usable
     */
    void render(SpeechRequest request, File out) throws IOException;
}
