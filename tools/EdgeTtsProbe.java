import com.bbsvoice.tts.WavFile;
import com.bbsvoice.tts.edge.EdgeTtsBackend;
import com.bbsvoice.tts.edge.EdgeTtsClient;
import com.bbsvoice.tts.edge.EdgeVoice;
import com.bbsvoice.tts.speech.ProsodyPlanner;
import com.bbsvoice.tts.speech.ProsodySpan;
import com.bbsvoice.tts.speech.SpeechRequest;
import com.bbsvoice.tts.speech.SsmlBuilder;
import com.bbsvoice.tts.speech.TtsProsody;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a line through the real Edge pipeline without starting Minecraft.
 *
 * <pre>
 *   javac -encoding UTF-8 -d build/probe -cp "libs/jlayer-1.0.1.4.jar" src/main/java/com/bbsvoice/tts/**&#47;*.java tools/EdgeTtsProbe.java
 *   java -Dstdout.encoding=UTF-8 -cp "build/probe;libs/jlayer-1.0.1.4.jar" EdgeTtsProbe "你好，世界！" build/probe
 * </pre>
 *
 * <p>It drives {@link EdgeTtsBackend} — the same class the mod renders with — so everything except the
 * Minecraft glue is exercised: websocket handshake, SSML, mp3 decode, wav write, validation.</p>
 *
 * <p>Three modes, and the default one matters most: it builds the prosody plan exactly the way
 * {@code VoiceData} does (split at punctuation, sample the curves across the line) and therefore sends
 * <em>several</em> {@code <prosody>} elements in one document. That is the shape the mod always uses
 * and the one that has to be proven against the live service; {@code --single} sends one span, and
 * {@code --matrix} moves one SSML attribute at a time so "does this attribute actually do anything" is
 * answered with numbers instead of a guess.</p>
 *
 * <p>Usage: {@code EdgeTtsProbe [--matrix|--single] [--voice name] <text> <outDir>}</p>
 *
 * <p>{@code --dump <file.mp3>} is the odd one out: it writes the raw mp3 the service sends, before any
 * decoding, which is how the offline decoder fixture ({@code src/test/resources/edge/*.mp3}) is
 * captured. Re-capture it only when the service changes its container, and re-check the test's numbers
 * when you do.</p>
 */
public class EdgeTtsProbe
{
    public static void main(String[] args) throws Exception
    {
        List<String> free = new ArrayList<>();
        String voice = EdgeVoice.DEFAULT_ID;
        String dump = null;
        boolean matrix = false;
        boolean single = false;

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
                case "--matrix" -> matrix = true;
                case "--single" -> single = true;
                case "--voice" -> voice = args[++i];
                case "--dump" -> dump = args[++i];
                default -> free.add(args[i]);
            }
        }

        if (free.size() < 2)
        {
            System.err.println("usage: EdgeTtsProbe [--matrix|--single] [--voice name] [--dump file.mp3] <text> <outDir>");
            System.exit(2);
        }

        String text = free.get(0);
        File dir = new File(free.get(1));

        Files.createDirectories(dir.toPath());

        if (dump != null)
        {
            EdgeVoice selected = EdgeVoice.byId(voice);
            String ssml = SsmlBuilder.build(selected.id, selected.locale, List.of(new ProsodySpan(text, 1F, 1F, 1F)));
            byte[] mp3 = EdgeTtsClient.synthesize(ssml, EdgeTtsBackend.TIMEOUT);

            Files.write(new File(dump).toPath(), mp3);

            System.out.println("mode     : dump raw mp3 (no decoding)");
            System.out.println("voice    : " + selected.id);
            System.out.println("ssml     : " + ssml);
            System.out.println("mp3      : " + dump + "  " + mp3.length + " bytes");

            return;
        }

        if (matrix)
        {
            matrix(text, dir, voice);
            return;
        }

        if (single)
        {
            List<ProsodySpan> spans = List.of(new ProsodySpan(text, 1F, 1F, 1F));
            Render render = render(spans, voice, new File(dir, "single.wav"));

            System.out.println("mode     : single span");
            System.out.println("voice    : " + voice);
            System.out.println("ssml     : " + render.ssml);
            System.out.println(render.describe());

            return;
        }

        /* The production shape: clauses, each sampling the curves at its own position in the line. */
        List<ProsodySpan> spans = ProsodyPlanner.plan(text,
            (t) -> 0.85F + 0.30F * t,
            (t) -> 1.10F - 0.20F * t,
            (t) -> 0.70F + 0.50F * t);
        Render render = render(spans, voice, new File(dir, "plan.wav"));

        System.out.println("mode     : prosody plan, " + spans.size() + " spans");
        System.out.println("voice    : " + voice);

        for (ProsodySpan span : spans)
        {
            System.out.printf("  span   : rate=%-5s pitch=%-6s vol=%-6s %s%n",
                TtsProsody.rate(span.speed), TtsProsody.pitch(span.pitch), TtsProsody.volume(span.volume), span.text);
        }

        System.out.println("ssml     : " + render.ssml);
        System.out.println(render.describe());
    }

    /**
     * One attribute at a time, so the printed table shows what each SSML attribute is worth.
     *
     * <p>Expectations, which is what makes the table readable rather than just numbers: a rate of 1.5
     * should cut the duration to roughly two thirds while leaving the level alone; a volume of +80%
     * should lift the RMS and leave the duration alone; a pitch of +20% should leave both alone (only
     * the fundamental moves, which this probe does not measure).</p>
     */
    private static void matrix(String text, File dir, String voice) throws Exception
    {
        Object[][] cases = new Object[][] {
            { "baseline",      1F,   1F,   1F   },
            { "rate 1.5",      1.5F, 1F,   1F   },
            { "rate 0.7",      0.7F, 1F,   1F   },
            { "pitch +20%",    1F,   1.2F, 1F   },
            { "volume +80%",   1F,   1F,   1.8F },
            { "volume -60%",   1F,   1F,   0.4F },
        };

        System.out.println(String.format("%-12s %-30s %8s %8s %8s",
            "case", "ssml attributes", "sec", "peak", "rms dB"));

        for (Object[] entry : cases)
        {
            String name = (String) entry[0];
            float rate = (Float) entry[1];
            float pitch = (Float) entry[2];
            float volume = (Float) entry[3];
            List<ProsodySpan> spans = List.of(new ProsodySpan(text, rate, pitch, volume));
            Render render = render(spans, voice, new File(dir, "matrix_" + name.replace(' ', '_').replace('+', 'p').replace('-', 'm') + ".wav"));

            System.out.println(String.format("%-12s %-30s %8.3f %8d %8.1f",
                name, render.attributes(), render.seconds(), render.peak, render.rmsDb));
        }
    }

    private static Render render(List<ProsodySpan> spans, String voice, File wav) throws Exception
    {
        EdgeVoice selected = EdgeVoice.byId(voice);

        /* Rebuilt for printing; the backend builds the identical string from the same spans. */
        String ssml = SsmlBuilder.build(selected.id, selected.locale, spans);

        try
        {
            new EdgeTtsBackend().render(new SpeechRequest(selected.id, spans), wav);
        }
        catch (Exception e)
        {
            /* A document the service rejects is the one thing worth printing verbatim. */
            System.err.println("REJECTED DOCUMENT:\n" + ssml);

            throw e;
        }

        return new Render(spans, wav, ssml);
    }

    /** One render's inputs and the measurements taken from the wav that was actually written. */
    private static final class Render
    {
        final List<ProsodySpan> spans;
        final File wav;
        final String ssml;
        final int sampleRate;
        final short[] samples;
        final int peak;
        final double rmsDb;

        Render(List<ProsodySpan> spans, File wav, String ssml) throws Exception
        {
            this.spans = spans;
            this.wav = wav;
            this.ssml = ssml;

            byte[] bytes = Files.readAllBytes(wav.toPath());

            this.sampleRate = (bytes[24] & 0xff) | ((bytes[25] & 0xff) << 8) | ((bytes[26] & 0xff) << 16) | ((bytes[27] & 0xff) << 24);
            this.samples = WavFile.read(wav);

            double sum = 0D;
            int max = 0;

            for (short sample : this.samples)
            {
                sum += (double) sample * sample;
                max = Math.max(max, Math.abs((int) sample));
            }

            this.peak = max;
            this.rmsDb = this.samples.length == 0 ? -999D
                : 20D * Math.log10(Math.sqrt(sum / this.samples.length) / 32768D);
        }

        float seconds()
        {
            return this.sampleRate <= 0 ? 0F : (float) this.samples.length / (float) this.sampleRate;
        }

        String attributes()
        {
            ProsodySpan span = this.spans.get(0);

            return "rate=" + TtsProsody.rate(span.speed) + " pitch=" + TtsProsody.pitch(span.pitch)
                + " vol=" + TtsProsody.volume(span.volume);
        }

        /** The SSML is rebuilt for printing only; the render above already went through the backend. */
        String describe()
        {
            return String.format("wav      : %s  %d bytes%n", this.wav, this.wav.length())
                + String.format("decoded  : %.3fs  %d Hz  %d samples  peak=%d  rms=%.1f dBFS",
                    seconds(), this.sampleRate, this.samples.length, this.peak, this.rmsDb);
        }
    }
}
