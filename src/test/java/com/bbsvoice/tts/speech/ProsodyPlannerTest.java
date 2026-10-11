package com.bbsvoice.tts.speech;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProsodyPlannerTest
{
    /** A curve that just echoes its input, so the sample position is directly visible. */
    private static final ProsodyPlanner.Curve RAMP = (t) -> t;

    @Test
    void splitsAtChinesePunctuation()
    {
        List<ProsodySpan> spans = ProsodyPlanner.plan("你好，世界！");

        assertEquals(2, spans.size());
        /* The punctuation stays attached, so the engine still takes its pause there. */
        assertEquals("你好，", spans.get(0).text);
        assertEquals("世界！", spans.get(1).text);
    }

    @Test
    void splitsAtAsciiPunctuationToo()
    {
        /* Users paste either width; both have to work. */
        assertEquals(2, ProsodyPlanner.plan("a, b.").size());
        assertEquals(2, ProsodyPlanner.plan("a; b:").size());
    }

    @Test
    void losesNoText()
    {
        String text = "你好，世界！这是一段测试。";
        String joined = ProsodyPlanner.plan(text).stream().map((span) -> span.text).collect(Collectors.joining());

        assertEquals(text, joined);
    }

    @Test
    void samplesCurvesByCharacterPosition()
    {
        /*
         * "ab，" starts at character 0 of 5, so it reads the curve at t=0; "cd" starts at 3 of 5, so
         * t=0.6. This is the rule that keeps curve editing from shifting later clauses around.
         */
        List<ProsodySpan> spans = ProsodyPlanner.plan("ab，cd", RAMP, RAMP, RAMP);

        assertEquals(2, spans.size());
        assertEquals(0F, spans.get(0).speed, 1.0E-4F);
        assertEquals(0.6F, spans.get(1).speed, 1.0E-4F);
    }

    @Test
    void aSingleClauseReadsTheStartOfTheCurve()
    {
        List<ProsodySpan> spans = ProsodyPlanner.plan("你好", RAMP, RAMP, RAMP);

        assertEquals(1, spans.size());
        assertEquals(0F, spans.get(0).speed, 1.0E-4F);
    }

    @Test
    void eachAxisIsSampledIndependently()
    {
        ProsodyPlanner.Curve flat = (t) -> 1F;
        List<ProsodySpan> spans = ProsodyPlanner.plan("ab，cd", RAMP, flat, (t) -> 2F * t);

        assertEquals(0.6F, spans.get(1).speed, 1.0E-4F);
        assertEquals(1F, spans.get(1).pitch, 1.0E-4F);
        assertEquals(1.2F, spans.get(1).volume, 1.0E-4F);
    }

    @Test
    void noAutomationLeavesEverythingAtUnity()
    {
        for (ProsodySpan span : ProsodyPlanner.plan("你好，世界！这是一段测试。"))
        {
            assertEquals(1F, span.speed, 1.0E-4F);
            assertEquals(1F, span.pitch, 1.0E-4F);
            assertEquals(1F, span.volume, 1.0E-4F);
        }
    }

    @Test
    void emptyInputProducesNoSpans()
    {
        assertTrue(ProsodyPlanner.plan(null).isEmpty());
        assertTrue(ProsodyPlanner.plan("").isEmpty());
        /* Whitespace alone would otherwise become a silent span with its own prosody. */
        assertTrue(ProsodyPlanner.plan("   ").isEmpty());
    }

    @Test
    void trailingPunctuationDoesNotCreateAnEmptySpan()
    {
        List<ProsodySpan> spans = ProsodyPlanner.plan("你好，");

        assertEquals(1, spans.size());
        assertEquals("你好，", spans.get(0).text);
    }

    /*
     * The service refuses a third prosody element (see SsmlBuilder.MAX_SPANS), so everything below is
     * about the plan always fitting into two - and about the merge not quietly dropping text to get
     * there, which is the failure that would be hardest to notice: the line would simply come out
     * shorter than what the user typed.
     */

    @Test
    void moreThanTwoClausesStillFitInTwoGroups()
    {
        List<ProsodySpan> spans = ProsodyPlanner.plan("一，二，三，四。");

        assertEquals(SsmlBuilder.MAX_SPANS, spans.size());
        assertEquals("一，二，", spans.get(0).text);
        assertEquals("三，四。", spans.get(1).text);
    }

    @Test
    void mergingKeepsEveryClauseAndItsOrder()
    {
        String text = "第一句，第二句！第三句？第四句；第五句：第六句。";
        List<ProsodySpan> spans = ProsodyPlanner.plan(text);

        assertEquals(text, spans.stream().map((span) -> span.text).collect(Collectors.joining()));
    }

    @Test
    void neverExceedsTwoSpansNoMatterHowManyClauses()
    {
        for (int clauses = 1; clauses <= 12; clauses++)
        {
            StringBuilder text = new StringBuilder();

            for (int i = 0; i < clauses; i++)
            {
                text.append("句").append(i).append(i == clauses - 1 ? "。" : "，");
            }

            List<ProsodySpan> spans = ProsodyPlanner.plan(text.toString(), RAMP, RAMP, RAMP);

            assertTrue(spans.size() <= SsmlBuilder.MAX_SPANS, clauses + " clauses -> " + spans.size() + " spans");
            assertEquals(text.toString(), spans.stream().map((span) -> span.text).collect(Collectors.joining()));
        }
    }

    @Test
    void mergingBalancesTheGroupsByHowMuchTextTheySpeak()
    {
        /*
         * A group samples the curve once, at its own start, and applies that value to everything it
         * holds - so a group holding a fifth of the line and one holding four fifths would leave the
         * curve badly unrepresented. "句子一，句子二，句子三，句。" is 4+4+4+2 characters: the short
         * tail merges into the third clause first, and the two equal pairs then merge, giving 8 and 6
         * rather than 4 and 10.
         */
        List<ProsodySpan> spans = ProsodyPlanner.plan("句子一，句子二，句子三，句。", RAMP, RAMP, RAMP);

        assertEquals(2, spans.size());
        assertEquals(8, spans.get(0).text.length());
        assertEquals(6, spans.get(1).text.length());
    }

    @Test
    void aMergedGroupSamplesTheCurveWhereItStarts()
    {
        /*
         * "一，二，三，四。" is 8 characters and merges into two groups of four, so the second group
         * starts at character 4 and reads the curve at 4/8. The sample point is the group's own start,
         * the same rule a single clause already followed.
         */
        List<ProsodySpan> spans = ProsodyPlanner.plan("一，二，三，四。", RAMP, RAMP, RAMP);

        assertEquals(0F, spans.get(0).speed, 1.0E-4F);
        assertEquals(0.5F, spans.get(1).speed, 1.0E-4F);
    }

    @Test
    void punctuationOnlyTextNeverBecomesABlankSpan()
    {
        /* A line of punctuation has nothing to say, but it must still fit the two element budget. */
        List<ProsodySpan> spans = ProsodyPlanner.plan("，。！");

        assertTrue(spans.size() <= SsmlBuilder.MAX_SPANS, spans.size() + " spans");

        for (ProsodySpan span : spans)
        {
            assertTrue(!span.text.isBlank(), "blank span: '" + span.text + "'");
        }
    }
}
