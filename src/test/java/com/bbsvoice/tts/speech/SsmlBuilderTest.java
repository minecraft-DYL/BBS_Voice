package com.bbsvoice.tts.speech;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Invalid markup is the failure mode this class exists to prevent, so escaping is the focus. */
class SsmlBuilderTest
{
    private static ProsodySpan span(String text)
    {
        return new ProsodySpan(text, 1F, 1F, 1F);
    }

    @Test
    void everySpanCarriesAllThreeProsodyAttributes()
    {
        /*
         * All three are always present even at their defaults: a lone attribute is what the previous
         * (SAPI) engine mis-handled, and keeping the element shape uniform means a value can never be
         * "missing" from one span and present in the next.
         */
        String ssml = SsmlBuilder.build("zh-CN-XiaoxiaoNeural", List.of(span("hi")));

        assertTrue(ssml.contains("rate='1'"), ssml);
        assertTrue(ssml.contains("pitch='+0%'"), ssml);
        assertTrue(ssml.contains("volume='+0%'"), ssml);
    }

    @Test
    void textCannotBreakOutOfItsElement()
    {
        String ssml = SsmlBuilder.build("V", List.of(span("a<b>&c")));

        assertTrue(ssml.contains("a&lt;b&gt;&amp;c"), ssml);
        assertFalse(ssml.contains("a<b>"), ssml);
    }

    @Test
    void theVoiceNameCannotBreakOutOfItsAttribute()
    {
        String ssml = SsmlBuilder.build("O'Brien & Co", List.of(span("x")));

        assertTrue(ssml.contains("name='O&apos;Brien &amp; Co'"), ssml);
    }

    @Test
    void spansStayInOrderAndEachBecomesItsOwnElement()
    {
        String ssml = SsmlBuilder.build("V", List.of(span("one"), span("two")));

        assertTrue(ssml.indexOf("one") < ssml.indexOf("two"));
        assertEquals(2, countOf(ssml, "<prosody"));
        assertEquals(2, countOf(ssml, "</prosody>"));
    }

    @Test
    void aThirdSpanIsRefusedRatherThanSentToTheService()
    {
        /*
         * Measured against the live service: one and two prosody elements render, and every count from
         * three up is answered with 1007 "SSML is invalid" - even when the elements are identical and
         * hold one character each. Failing here turns that into a compile-time-shaped error at the
         * caller (ProsodyPlanner merges down to two) instead of a mystery websocket close.
         */
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
            () -> SsmlBuilder.build("V", List.of(span("one"), span("two"), span("three"))));

        assertTrue(thrown.getMessage().contains("prosody"), thrown.getMessage());
    }

    @Test
    void aPlanWithNoSpansIsStillValidMarkup()
    {
        String ssml = SsmlBuilder.build("V", List.of());

        assertTrue(ssml.startsWith("<speak"));
        assertTrue(ssml.endsWith("</voice></speak>"));
        assertEquals(0, countOf(ssml, "<prosody"));
    }

    @Test
    void theDocumentDeclaresTheNamespaceAndLanguage()
    {
        String ssml = SsmlBuilder.build("zh-CN-XiaoxiaoNeural", "zh-CN", List.of(span("你好")));

        assertTrue(ssml.contains("version='1.0'"), ssml);
        assertTrue(ssml.contains("xmlns='http://www.w3.org/2001/10/synthesis'"), ssml);
        assertTrue(ssml.contains("xml:lang='zh-CN'"), ssml);
        assertTrue(ssml.contains("name='zh-CN-XiaoxiaoNeural'"), ssml);
    }

    @Test
    void allowsSmallWordsAndParagraphs()  // sensible text passes through untouched
    {
        String ssml = SsmlBuilder.build("V", List.of(span("你好，世界！")));

        assertTrue(ssml.contains("你好，世界！"), ssml);
    }

    private static int countOf(String haystack, String needle)
    {
        int count = 0;
        int at = 0;

        while ((at = haystack.indexOf(needle, at)) >= 0)
        {
            count++;
            at += needle.length();
        }

        return count;
    }
}
