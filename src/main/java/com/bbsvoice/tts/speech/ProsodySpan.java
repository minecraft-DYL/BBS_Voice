package com.bbsvoice.tts.speech;

/**
 * One span of text together with the prosody values that apply to it.
 *
 * <p>SAPI is given all three attributes on every span. That is not cosmetic: with {@code rate} alone
 * the engine mis-handles the element (measured - {@code rate='0%'} rendered a 5.973s baseline
 * sentence at 17.923s, while the same rate alongside pitch and volume landed on the baseline).</p>
 *
 * @param text   the slice of the utterance this span covers
 * @param speed  speech-rate multiplier, 1 = normal
 * @param pitch  pitch multiplier, 1 = normal
 * @param volume volume multiplier, 1 = normal
 */
public final class ProsodySpan
{
    public final String text;
    public final float speed;
    public final float pitch;
    public final float volume;

    public ProsodySpan(String text, float speed, float pitch, float volume)
    {
        this.text = text;
        this.speed = speed;
        this.pitch = pitch;
        this.volume = volume;
    }

    @Override
    public String toString()
    {
        return "'" + this.text + "' speed=" + this.speed + " pitch=" + this.pitch + " volume=" + this.volume;
    }
}
