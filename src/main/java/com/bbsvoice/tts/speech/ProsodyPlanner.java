package com.bbsvoice.tts.speech;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one line of text plus three curves into the prosody spans handed to the speech engine.
 *
 * <p><b>Alignment rule</b> (chosen deliberately): clauses are cut at punctuation, and each span
 * samples the curves at its own character position within the text - a span starting at character
 * {@code c} of {@code n} samples the curve at {@code c / n}. The rule is intentionally independent of
 * how long the clause takes to speak, because the two obvious alternatives are both worse: aligning
 * by measured duration would require rendering twice, and would also feed back on itself, since the
 * speech rate that the curve controls is what decides the durations.</p>
 *
 * <p>Punctuation is kept at the end of its span so the engine still produces the pause.</p>
 *
 * <p><b>Why spans get merged</b> — the Edge service accepts <em>at most two</em> {@code <prosody>}
 * elements in one document and rejects any third outright ({@code 1007 SSML is invalid}), measured:
 * one or two elements render, three to six are all refused, even when every attribute is identical and
 * the text is a single character per span. Nesting and {@code <break>} are refused too. A line with
 * three or more clauses therefore cannot be rendered one element per clause; adjacent clauses are
 * merged into at most {@link SsmlBuilder#MAX_SPANS} contiguous groups instead. This is exact for the
 * common case (a line of one or two clauses is emitted unchanged) and degrades to a two step ramp of
 * the curves for longer lines - the alternative was silently sending a document the service always
 * rejects. See {@code docs/superpowers/specs/2026-10-11-edgetts-engine-design.md} for the raw
 * measurements.</p>
 */
public final class ProsodyPlanner
{
    /** Samples a normalised curve; {@code t} runs from 0 at the start of the utterance to 1 at the end. */
    public interface Curve
    {
        float at(float t);

        /** A flat curve at 1, used when a curve is switched off. */
        Curve ONE = (t) -> 1F;
    }

    /** Characters that end a clause. Both full-width and ASCII forms, because users paste either. */
    private static final String CLAUSE_BREAKS = "，。！？；：、,.!?;:…";

    private ProsodyPlanner()
    {}

    /** Builds a plan with no curve automation at all - a single flat span per clause. */
    public static List<ProsodySpan> plan(String text)
    {
        return plan(text, Curve.ONE, Curve.ONE, Curve.ONE);
    }

    public static List<ProsodySpan> plan(String text, Curve speed, Curve pitch, Curve volume)
    {
        List<ProsodySpan> spans = new ArrayList<>();

        if (text == null || text.isEmpty())
        {
            return spans;
        }

        int length = text.length();
        List<int[]> clauses = clauses(text);

        for (int[] group : merge(clauses, SsmlBuilder.MAX_SPANS))
        {
            add(spans, text, group[0], group[1], length, speed, pitch, volume);
        }

        return spans;
    }

    /**
     * Clause boundaries as {@code [from, to)} ranges, a clause ending *after* its punctuation.
     *
     * <p>Whitespace-only clauses are dropped here rather than later, because a blank clause would
     * otherwise become a silent span with its own prosody - and, worse, would eat one of the two
     * groups a real clause needed.</p>
     */
    private static List<int[]> clauses(String text)
    {
        List<int[]> clauses = new ArrayList<>();
        int start = 0;

        for (int i = 0; i < text.length(); i++)
        {
            if (CLAUSE_BREAKS.indexOf(text.charAt(i)) >= 0)
            {
                if (!text.substring(start, i + 1).isBlank())
                {
                    clauses.add(new int[] { start, i + 1 });
                }

                start = i + 1;
            }
        }

        if (start < text.length() && !text.substring(start).isBlank())
        {
            clauses.add(new int[] { start, text.length() });
        }

        return clauses;
    }

    /**
     * Reduces the clauses to at most {@code max} contiguous groups.
     *
     * <p>Repeatedly merges whichever adjacent pair is currently shortest, which keeps the groups
     * balanced by how much text they have to speak - two groups of 3 and 40 characters would put the
     * single curve sample for the long half nowhere near the middle of it.</p>
     */
    private static List<int[]> merge(List<int[]> clauses, int max)
    {
        List<int[]> groups = new ArrayList<>(clauses);

        while (groups.size() > max)
        {
            int best = 0;
            int shortest = Integer.MAX_VALUE;

            for (int i = 0; i + 1 < groups.size(); i++)
            {
                int combined = length(groups.get(i)) + length(groups.get(i + 1));

                if (combined < shortest)
                {
                    shortest = combined;
                    best = i;
                }
            }

            groups.set(best, new int[] { groups.get(best)[0], groups.get(best + 1)[1] });
            groups.remove(best + 1);
        }

        return groups;
    }

    private static int length(int[] range)
    {
        return range[1] - range[0];
    }

    private static void add(List<ProsodySpan> spans, String text, int from, int to, int length,
                            Curve speed, Curve pitch, Curve volume)
    {
        if (to <= from)
        {
            return;
        }

        String slice = text.substring(from, to);

        /* Whitespace-only slices would otherwise become silent spans with their own prosody. */
        if (slice.isBlank())
        {
            return;
        }

        float t = length <= 1 ? 0F : (float) from / (float) length;

        spans.add(new ProsodySpan(slice, speed.at(t), pitch.at(t), volume.at(t)));
    }
}
