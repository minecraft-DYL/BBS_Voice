package com.bbsvoice.tts.external;

import com.bbsvoice.tts.Resampler;
import com.bbsvoice.tts.WavFile;
import com.bbsvoice.tts.edge.Mp3Decoder;
import com.bbsvoice.tts.speech.SpeechBackend;
import com.bbsvoice.tts.speech.SpeechRequest;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a line with a third party HTTP voice service — the character voices Edge does not have.
 *
 * <p>Two answer shapes are accepted, and which one it is gets <em>sniffed</em> rather than configured:
 * either the body is the audio, or it is a small JSON document with a {@code url} field pointing at
 * it (what the 曼波 service does). Sniffing survives the services changing; a declared shape that is
 * wrong would be a support ticket for the user, and the shapes in the wild are exactly these two.</p>
 *
 * <p>The audio is normalised to 24 kHz mono 16 bit before it is written — the same shape the Edge
 * backend produces — so clip length, playback and the cache never have to ask which engine a line came
 * from. The free service currently answers 48 kHz, hence the {@link Resampler} call.</p>
 *
 * <p>Failures are reported, never swallowed: the cache keeps its failure cooldown and the editor panel
 * shows the reason, because "nothing was synthesised" without a reason is the least debuggable
 * outcome there is.</p>
 */
public final class ExternalTtsBackend implements SpeechBackend
{
    /** Name this engine is identified by in log lines. */
    public static final String ENGINE = "external-http";

    /** One line's budget: the service renders on demand, so this is a queue plus a render. */
    public static final Duration TIMEOUT = Duration.ofSeconds(45);

    /** Every backend writes this rate; see the class doc. */
    public static final int RATE = 24000;

    /** A rate limited free service is worth one polite retry, not a queue of them. */
    private static final int ATTEMPTS = 3;
    private static final long BASE_DELAY_MS = 1200L;

    /** What a JSON answer points at, without pulling a JSON library into this package. */
    private static final Pattern URL_FIELD = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    @Override
    public String name()
    {
        return ENGINE;
    }

    @Override
    public void render(SpeechRequest request, File out) throws IOException
    {
        if (request == null || request.spans.isEmpty())
        {
            throw new IOException("nothing to say");
        }

        ExternalVoice voice = ExternalVoice.byId(request.voice);

        render(voice.endpoints.toArray(new String[0]), request.text(), out);
    }

    /**
     * The render itself, against an explicit set of endpoints.
     *
     * <p>Split out so the tests can point it at a local server. The alternative is a backend proven
     * either by the internet (which makes the build fail when a stranger's service hiccups) or by
     * nothing at all — and the two answer shapes, the resampling and the retry all deserve a test.</p>
     *
     * @param endpoints URL templates in fallback order, each containing {@link ExternalVoice#TEXT}
     * @param line      what to say
     * @param out       the wav to create
     */
    static void render(String[] endpoints, String line, File out) throws IOException
    {
        if (line == null || line.isBlank())
        {
            throw new IOException("nothing to say");
        }

        Audio audio = decode(fetchAny(endpoints, line));

        if (WavFile.peak(audio.samples) == 0)
        {
            throw new IOException("the service returned silence for " + audio.samples.length + " samples");
        }

        WavFile.write(out, Resampler.to(audio.samples, audio.rate, RATE), RATE);
        WavFile.validate(out);
    }

    /** Walks the endpoints in fallback order and returns the first answer that is usable. */
    private static byte[] fetchAny(String[] endpoints, String line) throws IOException
    {
        String encoded = URLEncoder.encode(line, StandardCharsets.UTF_8);
        IOException last = null;

        for (String template : endpoints)
        {
            String url = template.replace(ExternalVoice.TEXT, encoded);

            try
            {
                return audioAt(url, true);
            }
            catch (IOException e)
            {
                last = e;

                System.err.println("[BBS Voice] " + host(url) + " failed: " + e.getMessage());
            }
        }

        throw last == null ? new IOException("no endpoint to ask") : last;
    }

    /**
     * @param url           where to look for the audio
     * @param mayFollowJson whether a JSON answer may be followed to the file it names; the second hop
     *                      must be audio, so a service that answers JSON twice cannot loop
     */
    private static byte[] audioAt(String url, boolean mayFollowJson) throws IOException
    {
        byte[] body = get(url);

        if (looksLikeAudio(body))
        {
            return body;
        }

        if (!mayFollowJson)
        {
            throw new IOException("the audio url answered with something that is not audio");
        }

        String next = urlIn(body);

        if (next == null || next.isBlank())
        {
            throw new IOException("neither audio nor a url in the answer: " + snippet(body));
        }

        return audioAt(next, false);
    }

    /** One GET, retried on the statuses a limiter or a restarting server produces. */
    private static byte[] get(String url) throws IOException
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(TIMEOUT)
            .header("User-Agent", "Mozilla/5.0 (compatible; BBSVoice)")
            .header("Accept", "application/json, audio/*;q=0.9, */*;q=0.8")
            .GET()
            .build();

        for (int attempt = 1; ; attempt++)
        {
            int status;
            byte[] body;

            try
            {
                HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());

                status = response.statusCode();
                body = response.body();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();

                throw new IOException("interrupted while waiting for " + host(url));
            }

            if (status >= 200 && status < 300)
            {
                return body;
            }

            IOException failure = new IOException(host(url) + " answered HTTP " + status);

            if (attempt >= ATTEMPTS || !isTransient(status))
            {
                throw failure;
            }

            long delay = BASE_DELAY_MS * (1L << (attempt - 1));

            System.err.println("[BBS Voice] " + failure.getMessage() + "; retrying in " + delay + " ms (attempt " + (attempt + 1) + " of " + ATTEMPTS + ")");
            sleep(delay);
        }
    }

    /** @return the audio, plus the rate it came at. */
    private static Audio decode(byte[] audio) throws IOException
    {
        if (WavFile.isWav(audio))
        {
            return new Audio(WavFile.read(audio), WavFile.sampleRate(audio));
        }

        Mp3Decoder.Pcm pcm = Mp3Decoder.decode(audio);

        return new Audio(pcm.samples, pcm.sampleRate);
    }

    /**
     * @return whether the body is audio rather than a document describing it. Checked by magic bytes,
     *         because the services disagree about {@code Content-Type} (and some send
     *         {@code text/html} for a wav)
     */
    private static boolean looksLikeAudio(byte[] body)
    {
        return WavFile.isWav(body) || isMp3(body) || (body.length > 4 && body[0] == 'O' && body[1] == 'g' && body[2] == 'g' && body[3] == 'S');
    }

    private static boolean isMp3(byte[] body)
    {
        if (body.length < 2)
        {
            return false;
        }

        /* An mp3 either starts with an ID3 tag or with a frame sync. */
        return (body[0] == 'I' && body[1] == 'D' && body[2] == '3')
            || ((body[0] & 0xff) == 0xff && (body[1] & 0xe0) == 0xe0);
    }

    /** @return the {@code url} field of a JSON answer, unescaped, or {@code null}. */
    private static String urlIn(byte[] body)
    {
        Matcher matcher = URL_FIELD.matcher(new String(body, StandardCharsets.UTF_8));

        return matcher.find() ? matcher.group(1).replace("\\/", "/") : null;
    }

    /** A short, readable piece of an unexpected answer, for the error message. */
    private static String snippet(byte[] body)
    {
        String text = new String(body, 0, Math.min(body.length, 120), StandardCharsets.UTF_8).replaceAll("\\s+", " ");

        return text.isBlank() ? "<empty " + body.length + " bytes>" : text;
    }

    private static boolean isTransient(int status)
    {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static String host(String url)
    {
        try
        {
            return URI.create(url).getHost();
        }
        catch (Exception e)
        {
            return url;
        }
    }

    /** @return false when the render queue was interrupted, so the caller stops instead of hanging. */
    private static boolean sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);

            return true;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();

            return false;
        }
    }

    /** A decoded utterance: mono samples and the rate they are at. */
    private static final class Audio
    {
        final short[] samples;
        final int rate;

        Audio(short[] samples, int rate)
        {
            this.samples = samples;
            this.rate = rate;
        }
    }
}
