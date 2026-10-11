package com.bbsvoice.tts.speech;

import java.util.ArrayList;
import java.util.List;

/**
 * An emotion preset: a set of keyframes to stamp onto the three prosody curves.
 *
 * <p>Presets deliberately write ordinary keyframes rather than a separate hidden scale, so that after
 * picking one the user can drag the points around on BBS' own keyframe graph - which is the whole
 * point of "preset plus curve".</p>
 *
 * <p>The tick values are a normalised 0..100 span. They do not need to match the real clip length:
 * {@code ProsodyPlanner} samples every curve across its own extent, so a preset stretched onto a
 * longer timeline still shapes the whole line.</p>
 *
 * <p>Pure data, no Minecraft and no Windows, so presets can be unit tested.</p>
 */
public final class VoiceEmotion
{
    public static final String NONE = "none";

    /** Voice the system-speech backend uses by default; present on a zh-CN Windows install. */
    public static final String DEFAULT_VOICE = "Microsoft Huihui Desktop";

    private static final float[] ENDS = { 0F, 100F };

    private static final List<VoiceEmotion> ALL = new ArrayList<>();

    public final String id;
    public final String label;

    /** Keyframe ticks, shared by all three axes of this preset. */
    public final float[] ticks;

    /** Null disables (removes) that curve. */
    public final float[] speed;
    public final float[] pitch;
    public final float[] volume;

    private VoiceEmotion(String id, String label, float[] ticks, float[] speed, float[] pitch, float[] volume)
    {
        this.id = id;
        this.label = label;
        this.ticks = ticks;
        this.speed = speed;
        this.pitch = pitch;
        this.volume = volume;
    }

    public static List<VoiceEmotion> all()
    {
        return ALL;
    }

    public static VoiceEmotion byId(String id)
    {
        for (VoiceEmotion emotion : ALL)
        {
            if (emotion.id.equals(id))
            {
                return emotion;
            }
        }

        return ALL.get(0);
    }

    static
    {
        /* Leaves the curves alone - whatever the user drew stays. */
        ALL.add(new VoiceEmotion(NONE, "None", ENDS, null, null, null));

        /* Flat lines: gives the curves a visible, draggable shape. */
        ALL.add(new VoiceEmotion("calm", "Calm", ENDS, new float[] { 1F, 1F }, new float[] { 1F, 1F }, new float[] { 1F, 1F }));

        /* Pushes the middle of the line: faster, higher, louder. */
        ALL.add(new VoiceEmotion("excited", "Excited",
            new float[] { 0F, 50F, 100F },
            new float[] { 1F, 1.25F, 1F },
            new float[] { 1F, 1.20F, 1.05F },
            new float[] { 1F, 1.15F, 1F }));

        /* Overall slower, lower and quieter, with a droop at the end. */
        ALL.add(new VoiceEmotion("sad", "Sad",
            new float[] { 0F, 60F, 100F },
            new float[] { 0.85F, 0.75F, 0.65F },
            new float[] { 0.90F, 0.85F, 0.80F },
            new float[] { 0.80F, 0.70F, 0.65F }));

        /* News-reader shape: steady pace, pitch falls away at the end of the line. */
        ALL.add(new VoiceEmotion("narration", "Narration",
            new float[] { 0F, 40F, 100F },
            new float[] { 1F, 1.05F, 0.95F },
            new float[] { 1.05F, 1.0F, 0.92F },
            new float[] { 1F, 1F, 0.95F }));

        /* Slow and low, then speeding up and rising - a build. */
        ALL.add(new VoiceEmotion("suspense", "Suspense",
            new float[] { 0F, 70F, 100F },
            new float[] { 0.80F, 0.85F, 1.15F },
            new float[] { 0.95F, 1.05F, 1.20F },
            new float[] { 0.85F, 0.95F, 1.10F }));
    }
}
