package com.bbsvoice.tts.edge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cache key decides whether a line is re-rendered (a network round trip) or served from disk. Both
 * kinds of mistake are invisible in game: too eager a key means the mod re-renders while the user
 * watches, too lax a key means a line plays audio that belongs to different words.
 */
class EdgeTtsRequestTest
{
    @Test
    void theSameInputsGiveTheSameKey()
    {
        assertEquals(new EdgeTtsRequest("你好", "zh-CN-YunxiNeural", "1.0").signature(),
            new EdgeTtsRequest("你好", "zh-CN-YunxiNeural", "1.0").signature());
    }

    @Test
    void everyBakedInputChangesTheKey()
    {
        String base = new EdgeTtsRequest("你好", "zh-CN-YunxiNeural", "1.0").signature();

        /* Text, speaker and baked prosody are all in the samples, so all three must invalidate. */
        assertNotEquals(base, new EdgeTtsRequest("你好啊", "zh-CN-YunxiNeural", "1.0").signature());
        assertNotEquals(base, new EdgeTtsRequest("你好", "zh-CN-XiaoxiaoNeural", "1.0").signature());
        assertNotEquals(base, new EdgeTtsRequest("你好", "zh-CN-YunxiNeural", "1.2").signature());
    }

    @Test
    void fieldsCannotBeReSplitIntoTheSameKey()
    {
        /*
         * The reason the key is length prefixed rather than joined with a separator. With a plain "|"
         * join these two collapse: voice="v", prosody="|a", text="b" and voice="v", prosody="",
         * text="a|b" both build "v||a|b" - and the user typed the "|", so it is not theoretical.
         */
        assertNotEquals(new EdgeTtsRequest("a|b", "v", "").signature(),
            new EdgeTtsRequest("b", "v", "|a").signature());
    }

    @Test
    void nullsAreTolerated()
    {
        /* A half filled clip must not throw while the user is still typing into it. */
        EdgeTtsRequest request = new EdgeTtsRequest(null, null, null);

        assertEquals("", request.text);
        assertEquals(EdgeVoice.DEFAULT_ID, request.voice);
        assertEquals("", request.prosody);
        assertEquals(new EdgeTtsRequest("", EdgeVoice.DEFAULT_ID, "").signature(), request.signature());
    }

    @Test
    void theKeyIsAShortHexDigest()
    {
        String signature = new EdgeTtsRequest("你好", EdgeVoice.DEFAULT_ID, "1.0").signature();

        assertEquals(20, signature.length(), signature);
        assertTrue(signature.matches("[0-9a-f]{20}"), signature);
    }

    @Test
    void whitespaceAndCaseAreNotNormalised()
    {
        /*
         * Deliberate: the text goes into the SSML verbatim, so "你好 " and "你好" are different requests.
         * Normalising here would mean a cache hit for audio that says something slightly different.
         */
        assertNotEquals(new EdgeTtsRequest("你好", "v", "").signature(),
            new EdgeTtsRequest("你好 ", "v", "").signature());
    }
}
