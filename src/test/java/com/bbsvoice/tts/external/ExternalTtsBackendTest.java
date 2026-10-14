package com.bbsvoice.tts.external;

import com.bbsvoice.tts.WavFile;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The external backend against a local server: the JSON hop, a direct audio answer, the resample to
 * the mod's rate, the retry after a rate limit, and the refusals.
 *
 * <p>Local on purpose. A test that asks a stranger's service is a test that fails for reasons nobody
 * here can fix, and this backend has enough moving parts (two answer shapes, a hop, a resample, a
 * fallback chain) that "proven by nothing" was not good enough either.</p>
 */
class ExternalTtsBackendTest
{
    /** The shape the 曼波 service answers with, host taken from the local server. */
    private static final String JSON_ANSWER = "{\"code\":200,\"msg\":\"生成完成!\",\"url\":\"%s\"}";

    private HttpServer server;
    private String base;
    private File out;

    @BeforeEach
    void startServer() throws IOException
    {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.start();
        this.base = "http://127.0.0.1:" + this.server.getAddress().getPort();
        this.out = File.createTempFile("bbsvoice-external", ".wav");
        this.out.deleteOnExit();
    }

    @AfterEach
    void stopServer()
    {
        this.server.stop(0);
    }

    @Test
    void followsTheUrlInAJsonAnswerAndNormalisesTheRate() throws IOException
    {
        byte[] wav = wav(48000, 4800, 300);

        answer("/media", 200, "audio/wav", wav);
        answer("/api", 200, "application/json", JSON_ANSWER.formatted(this.base + "/media").getBytes(StandardCharsets.UTF_8));

        ExternalTtsBackend.render(new String[] { this.base + "/api?text={text}" }, "你好，世界。", this.out);

        byte[] bytes = Files.readAllBytes(this.out.toPath());
        short[] samples = WavFile.read(bytes);

        assertEquals(24000, WavFile.sampleRate(bytes), "the rest of the pipeline expects 24 kHz");
        assertTrue(Math.abs(samples.length - 2400) <= 2, "expected 0.1s at 24 kHz, got " + samples.length);
        assertTrue(WavFile.peak(samples) > 0, "the rendered file is silent");
    }

    @Test
    void acceptsAnAnswerThatIsAlreadyAudio() throws IOException
    {
        answer("/direct", 200, "application/octet-stream", wav(24000, 2400, 300));

        ExternalTtsBackend.render(new String[] { this.base + "/direct?text={text}" }, "你好", this.out);

        assertTrue(WavFile.peak(WavFile.read(Files.readAllBytes(this.out.toPath()))) > 0);
    }

    @Test
    void sendsTheLineUrlEncoded() throws IOException
    {
        AtomicReference<String> rawQuery = new AtomicReference<>();

        this.server.createContext("/api", exchange ->
        {
            /*
             * The raw query, not getQuery(): that one decodes the escapes with the JVM's default
             * charset, which is GBK on the machine this was written on, and would turn a correct
             * percent encoding into mojibake. What goes on the wire is what matters.
             */
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "audio/wav", wav(24000, 240, 300));
        });

        ExternalTtsBackend.render(new String[] { this.base + "/api?text={text}" }, "曼波 测试", this.out);

        assertNotNull(rawQuery.get(), "the service was never asked");
        assertTrue(rawQuery.get().contains("text=%E6%9B%BC%E6%B3%A2"), rawQuery.get());
        assertTrue(rawQuery.get().contains("+%E6%B5%8B%E8%AF%95"), "a space must not go out raw: " + rawQuery.get());
        assertFalse(rawQuery.get().contains("曼波"), "the line went out unencoded: " + rawQuery.get());
    }

    @Test
    void retriesOnceAfterARateLimit() throws IOException
    {
        AtomicInteger calls = new AtomicInteger();

        this.server.createContext("/api", exchange ->
        {
            if (calls.incrementAndGet() == 1)
            {
                respond(exchange, 429, "text/plain", "slow down".getBytes(StandardCharsets.UTF_8));

                return;
            }

            respond(exchange, 200, "audio/wav", wav(24000, 240, 300));
        });

        ExternalTtsBackend.render(new String[] { this.base + "/api?text={text}" }, "你好", this.out);

        assertEquals(2, calls.get(), "a 429 should have been retried exactly once");
    }

    @Test
    void fallsBackToTheNextEndpoint() throws IOException
    {
        answer("/mirror", 200, "audio/wav", wav(24000, 240, 300));

        ExternalTtsBackend.render(new String[] {
            "http://127.0.0.1:1/dead?text={text}",
            this.base + "/mirror?text={text}"
        }, "你好", this.out);

        assertTrue(WavFile.peak(WavFile.read(Files.readAllBytes(this.out.toPath()))) > 0, "the mirror was not used");
    }

    @Test
    void refusesSilence() throws IOException
    {
        answer("/silent", 200, "audio/wav", wav(24000, 2400, 0));

        IOException failure = assertThrows(IOException.class, () ->
            ExternalTtsBackend.render(new String[] { this.base + "/silent?text={text}" }, "你好", this.out));

        assertTrue(failure.getMessage().contains("silence"), failure.getMessage());
    }

    @Test
    void refusesAnAnswerThatIsNeitherAudioNorAJsonUrl() throws IOException
    {
        answer("/html", 200, "text/html", "<html>this service has moved</html>".getBytes(StandardCharsets.UTF_8));

        IOException failure = assertThrows(IOException.class, () ->
            ExternalTtsBackend.render(new String[] { this.base + "/html?text={text}" }, "你好", this.out));

        assertNotNull(failure.getMessage());
    }

    @Test
    void refusesAnEmptyLineWithoutAskingAnybody() throws IOException
    {
        assertThrows(IOException.class, () ->
            ExternalTtsBackend.render(new String[] { this.base + "/api?text={text}" }, "   ", this.out));
    }

    /** Registers a handler that always answers the same way. */
    private void answer(String path, int status, String type, byte[] body)
    {
        this.server.createContext(path, exchange -> respond(exchange, status, type, body));
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException
    {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);

        try (OutputStream stream = exchange.getResponseBody())
        {
            stream.write(body);
        }
    }

    /** @return a mono 16 bit wav of {@code count} samples at {@code rate}: a sine at {@code freq} Hz. */
    private static byte[] wav(int rate, int count, double freq)
    {
        short[] samples = new short[count];

        for (int i = 0; i < count; i++)
        {
            samples[i] = (short) Math.round(Math.sin(2 * Math.PI * freq * i / rate) * 12000);
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try
        {
            WavFile.write(bytes, samples, rate);
        }
        catch (IOException e)
        {
            throw new AssertionError(e);
        }

        return bytes.toByteArray();
    }
}
