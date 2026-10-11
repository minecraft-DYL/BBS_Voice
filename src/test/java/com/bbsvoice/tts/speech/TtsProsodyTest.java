package com.bbsvoice.tts.speech;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * These pin the three attribute forms the Edge service expects. They were established by rendering the
 * same line with each form and measuring it (see {@code tools/EdgeTtsProbe.java}; the numbers are in
 * the design note), and they are easy to "tidy up" into something the service rejects: a percentage
 * where a bare multiplier belongs, or an unsigned where a signed value is required.
 */
class TtsProsodyTest
{
    @Test
    void speedIsClampedToTheRangeTheVoicesActuallyDo()
    {
        assertEquals(0.5F, TtsProsody.clampSpeed(0.25F), 1.0E-4F);
        assertEquals(2F, TtsProsody.clampSpeed(4F), 1.0E-4F);
        assertEquals(1.25F, TtsProsody.clampSpeed(1.25F), 1.0E-4F);
        assertEquals(0.5F, TtsProsody.clampSpeed(0.5F), 1.0E-4F);
    }

    @Test
    void rateIsABareMultiplier()
    {
        /*
         * Not "125%": rate is a multiplier attribute, and the service measures a real speed change from
         * the bare form (5.064s at 1, 3.408s at 1.5, 7.224s at 0.7).
         */
        assertEquals("1", TtsProsody.rate(1F));
        assertEquals("1.25", TtsProsody.rate(1.25F));
        assertEquals("1.5", TtsProsody.rate(1.5F));
        assertEquals("0.5", TtsProsody.rate(0.5F));
        assertEquals("2", TtsProsody.rate(2F));
    }

    @Test
    void rateIsNeverSigned()
    {
        /* Speed is already a multiplier; a sign here would be a relative change to a relative change. */
        assertFalse(TtsProsody.rate(1.8F).startsWith("+"));
        assertFalse(TtsProsody.rate(0.8F).startsWith("-"));
        assertEquals("0.8", TtsProsody.rate(0.8F));
    }

    @Test
    void rateIsClampedBeforeItIsPrinted()
    {
        assertEquals("0.5", TtsProsody.rate(0.1F));
        assertEquals("2", TtsProsody.rate(9F));
    }

    @Test
    void rateHasNoTrailingZeroes()
    {
        /* "1.250" is not what the reference client sends, and there is no reason to differ. */
        assertFalse(TtsProsody.rate(1.5F).contains(".") && TtsProsody.rate(1.5F).endsWith("0"));
        assertEquals("1.75", TtsProsody.rate(1.75F));
    }

    @Test
    void pitchIsASignedRelativePercentage()
    {
        assertEquals("+20%", TtsProsody.pitch(1.2F));
        assertEquals("-15%", TtsProsody.pitch(0.85F));
        assertEquals("+0%", TtsProsody.pitch(1F));
    }

    @Test
    void pitchDeviationIsClampedBothWays()
    {
        /* Past half an octave the chosen speaker stops being recognisable. */
        assertEquals("+50%", TtsProsody.pitch(3F));
        assertEquals("-50%", TtsProsody.pitch(0F));
        assertEquals("+50%", TtsProsody.pitch(1.5F));
    }

    @Test
    void volumeIsRelativeBecauseTheAbsoluteFormCannotGoLouder()
    {
        /*
         * volume='100%' means "unchanged" in the absolute form, so it cannot express an emotion preset
         * that pushes a line louder. Measured: volume='+80%' lifts the peak from 21785 to 30830 and
         * volume='-60%' drops it to 8508, and neither touches the duration.
         */
        assertEquals("+0%", TtsProsody.volume(1F));
        assertEquals("+80%", TtsProsody.volume(1.8F));
        assertEquals("-20%", TtsProsody.volume(0.8F));
        assertEquals("-60%", TtsProsody.volume(0.4F));
    }

    @Test
    void volumeIsClampedToSilenceAndToDouble()
    {
        assertEquals("-100%", TtsProsody.volume(-1F));
        assertEquals("-100%", TtsProsody.volume(0F));
        assertEquals("+100%", TtsProsody.volume(2F));
        assertEquals("+100%", TtsProsody.volume(5F));
    }

    @Test
    void everyFormIsSafeInsideASingleQuotedAttribute()
    {
        /* The values go into '...' attributes, so a quote or angle bracket would break the document. */
        for (float value : new float[] { 0F, 0.25F, 0.5F, 1F, 1.25F, 1.5F, 2F, 4F })
        {
            for (String attribute : new String[] { TtsProsody.rate(value), TtsProsody.pitch(value), TtsProsody.volume(value) })
            {
                assertFalse(attribute.matches(".*['\"<>&].*"), value + " -> " + attribute);
            }
        }
    }
}
