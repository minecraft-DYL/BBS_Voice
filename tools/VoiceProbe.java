import com.bbsvoice.tts.VoiceCatalog;
import com.bbsvoice.tts.WavFile;
import com.bbsvoice.tts.edge.EdgeTtsBackend;
import com.bbsvoice.tts.edge.EdgeVoice;
import com.bbsvoice.tts.external.ExternalTtsBackend;
import com.bbsvoice.tts.external.ExternalVoice;
import com.bbsvoice.tts.speech.ProsodySpan;
import com.bbsvoice.tts.speech.SpeechRequest;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Renders one Chinese line with every kind of speaker the catalog carries, without starting Minecraft.
 *
 * <pre>
 *   java -Dstdout.encoding=UTF-8 -cp "build/probe;build/classes/java/main;libs/jlayer-1.0.1.4.jar" VoiceProbe build/probe
 * </pre>
 *
 * <p>Two things need numbers rather than opinions. First: the 12 {@code *MultilingualNeural} voices are
 * documented to read any language, but that was never checked for Chinese — if they cannot, they are 12
 * dead entries in the picker and the list should not claim them. Second: the character voice goes
 * through a different backend (plain HTTP, a JSON hop, a 48 kHz answer), so "the code compiles" says
 * nothing about whether a wav comes out.</p>
 *
 * <p>The line is a literal inside this class on purpose: a Chinese argument would have to survive the
 * console's code page, and a probe measuring audio is the last place to introduce an encoding doubt.</p>
 */
public class VoiceProbe
{
    /** Short, Chinese, punctuated: a comma forces more than one prosody span. */
    private static final String LINE = "你好，世界！这是配音测试。";

    private static final String[] VOICES = new String[] {
        EdgeVoice.DEFAULT_ID,
        "en-US-AvaMultilingualNeural",
        "en-US-AndrewMultilingualNeural",
        "de-DE-FlorianMultilingualNeural",
        "fr-FR-VivienneMultilingualNeural",
        "ko-KR-HyunsuMultilingualNeural",
    };

    public static void main(String[] args) throws Exception
    {
        File dir = new File(args.length > 0 ? args[0] : "build/probe");

        Files.createDirectories(dir.toPath());

        List<ProsodySpan> spans = List.of(new ProsodySpan(LINE, 1F, 1F, 1F));

        System.out.println("line     : " + LINE);
        System.out.printf("%-34s %7s %7s %8s %7s  %s%n", "voice", "sec", "peak", "rms dB", "ms", "result");

        for (String id : VOICES)
        {
            render(new SpeechRequest(id, spans), new File(dir, "voice-" + id + ".wav"));
        }

        render(new SpeechRequest(ExternalVoice.MANBO.id(), spans), new File(dir, "voice-manbo.wav"));
    }

    private static void render(SpeechRequest request, File out)
    {
        long start = System.currentTimeMillis();
        String result;

        try
        {
            /* The same one line dispatch the render cache uses; VoiceCatalog decides, not this probe. */
            (VoiceCatalog.isExternal(request.voice) ? new ExternalTtsBackend() : new EdgeTtsBackend()).render(request, out);

            result = measure(out);
        }
        catch (Exception e)
        {
            result = "FAILED: " + e.getMessage();
        }

        System.out.printf("%-34s %s  %d ms%n", request.voice, result, System.currentTimeMillis() - start);
    }

    private static String measure(File file) throws Exception
    {
        byte[] bytes = Files.readAllBytes(file.toPath());
        short[] samples = WavFile.read(bytes);
        int rate = WavFile.sampleRate(bytes);
        double sum = 0D;
        int peak = 0;

        for (short sample : samples)
        {
            sum += (double) sample * sample;
            peak = Math.max(peak, Math.abs((int) sample));
        }

        double rms = samples.length == 0 ? -999D : 20D * Math.log10(Math.sqrt(sum / samples.length) / 32768D);
        double seconds = rate <= 0 ? 0D : (double) samples.length / rate;

        return String.format("%7.2f %7d %8.1f", seconds, peak, rms);
    }
}
