package com.bbsvoice.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole point of {@link ClipFitRules} is that it can be decided without a game, so the wiring in
 * {@code VoiceClipFit} stays a two line bridge. These are the cases that decisions table exists for.
 */
class ClipFitRulesTest
{
    @Test
    void convertsSecondsToTicks()
    {
        assertEquals(0, ClipFitRules.ticksFor(0F));
        assertEquals(0, ClipFitRules.ticksFor(-2F));
        assertEquals(1, ClipFitRules.ticksFor(0.001F), "a very short line still occupies one tick");
        assertEquals(20, ClipFitRules.ticksFor(1F));
        assertEquals(24, ClipFitRules.ticksFor(1.2F));
        assertEquals(48, ClipFitRules.ticksFor(2.376F), "a partial tick rounds up, never down");
    }

    @Test
    void aLineLongerThanItsClipGrowsTheClip()
    {
        assertTrue(ClipFitRules.shouldGrow(30, 48, null, false, true), "the clip must hold the line");
        assertTrue(ClipFitRules.shouldGrow(ClipFitRules.DEFAULT_TICKS, 24, null, false, true), "a fresh clip is one tick");
    }

    @Test
    void aClipThatAlreadyFitsIsLeftAlone()
    {
        assertFalse(ClipFitRules.shouldGrow(30, 30, null, false, true), "equal is not growth");
        assertFalse(ClipFitRules.shouldGrow(80, 48, null, false, true), "a clip is never shortened");
    }

    @Test
    void fitToClipOwnsTheLength()
    {
        assertFalse(ClipFitRules.shouldGrow(30, 48, null, true, true), "auto fit speeds the line up instead");
        assertFalse(ClipFitRules.shouldGrow(30, 48, 48, true, false), "playback must not fight auto fit");
    }

    @Test
    void playbackOnlyRegrowsALengthThisAddonSet()
    {
        assertTrue(ClipFitRules.shouldGrow(30, 48, 30, false, false), "the clip is still where this addon left it");
        assertFalse(ClipFitRules.shouldGrow(30, 48, null, false, false), "an old film's clips are not ours to resize");
        assertFalse(ClipFitRules.shouldGrow(20, 48, 48, false, false), "the user dragged the clip, so leave it alone");
    }
}
