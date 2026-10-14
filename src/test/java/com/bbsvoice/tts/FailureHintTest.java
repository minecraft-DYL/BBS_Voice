package com.bbsvoice.tts;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The classification is checked against the exact messages the two backends throw, copied out of
 * {@code EdgeTtsClient} / {@code EdgeTtsBackend} / {@code ExternalTtsBackend}. That is the point: the
 * hints are only as good as their match on those strings, so rewording a message must break a test
 * here rather than quietly turn every failure into "something went wrong".
 */
class FailureHintTest
{
    @Test
    void aRateLimitIsNotReportedAsAnAbuseRefusal()
    {
        /* EdgeTtsClient.describe() of a refused upgrade, after the 4 backoff retries. */
        String edge = "could not open the Edge speech connection: HTTP 429 - the Edge speech service refused the websocket upgrade";
        /* ExternalTtsBackend.get() after its own retries. */
        String external = "api.milorapart.top answered HTTP 429";

        assertEquals(FailureHint.RATE_LIMITED, FailureHint.classify(false, edge));
        assertEquals(FailureHint.RATE_LIMITED, FailureHint.classify(true, external));
    }

    @Test
    void anAbuseRefusalIsReportedAsSuch()
    {
        assertEquals(FailureHint.BLOCKED,
            FailureHint.classify(false, "could not open the Edge speech connection: HTTP 403 - the Edge speech service refused the websocket upgrade"));
        assertEquals(FailureHint.BLOCKED, FailureHint.classify(false, "host answered HTTP 401"));
    }

    @Test
    void aServerErrorIsTheirProblemNotTheUsers()
    {
        assertEquals(FailureHint.SERVICE_DOWN, FailureHint.classify(true, "api.milorapart.top answered HTTP 502"));
        assertEquals(FailureHint.SERVICE_DOWN, FailureHint.classify(false, "HTTP 503 - the Edge speech service refused the websocket upgrade"));
    }

    @Test
    void offlineAndTimeoutsStayApart()
    {
        assertEquals(FailureHint.OFFLINE,
            FailureHint.classify(false, "could not open the Edge speech connection: java.net.UnknownHostException: api.msedgeservices.com: Name or service not known"));
        assertEquals(FailureHint.OFFLINE, FailureHint.classify(true, "java.net.ConnectException: Connection refused"));
        assertEquals(FailureHint.TIMEOUT, FailureHint.classify(false, "Edge speech timed out after 45s"));
    }

    @Test
    void aVoiceThatCannotReadTheLineSaysSo()
    {
        /* What the Edge data channel sends when the speaker does not fit the document. */
        assertEquals(FailureHint.UNSUPPORTED_LINE,
            FailureHint.classify(false, "Edge speech failed: No audio was received. Please verify that your parameters are correct."));
        assertEquals(FailureHint.UNSUPPORTED_LINE, FailureHint.classify(false, "Edge speech closed the connection (status 1007)"));
    }

    @Test
    void emptyAudioAndDecodeFailuresAreSeparated()
    {
        assertEquals(FailureHint.EMPTY_AUDIO, FailureHint.classify(false, "Edge speech streamed no audio in 4 frames"));
        assertEquals(FailureHint.EMPTY_AUDIO, FailureHint.classify(true, "the service returned silence for 0 samples"));
        assertEquals(FailureHint.EMPTY_AUDIO, FailureHint.classify(false, "the mp3 stream contained no decodable frames"));
        assertEquals(FailureHint.LOCAL_AUDIO, FailureHint.classify(false, "could not decode the mp3 stream: java.io.EOFException"));
        assertEquals(FailureHint.LOCAL_AUDIO, FailureHint.classify(false, "not a wav: OggS"));
    }

    @Test
    void aMovedThirdPartyAnswerIsNotBlamedOnTheNetwork()
    {
        assertEquals(FailureHint.ODD_ANSWER, FailureHint.classify(true, "neither audio nor a url in the answer: <html>moved</html>"));
        assertEquals(FailureHint.ODD_ANSWER, FailureHint.classify(true, "the audio url answered with something that is not audio"));
    }

    @Test
    void anUnrecognisableThirdPartyFailureStillAdvisesSwitchingVoices()
    {
        assertEquals(FailureHint.EXTERNAL_DOWN, FailureHint.classify(true, "the mirror said something nobody has seen before"));
        /* ...but for Edge there is nothing useful to add, so the raw message is shown instead. */
        assertEquals(FailureHint.UNKNOWN, FailureHint.classify(false, "the mirror said something nobody has seen before"));
        assertEquals(FailureHint.UNKNOWN, FailureHint.classify(false, null));
        assertEquals(FailureHint.UNKNOWN, FailureHint.classify(false, "   "));
    }

    @Test
    void everyHintIsNamedInEveryLanguageFile()
    {
        for (String language : new String[] { "zh_cn", "en_us" })
        {
            Set<String> keys = hintKeys(language);

            for (FailureHint hint : FailureHint.values())
            {
                assertTrue(keys.contains(hint.labelKey()), hint.labelKey() + " is missing from " + language + ".json");
            }
        }
    }

    @Test
    void noLanguageFileNamesAHintThatNoLongerExists()
    {
        for (String language : new String[] { "zh_cn", "en_us" })
        {
            for (String key : hintKeys(language))
            {
                boolean known = false;

                for (FailureHint hint : FailureHint.values())
                {
                    known |= hint.labelKey().equals(key);
                }

                assertTrue(known, key + " is a stale key in " + language + ".json");
            }
        }
    }

    /** @return every {@code bbsvoice.hint.*} key in the given language file. */
    private static Set<String> hintKeys(String language)
    {
        String path = "/assets/bbsvoice/strings/" + language + ".json";

        try (InputStream in = FailureHintTest.class.getResourceAsStream(path))
        {
            assertNotNull(in, path + " is not on the classpath");

            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("\"(bbsvoice\\.hint\\.[^\"]+)\"").matcher(json);
            Set<String> keys = new HashSet<>();

            while (matcher.find())
            {
                keys.add(matcher.group(1));
            }

            assertFalse(keys.isEmpty(), "no hint keys found in " + path);

            return keys;
        }
        catch (Exception e)
        {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
