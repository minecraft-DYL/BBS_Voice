package com.bbsvoice.tts.edge;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The voice list is the one place where a wrong string is not a display bug but a request the service
 * refuses, so it is tested together with the language files that name the voices.
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
    void everyVoiceIsNamedInEveryLanguageFile()
    {
        for (String language : new String[] { "zh_cn", "en_us" })
        {
            Set<String> keys = voiceKeys(language);

            for (EdgeVoice voice : EdgeVoice.all())
            {
                assertTrue(keys.contains(voice.labelKey), voice.labelKey + " is missing from " + language + ".json");
                assertFalse(voice.labelFallback.isBlank(), voice.id + " has no fallback label");
            }
        }
    }

    @Test
    void noLanguageFileNamesAVoiceThatNoLongerExists()
    {
        /* The mirror of the test above: a stale key means a renamed or removed voice was left behind. */
        List<String> stale = new ArrayList<>();

        for (String language : new String[] { "zh_cn", "en_us" })
        {
            for (String key : voiceKeys(language))
            {
                String id = key.substring("bbsvoice.voice.".length());

                if (!EdgeVoice.exists(id) && !stale.contains(language + ":" + id))
                {
                    stale.add(language + ":" + id);
                }
            }
        }

        assertEquals(List.of(), stale, "stale voice translation keys");
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

    /** @return every {@code bbsvoice.voice.*} key in the given language file. */
    private static Set<String> voiceKeys(String language)
    {
        String path = "/assets/bbsvoice/strings/" + language + ".json";

        try (InputStream in = EdgeVoiceTest.class.getResourceAsStream(path))
        {
            assertNotNull(in, path + " is not on the classpath");

            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("\"(bbsvoice\\.voice\\.[^\"]+)\"").matcher(json);
            Set<String> keys = new HashSet<>();

            while (matcher.find())
            {
                keys.add(matcher.group(1));
            }

            assertFalse(keys.isEmpty(), "no voice keys found in " + path);

            return keys;
        }
        catch (Exception e)
        {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
