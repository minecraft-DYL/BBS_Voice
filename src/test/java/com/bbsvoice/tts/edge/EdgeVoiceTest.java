package com.bbsvoice.tts.edge;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Edge voice list is the one place where a wrong string is not a display bug but a request the
 * service refuses, so the ids themselves are asserted here.
 *
 * <p>Completeness of the <em>combined</em> list (Edge voices plus character voices, against both
 * language files) moved to {@code VoiceCatalogTest} once the catalog stopped being Edge-only.</p>
 */
class EdgeVoiceTest
{
    @Test
    void theDefaultVoiceExistsAndIsSelectable()
    {
        assertTrue(EdgeVoice.exists(EdgeVoice.DEFAULT_ID));
        assertSame(EdgeVoice.defaultVoice(), EdgeVoice.byId(EdgeVoice.DEFAULT_ID));
        assertEquals(EdgeVoice.ZH_XIAOXIAO, EdgeVoice.defaultVoice());
    }

    @Test
    void anUnknownVoiceFallsBackInsteadOfFailingTheRequest()
    {
        /*
         * This is what keeps films saved by the old engine playable: they carry preset ids like "male"
         * that no longer name anything, and a rendered request needs *some* valid voice element.
         */
        assertSame(EdgeVoice.ZH_XIAOXIAO, EdgeVoice.byId("male"));
        assertSame(EdgeVoice.ZH_XIAOXIAO, EdgeVoice.byId(null));
        assertSame(EdgeVoice.ZH_XIAOXIAO, EdgeVoice.byId(""));
        assertFalse(EdgeVoice.exists("male"));
    }

    @Test
    void idsAreUniqueAndComplete()
    {
        Set<String> ids = new LinkedHashSet<>();

        for (EdgeVoice voice : EdgeVoice.all())
        {
            assertNotNull(voice.id);
            assertFalse(voice.id.isEmpty());

            assertTrue(ids.add(voice.id), "duplicate voice id: " + voice.id);

            assertNotNull(voice.locale);
            assertTrue(voice.locale.length() >= 5, voice.id + " -> " + voice.locale);
            assertTrue(voice.isFemale() || "Male".equals(voice.gender), voice.id + " -> " + voice.gender);
        }

        assertTrue(EdgeVoice.all().length >= 10, "the service offers many more voices than this");
    }

    @Test
    void everyVoiceIdIsALocalePrefixedShortName()
    {
        /* The id is pasted straight into the SSML <voice name>, so its shape is load bearing. */
        for (EdgeVoice voice : EdgeVoice.all())
        {
            assertTrue(voice.id.matches("[a-z]{2}-[A-Za-z]{2}(-[a-z]+)?-[A-Za-z]+Neural"), voice.id);
            assertTrue(voice.id.startsWith(voice.locale.split("-")[0] + "-" + voice.locale.split("-")[1]), voice.id + " vs " + voice.locale);
        }
    }

    @Test
    void indexOfFindsEveryVoiceAndDefaultsToTheFirst()
    {
        for (int i = 0; i < EdgeVoice.all().length; i++)
        {
            assertEquals(i, EdgeVoice.indexOf(EdgeVoice.all()[i].id));
        }

        assertEquals(0, EdgeVoice.indexOf("nonsense"));
        assertEquals(0, EdgeVoice.indexOf(null));
    }

    @Test
    void theMultilingualVoicesAreInTheListAndWellaShaped()
    {
        /*
         * They are the only way to get a non-Chinese-sounding speaker for a Chinese line, and they are
         * still pasted into the SSML, so they must satisfy the same shape as every other id - which the
         * test above already asserts for the whole array. This one pins that they were added at all.
         */
        assertTrue(EdgeVoice.all().length >= 26, "the multilingual voices should be in: " + EdgeVoice.all().length);
        assertSame(EdgeVoice.EN_US_AVA_ML, EdgeVoice.byId("en-US-AvaMultilingualNeural"));
        assertSame(EdgeVoice.EN_AU_WILLIAM_ML, EdgeVoice.byId("en-AU-WilliamMultilingualNeural"));
    }
}
