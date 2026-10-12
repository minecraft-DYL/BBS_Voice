package com.bbsvoice.tts.edge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Speaks one SSML document through the Edge "read aloud" websocket and returns the audio it streams
 * back (mp3).
 *
 * <p>This is a straight port of {@code tools/edge_tts.js}, which is the reference implementation that
 * was verified against the live service; every header and query parameter below is there because
 * dropping it was measured to produce a {@code 403} or a {@code 1007} close. The port is possible in
 * plain Java because {@link java.net.http.WebSocket.Builder} can set arbitrary request headers
 * (Node's built-in WebSocket cannot, which is why the reference had to hand-roll framing).</p>
 *
 * <p>Nothing here touches Minecraft, which is what lets the whole pipeline be exercised from a plain
 * {@code main()} — see {@code tools/EdgeTtsProbe.java}.</p>
 *
 * <p>The conversation, in order: the upgrade, a {@code Path:speech.config} JSON frame that chooses the
 * output format, a {@code Path:ssml} frame with the document to speak, then binary frames whose
 * payload header says {@code Path:audio} until a text frame says {@code Path:turn.end}.</p>
 */
public final class EdgeTtsClient
{
    /**
     * Edge read-aloud synthesis endpoint.
     *
     * <p>Since October 2026 the legacy host ({@code speech.platform.bing.com}) answers the synthesis
     * websocket with {@code 403} in most countries, and from 12 October 2026 in the US as well - which
     * is why the same code that worked the day before started failing everywhere at once. Microsoft
     * Edge 155 synthesizes through this host instead. It accepts the legacy client token too, but named
     * {@code Ocp-Apim-Subscription-Key} rather than {@code TrustedClientToken}, and it still honours the
     * legacy {@code Sec-MS-GEC} parameters.</p>
     *
     * <p>Only synthesis moved: the voice list is still served by the legacy host (verified - it answers
     * 200 there and 401 without a token, while the same token is refused on the old synthesis URL).</p>
     */
    public static final String ENDPOINT = "wss://api.msedgeservices.com/tts/cognitiveservices/websocket/v1";

    /** Origin the endpoint expects; a Chromium extension id, not a secret. */
    public static final String ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold";

    /**
     * Output format asked for in {@code speech.config}.
     *
     * <p>Not a free choice: the Edge endpoint is a filtered subset of Azure's formats. Measured on the
     * live service - {@code riff-*-pcm}, {@code raw-*-pcm} and {@code ogg-*-opus} are all rejected with
     * a 1007 close ("Unsupported Edge output format"), leaving mp3 and {@code webm-*-opus}. mp3 wins
     * because a decoder for it is small and pure Java, while Opus in WebM is not.</p>
     */
    public static final String OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";

    /** Connect timeout; the render already runs on a background thread, so this can be generous. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    /** Attempts allowed per line; the service rates the client, so a burst of new lines earns 429s. */
    private static final int MAX_ATTEMPTS = 4;

    /** First retry delay; every further attempt doubles it. */
    private static final long BASE_RETRY_DELAY_MS = 1200L;

    /** Cap on one retry delay, however long {@code Retry-After} asks us to wait. */
    private static final long MAX_RETRY_DELAY_MS = 20_000L;

    /**
     * Minimum spacing between two conversations. The host rates the client per address, so a film whose
     * lines are all new would otherwise open one socket right after the next and collect {@code 429}s
     * on the tail of the burst; a render already takes seconds, so the gap is not noticeable.
     */
    private static final long MIN_REQUEST_GAP_MS = 700L;

    /** When the previous conversation started; keeps consecutive ones apart. */
    private static final AtomicLong LAST_REQUEST_START = new AtomicLong();

    private EdgeTtsClient()
    {}

    /**
     * Speaks {@code ssml}, waiting and trying again while the service is only asking us to slow down.
     *
     * <p>The host rates the client per address: a film whose lines are all new opens one socket after
     * another, and the tail of that burst comes back {@code 429}. That used to be reported to the user
     * as a failed line even though the very next attempt succeeds, so the render now waits instead. The
     * wait respects {@code Retry-After} when the service sends one and doubles otherwise.</p>
     *
     * @param ssml    the document to speak, built with {@code SsmlBuilder}
     * @param timeout total budget for the whole conversation
     * @return the mp3 stream the service produced
     * @throws IOException when the service is unreachable, refuses the request, or streams no audio
     */
    public static byte[] synthesize(String ssml, Duration timeout) throws IOException
    {
        IOException failure = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)
        {
            try
            {
                return synthesizeOnce(ssml, timeout);
            }
            catch (IOException e)
            {
                failure = e;

                if (attempt == MAX_ATTEMPTS || !isTransient(e))
                {
                    throw e;
                }

                long delay = retryDelayMs(e, attempt);

                System.err.println("[BBS Voice] Edge speech refused the upgrade with " + statusOf(e)
                    + "; retrying in " + delay + " ms (attempt " + (attempt + 1) + " of " + MAX_ATTEMPTS + ")");

                if (!sleep(delay))
                {
                    throw e;
                }
            }
        }

        throw failure;
    }

    private static byte[] synthesizeOnce(String ssml, Duration timeout) throws IOException
    {
        pacePolitely();

        String connectionId = randomHex(16);
        String muid = randomHex(16).toUpperCase(java.util.Locale.ROOT);

        URI uri = URI.create(ENDPOINT
            + "?Ocp-Apim-Subscription-Key=" + SecMsGec.TRUSTED_CLIENT_TOKEN
            + "&Sec-MS-GEC=" + SecMsGec.generate()
            + "&Sec-MS-GEC-Version=1-" + SecMsGec.CHROMIUM_VERSION
            + "&ConnectionId=" + connectionId);

        Conversation conversation = new Conversation(connectionId, ssml);

        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        WebSocket socket;

        try
        {
            socket = client.newWebSocketBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .header("Origin", ORIGIN)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Cookie", "muid=" + muid + ";")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("User-Agent", userAgent())
                .buildAsync(uri, conversation)
                .get(CONNECT_TIMEOUT.toMillis() + 1000L, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();

            throw new IOException("interrupted while opening the Edge speech connection", e);
        }
        catch (ExecutionException | TimeoutException e)
        {
            throw new IOException("could not open the Edge speech connection: " + describe(e), unwrap(e));
        }

        try
        {
            if (!conversation.finished.await(timeout.toMillis(), TimeUnit.MILLISECONDS))
            {
                throw new IOException("Edge speech timed out after " + timeout.toSeconds() + "s");
            }

            return conversation.result();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();

            throw new IOException("interrupted while rendering with Edge speech", e);
        }
        finally
        {
            /* Also the normal exit: the service closes its side after turn.end, this is just firm. */
            socket.abort();
        }
    }

    /** Chromium that the endpoint accepts; the version segment alone is checked, not the whole UA. */
    private static String userAgent()
    {
        String major = SecMsGec.CHROMIUM_VERSION.split("\\.")[0];

        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
            + major + ".0.0.0 Safari/537.36 Edg/" + major + ".0.0.0";
    }

    private static String randomHex(int bytes)
    {
        byte[] buffer = new byte[bytes];

        new SecureRandom().nextBytes(buffer);

        StringBuilder out = new StringBuilder(bytes * 2);

        for (byte b : buffer)
        {
            out.append(Character.forDigit((b >> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }

        return out.toString();
    }

    /**
     * A bare {@link WebSocketHandshakeException} prints as nothing but its own class name, which tells
     * whoever is reading the log nothing at all - and "the upgrade was refused" is the failure users
     * actually hit, because this endpoint answers 403 as soon as it dislikes the client token or one of
     * the headers. So the handshake's HTTP status is pulled out of the response and reported.
     */
    private static String describe(Throwable throwable)
    {
        Throwable cause = unwrap(throwable);

        if (cause instanceof WebSocketHandshakeException)
        {
            HttpResponse<?> response = ((WebSocketHandshakeException) cause).getResponse();

            if (response != null)
            {
                return "HTTP " + response.statusCode() + " - the Edge speech service refused the websocket upgrade";
            }
        }

        return cause == null ? String.valueOf(throwable) : cause.toString();
    }

    private static Throwable unwrap(Throwable throwable)
    {
        return throwable.getCause() == null ? throwable : throwable.getCause();
    }

    /** The HTTP status of a refused upgrade, or -1 when the failure was not a handshake response. */
    private static int statusOf(Throwable failure)
    {
        HttpResponse<?> response = handshakeResponse(failure);

        return response == null ? -1 : response.statusCode();
    }

    /**
     * @return whether sending the same request again is worth it: the service rated the client (429) or
     *         is briefly unavailable (5xx). A 403 means the token was rejected and a 400 the document,
     *         so retrying those only delays the error the user needs to see.
     */
    private static boolean isTransient(IOException failure)
    {
        int status = statusOf(failure);

        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static long retryDelayMs(IOException failure, int attempt)
    {
        long backoff = BASE_RETRY_DELAY_MS << (attempt - 1);

        return Math.min(Math.max(backoff, retryAfterMs(failure)), MAX_RETRY_DELAY_MS);
    }

    /** {@code Retry-After} in seconds as the service may send it; 0 when absent or in HTTP-date form. */
    private static long retryAfterMs(Throwable failure)
    {
        HttpResponse<?> response = handshakeResponse(failure);

        if (response == null)
        {
            return 0L;
        }

        String header = response.headers().firstValue("Retry-After").orElse(null);

        if (header == null)
        {
            return 0L;
        }

        try
        {
            return Math.max(0L, Long.parseLong(header.trim())) * 1000L;
        }
        catch (NumberFormatException e)
        {
            /* An HTTP-date; our own backoff is a fine fallback and this stays simple. */
            return 0L;
        }
    }

    /** The response of a refused upgrade, looked for anywhere in the cause chain. */
    private static HttpResponse<?> handshakeResponse(Throwable failure)
    {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
        {
            if (cause instanceof WebSocketHandshakeException)
            {
                return ((WebSocketHandshakeException) cause).getResponse();
            }
        }

        return null;
    }

    /** Keep two conversations apart, so a queue of new lines does not trip the service's rate limit. */
    private static void pacePolitely()
    {
        long wait = LAST_REQUEST_START.get() + MIN_REQUEST_GAP_MS - System.currentTimeMillis();

        if (wait > 0)
        {
            sleep(wait);
        }

        LAST_REQUEST_START.set(System.currentTimeMillis());
    }

    /** @return false when interrupted, having restored the interrupt flag. */
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

    /** One request/response conversation; the listener owns every piece of mutable state. */
    private static final class Conversation implements WebSocket.Listener
    {
        private final String requestId;
        private final String ssml;
        private final CountDownLatch finished = new CountDownLatch(1);
        private final List<byte[]> audio = new ArrayList<>();
        private final ByteArrayOutputStream message = new ByteArrayOutputStream();
        private final StringBuilder text = new StringBuilder();
        private final AtomicReference<String> failure = new AtomicReference<>();
        private final AtomicReference<String> lastNotice = new AtomicReference<>();

        private volatile int audioBytes;
        private volatile boolean turnEnded;

        Conversation(String requestId, String ssml)
        {
            this.requestId = requestId;
            this.ssml = ssml;
        }

        @Override
        public void onOpen(WebSocket socket)
        {
            socket.request(1);

            String timestamp = Instant.now().toString();

            socket.sendText(configFrame(timestamp), true);
            socket.sendText(ssmlFrame(timestamp), true);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last)
        {
            this.text.append(data);

            if (last)
            {
                handleText(this.text.toString());
                this.text.setLength(0);
            }

            socket.request(1);

            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last)
        {
            byte[] chunk = new byte[data.remaining()];

            data.get(chunk);
            this.message.write(chunk, 0, chunk.length);

            if (last)
            {
                /* One websocket message carries the 2 byte header length, the header and the audio. */
                handleBinary(this.message.toByteArray());
                this.message.reset();
            }

            socket.request(1);

            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason)
        {
            /* A refusal is reported here and nowhere else: 1007 carries the reason string. */
            if (!this.turnEnded && statusCode != 1000)
            {
                this.failure.compareAndSet(null, "the service closed the connection ("
                    + statusCode + (reason == null || reason.isEmpty() ? "" : " " + reason) + ")");
            }

            this.finished.countDown();

            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error)
        {
            this.failure.compareAndSet(null, describe(error));
            this.finished.countDown();
        }

        private void handleText(String frame)
        {
            if (frame.contains("Path:turn.end"))
            {
                this.turnEnded = true;
                this.finished.countDown();

                return;
            }

            if (frame.contains("Path:audio.metadata"))
            {
                return;
            }

            /* Anything else (a service tag, a status frame) is kept as context for a failure. */
            int index = frame.indexOf("\r\n\r\n");

            this.lastNotice.set(index >= 0 ? frame.substring(index + 4).trim() : frame.trim());
        }

        private void handleBinary(byte[] frame)
        {
            if (frame.length < 2)
            {
                return;
            }

            int headerLength = ((frame[0] & 0xff) << 8) | (frame[1] & 0xff);

            if (headerLength <= 0 || frame.length < 2 + headerLength)
            {
                return;
            }

            String header = new String(frame, 2, headerLength, StandardCharsets.US_ASCII);

            if (!header.contains("Path:audio"))
            {
                return;
            }

            int length = frame.length - 2 - headerLength;
            byte[] copy = new byte[length];

            System.arraycopy(frame, 2 + headerLength, copy, 0, length);

            synchronized (this.audio)
            {
                this.audio.add(copy);
                this.audioBytes += length;
            }
        }

        private byte[] result() throws IOException
        {
            String failure = this.failure.get();

            if (failure != null)
            {
                throw new IOException("Edge speech failed: " + failure);
            }

            byte[] all;
            int total;

            synchronized (this.audio)
            {
                all = new byte[this.audioBytes];
                total = 0;

                for (byte[] chunk : this.audio)
                {
                    System.arraycopy(chunk, 0, all, total, chunk.length);
                    total += chunk.length;
                }
            }

            if (total == 0)
            {
                throw new IOException("Edge speech streamed no audio"
                    + (this.lastNotice.get() == null ? "" : ": " + this.lastNotice.get()));
            }

            return all;
        }

        private String configFrame(String timestamp)
        {
            String json = "{\"context\":{\"synthesis\":{\"audio\":{"
                + "\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"},"
                + "\"outputFormat\":\"" + OUTPUT_FORMAT + "\"}}}}";

            return "X-Timestamp:" + timestamp + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + json;
        }

        private String ssmlFrame(String timestamp)
        {
            return "X-RequestId:" + this.requestId + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + timestamp + "\r\n"
                + "Path:ssml\r\n\r\n"
                + this.ssml;
        }
    }
}
