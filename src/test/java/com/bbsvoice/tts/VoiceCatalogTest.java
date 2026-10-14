package com.bbsvoice.tts;

import com.bbsvoice.tts.edge.EdgeVoice;
import com.bbsvoice.tts.external.ExternalVoice;
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
 * The catalog is what the picker shows, what a film saves and what the render dispatch reads, so its
 * two failure modes are checked here: an id that resolves to the wrong voice, and a voice that the
 * language files do not name.
 *
 * <p>The language file checks used to live in {@link com.bbsvoice.tts.edge.EdgeVoiceTest} and covered
 * Edge voices only. They are here now because the catalog is the list that has to be complete — an
 * unnamed character voice would show its raw id in the picker.</p>
 */
class VoiceCatalogTest
{
    @Test
    void everyIdIsUniqueAndResolvesToItself()
    {
        Set<String> ids = new LinkedHashSet<>();

        for (Voice voice : VoiceCatalog.all())
        {
            assertNotNull(voice.id());
            assertFalse(voice.id().isBlank());
            assertTrue(ids.add(voice.id()), "duplicate voice id: " + voice.id());
            assertSame(voice, VoiceCatalog.byId(voice.id()), voice.id() + " does not resolve to itself");
            assertTrue(VoiceCatalog.exists(voice.id()), voice.id() + " is not reachable by id");
        }
    }

    @Test
    void bothEnginesAreInTheOneList()
    {
        assertEquals(EdgeVoice.all().length + ExternalVoice.all().size(), VoiceCatalog.all().size());
        assertTrue(VoiceCatalog.all().get(0) instanceof EdgeVoice, "the Edge voices come first");
        assertTrue(VoiceCatalog.all().get(VoiceCatalog.all().size() - 1) instanceof ExternalVoice, "characters last");
    }

    @Test
    void theCharacterVoiceIsReachableAndKnowsItIsExternal()
    {
        assertEquals("ext:manbo", ExternalVoice.MANBO.id());
        assertTrue(VoiceCatalog.exists("ext:manbo"));
        assertTrue(VoiceCatalog.isExternal("ext:manbo"));
        assertFalse(VoiceCatalog.isExternal(EdgeVoice.DEFAULT_ID));
        assertFalse(VoiceCatalog.isExternal(null));
    }

    @Test
    void everyExternalEndpointCarriesTheTextPlaceholder()
    {
        /* Without it the request would go out with no line in it and the service would answer empty. */
        for (ExternalVoice voice : ExternalVoice.all())
        {
            assertFalse(voice.endpoints.isEmpty(), voice.id() + " has no endpoint");
            assertFalse(voice.labelFallback().isBlank(), voice.id() + " has no fallback label");

            for (String endpoint : voice.endpoints)
            {
                assertTrue(endpoint.contains(ExternalVoice.TEXT), endpoint + " cannot carry the text");
                assertTrue(endpoint.startsWith("https://"), endpoint + " is not https");
            }
        }
    }

    @Test
    void anUnknownIdFallsBackInsteadOfFailing()
    {
        /* How a film saved by the old engine, or one naming a removed character, stays playable. */
        assertSame(EdgeVoice.defaultVoice(), VoiceCatalog.byId("male"));
        assertSame(EdgeVoice.defaultVoice(), VoiceCatalog.byId(null));
        assertSame(EdgeVoice.defaultVoice(), VoiceCatalog.byId(""));
        assertFalse(VoiceCatalog.exists("male"));
    }

    @Test
    void everyVoiceIsNamedInEveryLanguageFile()
    {
        for (String language : new String[] { "zh_cn", "en_us" })
        {
            Set<String> keys = voiceKeys(language);

            for (Voice voice : VoiceCatalog.all())
            {
                assertTrue(keys.contains(voice.labelKey()), voice.labelKey() + " is missing from " + language + ".json");
                assertFalse(voice.labelFallback().isBlank(), voice.id() + " has no fallback label");
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

                if (!VoiceCatalog.exists(id) && !stale.contains(language + ":" + id))
                {
                    stale.add(language + ":" + id);
                }
            }
        }

        assertEquals(List.of(), stale, "stale voice translation keys");
    }

    /** @return every {@code bbsvoice.voice.*} key in the given language file. */
    private static Set<String> voiceKeys(String language)
    {
        String path = "/assets/bbsvoice/strings/" + language + ".json";

        try (InputStream in = VoiceCatalogTest.class.getResourceAsStream(path))
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
