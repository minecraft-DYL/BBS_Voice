package com.bbsvoice.tts;

/**
 * Sample rate conversion, so a backend can hand over whatever rate its service sends and the cache
 * still holds one shape (24 kHz mono, the same as an Edge render).
 *
 * <p>Linear interpolation rather than a windowed sinc: the material is speech, the sources are
 * already band limited, and the alternative would put the largest piece of DSP in the mod inside a
 * class whose only job is to make two services agree about a number.</p>
 */
public final class Resampler
{
    private Resampler()
    {}

    /**
     * @param samples  mono samples
     * @param fromRate the rate those samples are at
     * @param toRate   the rate to convert to
     * @return the samples at {@code toRate}; the input array itself when the rates already match
     */
    public static short[] to(short[] samples, int fromRate, int toRate)
    {
        if (samples.length == 0 || fromRate <= 0 || toRate <= 0 || fromRate == toRate)
        {
            return samples;
        }

        int length = Math.max(1, (int) Math.round(samples.length * (double) toRate / fromRate));
        short[] out = new short[length];
        double step = (double) fromRate / toRate;

        for (int i = 0; i < length; i++)
        {
            double position = i * step;
            int index = (int) position;
            int next = Math.min(index + 1, samples.length - 1);
            double fraction = position - index;

            if (index >= samples.length)
            {
                index = samples.length - 1;
                next = index;
                fraction = 0D;
            }

            out[i] = (short) Math.round(samples[index] * (1D - fraction) + samples[next] * fraction);
        }

        return out;
    }
}
