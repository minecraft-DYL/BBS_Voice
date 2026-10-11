package com.bbsvoice.tts.edge;

import com.bbsvoice.tts.WavFile;
import com.bbsvoice.tts.speech.SpeechBackend;
import com.bbsvoice.tts.speech.SpeechRequest;
import com.bbsvoice.tts.speech.SsmlBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The mod's only speech engine: renders an utterance with the Edge read-aloud service and writes it
 * out as the 16 bit PCM wav BBS can load.
 *
 * <p>One implementation behind the {@link SpeechBackend} seam (rather than a direct call) so the
 * cache/playback side can be tested with a fake that never touches the network — important here
 * because every other test would otherwise depend on a live third party.</p>
 *
 * <p>A broken render must never leave a file behind: the voice cache treats any file longer than a
 * wav header as a finished render, so a truncated or silent result would be cached as "ready" and
 * never retried. Everything is therefore validated before this method returns.</p>
 */
public final class EdgeTtsBackend implements SpeechBackend
{
    /** Name this engine is identified by in the cache key and in log lines. */
    public static final String ENGINE = "edge-tts";

    /**
     * Budget for one line. Measured warm renders land around 1s; the generous ceiling is for a cold
     * TLS handshake on a slow link, and because a stuck request must not hold the render queue.
     */
    public static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Override
    public String name()
    {
        return ENGINE;
    }

    @Override
    public void render(SpeechRequest request, File out) throws IOException
    {
        if (request == null || request.spans.isEmpty())
        {
            throw new IOException("nothing to say");
        }

        EdgeVoice voice = EdgeVoice.byId(request.voice);
        String ssml = SsmlBuilder.build(voice.id, voice.locale, request.spans);
        byte[] mp3 = EdgeTtsClient.synthesize(ssml, TIMEOUT);
        Mp3Decoder.Pcm pcm = Mp3Decoder.decode(mp3);

        if (peak(pcm.samples) == 0)
        {
            throw new IOException("Edge speech returned silence for " + pcm.durationSeconds() + "s");
        }

        WavFile.write(out, pcm.samples, pcm.sampleRate);
        validate(out);
    }

    private static int peak(short[] samples)
    {
        int peak = 0;

        for (short sample : samples)
        {
            int value = Math.abs((int) sample);

            if (value > peak)
            {
                peak = value;
            }
        }

        return peak;
    }

    /** Rejects a truncated or non-wav result instead of caching it. */
    private static void validate(File out) throws IOException
    {
        if (!out.isFile() || out.length() <= 44)
        {
            throw new IOException("wrote " + (out.isFile() ? out.length() : 0) + " bytes");
        }

        byte[] head = new byte[12];

        try (InputStream in = new FileInputStream(out))
        {
            if (in.read(head) != head.length)
            {
                throw new IOException("truncated wav header");
            }
        }

        String riff = new String(head, 0, 4, StandardCharsets.US_ASCII);
        String wave = new String(head, 8, 4, StandardCharsets.US_ASCII);

        if (!"RIFF".equals(riff) || !"WAVE".equals(wave))
        {
            throw new IOException("not a wav: " + riff + "/" + wave);
        }
    }
}
