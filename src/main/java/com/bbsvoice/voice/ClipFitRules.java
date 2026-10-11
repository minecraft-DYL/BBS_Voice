package com.bbsvoice.voice;

/**
 * The decision table behind "the clip is as long as the line".
 *
 * <p>A voice clip is a timeline clip, and a timeline clip that is shorter than its audio simply cuts
 * the audio off: the moment the playhead leaves the clip, playback is stopped. That is the right
 * behaviour for a clip, and the wrong experience for a freshly typed line - BBS hands a new clip its
 * own default length ({@code editor.duration}, 30 ticks on a default install, 1 tick in the model
 * itself), which has nothing to do with how long the sentence turns out to be. The first in-game hand
 * test of the EdgeTTS engine heard a cut off "hello" for exactly that reason.</p>
 *
 * <p>So the line sizes the clip. The rules are deliberately conservative, because silently rewriting
 * the user's timeline is worse than a cut line:</p>
 *
 * <ul>
 *   <li>a clip is <b>never shortened</b> - only a line that does not fit grows its clip;</li>
 *   <li><b>fit-to-clip wins</b>: with "auto fit" on, the clip's length is what the user asked for and
 *       the line is sped up to fit inside it, so the clip is left alone;</li>
 *   <li>while <b>editing</b> (a new render just landed for this clip) the clip follows the new line;</li>
 *   <li>during <b>playback</b> only a length this addon set itself is adjusted, so a clip the user
 *       dragged is never fought over;</li>
 *   <li>opening an old film never resizes anything: playback fitting runs only for clips this session
 *       already sized, and a resize needs a render event.</li>
 * </ul>
 *
 * <p>Deliberately Minecraft and BBS free so the table can be unit tested without a game; the glue that
 * walks to the owning clip and writes the value is {@code com.bbsvoice.client.voice.VoiceClipFit}.</p>
 */
public final class ClipFitRules
{
    /** BBS films run at 20 ticks per second. */
    public static final float TICKS_PER_SECOND = 20F;

    /** The length of a clip straight out of BBS' clip constructor; anything above this was set by hand. */
    public static final int DEFAULT_TICKS = 1;

    private ClipFitRules()
    {}

    /** @return how many ticks a line of this length occupies, or 0 when there is no audio at all. */
    public static int ticksFor(float seconds)
    {
        return seconds <= 0F ? 0 : Math.max(1, (int) Math.ceil(seconds * TICKS_PER_SECOND));
    }

    /**
     * @param current   the clip's length right now, in ticks
     * @param wanted    the length the rendered line needs, in ticks
     * @param applied   the length this addon last set on this clip, or null when it never sized it
     * @param autoFit   whether the clip speeds the line up instead of cutting it
     * @param authoring true while the user is editing the line, false during playback
     */
    public static boolean shouldGrow(int current, int wanted, Integer applied, boolean autoFit, boolean authoring)
    {
        if (autoFit || wanted <= current)
        {
            return false;
        }

        return authoring || (applied != null && current == applied);
    }
}
