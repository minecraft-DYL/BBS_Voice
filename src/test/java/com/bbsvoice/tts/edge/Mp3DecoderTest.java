package com.bbsvoice.tts.edge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes a real mp3 body that the Edge read-aloud service sent, kept as a fixture in
 * {@code src/test/resources/edge/hello.mp3} (11664 bytes, "你好，世界。" as {@code zh-CN-XiaoxiaoNeural}).
 *
 * <p>Why a captured blob instead of a synthesised tone: the whole mp3 step exists because the service
 * refuses every format BBS could read directly, so the interesting question is not "does JLayer work"
 * but "does our glue still decode <em>what the service actually sends</em>". A hand-made fixture would
 * answer the first and not the second - and this test runs in {@code gradle test}, with no network,
 * while a service-side container change is exactly the kind of thing that otherwise only shows up as a
 * broken line in someone's film.</p>
 *
 * <p>To re-capture (only needed if the service's container changes - the test's numbers are then
 * re-checked by hand):
 * {@code java -cp build/probe;libs/jlayer-1.0.1.4.jar EdgeTtsProbe --dump src/test/resources/edge/hello.mp3 "你好，世界。" build/probe}</p>
 */
class Mp3DecoderTest
{
    private static byte[] fixture() throws IOException
    {
        try (InputStream in = Mp3DecoderTest.class.getResourceAsStream("/edge/hello.mp3"))
        {
            assertNotNull(in, "the mp3 fixture is missing from the test resources");

            return in.readAllBytes();
        }
    }

    @Test
    void theFixtureIsRealMpegAudio() throws IOException
    {
        byte[] mp3 = fixture();

        assertTrue(mp3.length > 2000, "the captured stream is suspiciously small: " + mp3.length + " bytes");
        assertTrue(hasFrameSync(mp3), "no MPEG frame sync in the first 4 KiB, so this is not an mp3 stream");
    }

    /**
     * Looks for an MPEG audio frame header (11 set bits) rather than demanding one at byte 0, so a
     * future capture that happens to carry tag bytes at the front still passes this check.
     */
    private static boolean hasFrameSync(byte[] mp3)
    {
        for (int i = 0; i + 1 < Math.min(mp3.length, 4096); i++)
        {
            if ((mp3[i] & 0xFF) == 0xFF && (mp3[i + 1] & 0xE0) == 0xE0)
            {
                return true;
            }
        }

        return false;
    }

    @Test
    void decodesToTheFormatTheEnginePromises() throws IOException
    {
        Mp3Decoder.Pcm pcm = Mp3Decoder.decode(fixture());

        /*
         * 24 kHz is not a style choice: BBS' WaveReader plays whatever rate the header declares, the
         * playback code computes its position from the duration, and the self test asserts the same
         * number, so a silent change here would desynchronise voice from film.
         */
        assertEquals(24000, pcm.sampleRate, "the pipeline is calibrated for the service's 24 kHz output");

        float duration = pcm.durationSeconds();

        assertTrue(duration > 1F && duration < 4F, "a two second line decoded to " + duration + "s");
    }

    @Test
    void decodesAudibleAudioRatherThanSilence() throws IOException
    {
        Mp3Decoder.Pcm pcm = Mp3Decoder.decode(fixture());

        int peak = 0;
        double sum = 0D;

        for (short sample : pcm.samples)
        {
            peak = Math.max(peak, Math.abs((int) sample));
            sum += (double) sample * sample;
        }

        double rmsDb = 20D * Math.log10(Math.sqrt(sum / pcm.samples.length) / 32768D);

        System.out.printf("fixture decoded: %.3fs, %d samples, peak=%d, rms=%.1f dBFS%n",
            pcm.durationSeconds(), pcm.samples.length, peak, rmsDb);

        assertTrue(peak > 2000, "peak " + peak + " is too quiet to be speech (" + rmsDb + " dBFS)");
        assertTrue(peak < 32767, "peak " + peak + " is clipped");
        assertTrue(rmsDb > -45D && rmsDb < -8D, "level " + rmsDb + " dBFS is not a plausible speech level");
    }

    @Test
    void decodingIsDeterministic() throws IOException
    {
        byte[] mp3 = fixture();

        /* The voice cache is keyed on the request, so the same bytes must always give the same audio. */
        assertArrayEquals(Mp3Decoder.decode(mp3).samples, Mp3Decoder.decode(mp3).samples);
    }

    @Test
    void garbageIsReportedAsAnIoFailureRatherThanAnEmptyRender()
    {
        assertThrows(IOException.class, () -> Mp3Decoder.decode(new byte[] { 1, 2, 3, 4 }));
        assertThrows(IOException.class, () -> Mp3Decoder.decode(null));
    }
}
