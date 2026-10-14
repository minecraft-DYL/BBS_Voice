package com.bbsvoice.tts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resampler exists so two services that disagree about a sample rate still produce cache files of
 * one shape. Its failure mode is silent (audio that plays at the wrong speed), so the lengths and the
 * interpolated values are asserted rather than just "it did not throw".
 */
class ResamplerTest
{
    @Test
    void halvingTheRateHalvesTheSamples()
    {
        short[] in = new short[] { 0, 100, 200, 300 };

        assertArrayEquals(new short[] { 0, 200 }, Resampler.to(in, 48000, 24000));
    }

    @Test
    void matchingRatesAreLeftAlone()
    {
        short[] in = new short[] { 1, 2, 3 };

        assertSame(in, Resampler.to(in, 24000, 24000));
    }

    @Test
    void upsamplingProducesTheLongerBuffer()
    {
        short[] in = new short[] { 0, 1000 };

        short[] out = Resampler.to(in, 24000, 48000);

        assertEquals(4, out.length);
        assertEquals(0, out[0]);
        assertTrue(out[3] >= out[0]);
    }

    @Test
    void degenerateInputIsNotAnError()
    {
        assertEquals(0, Resampler.to(new short[0], 48000, 24000).length);
        assertSame(Resampler.to(new short[] { 5 }, 0, 24000).length, 1);
    }

    @Test
    void aSingleSampleSurvivesADownConversion()
    {
        short[] out = Resampler.to(new short[] { 1234 }, 44100, 24000);

        assertEquals(1, out.length);
        assertEquals(1234, out[0]);
    }
}
