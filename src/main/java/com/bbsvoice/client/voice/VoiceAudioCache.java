package com.bbsvoice.client.voice;

import com.bbsvoice.BBSVoice;
import com.bbsvoice.BBSVoiceSettings;
import com.bbsvoice.tts.WavFile;
import com.bbsvoice.tts.edge.EdgeTtsBackend;
import com.bbsvoice.tts.edge.EdgeTtsRequest;
import com.bbsvoice.tts.speech.SpeechRequest;
import com.bbsvoice.voice.ClipFitRules;
import com.bbsvoice.voice.VoiceData;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.resources.Link;
import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Renders utterances with EdgeTTS in the background and caches them as {@code .wav} files under
 * {@code config/bbs/assets/audio/bbsvoice/}.
 *
 * <p>The cache key is the signature of everything that changes the audio (text, speaker, baked
 * prosody), so a clip only ever renders once per unique line. Gain and pace are applied live during
 * playback and therefore never invalidate the cache — see {@link VoiceData} for why that split is the
 * whole point.</p>
 *
 * <p>Because rendering now costs a network round trip, a failure is treated as <em>temporary</em>: the
 * reason is kept (and shown in the panel) but the line is retried after {@link #FAILURE_COOLDOWN_MS}.
 * A permanent blacklist would mean one dropped packet left a line silent until the user found the
 * "re-synthesise" button, which is a bug the user would blame on the mod.</p>
 */
public final class VoiceAudioCache
{
    public static final String FOLDER = BBSVoice.VOICE_CACHE_FOLDER;

    /** How long a failed render is left alone before the next automatic attempt. */
    private static final long FAILURE_COOLDOWN_MS = 60_000L;

    public enum Status
    {
        EMPTY,
        RENDERING,
        READY,
        FAILED
    }

    private static final Map<String, Link> READY = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Failure> FAILED = new ConcurrentHashMap<>();

    /** Length in ticks of each rendered line, so clips can be sized to their audio. */
    private static final Map<String, Integer> TICKS = new ConcurrentHashMap<>();

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor((runnable) ->
    {
        Thread thread = new Thread(runnable, "BBSVoice-TTS");

        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY + 1);

        return thread;
    });

    /** Why a render failed, and when, so the next attempt can wait for the cooldown. */
    private record Failure(String reason, long at) {}

    private VoiceAudioCache()
    {}

    public static String resolveSignature(VoiceData data)
    {
        return data.toRequest().signature();
    }

    /** @return the playable link, or {@code null} while it is still being rendered. */
    public static Link get(VoiceData data)
    {
        if (data == null)
        {
            return null;
        }

        /* Recordings are not synthesised - they are already on disk. */
        if (data.usesRecording())
        {
            return existingRecording(data);
        }

        if (data.text.get() == null || data.text.get().trim().isEmpty())
        {
            return null;
        }

        String signature = data.toRequest().signature();
        Link ready = READY.get(signature);

        if (ready != null)
        {
            return ready;
        }

        File file = fileFor(signature);

        if (file.isFile() && file.length() > 44)
        {
            Link link = linkFor(signature);

            READY.put(signature, link);

            return link;
        }

        if (isCoolingDown(signature))
        {
            return null;
        }

        if (BBSVoiceSettings.autoRender() && PENDING.add(signature))
        {
            submit(signature, data, file);
        }

        return null;
    }

    /**
     * Renders this line in the background if it is missing, without waiting for playback.
     *
     * <p>Playback used to be the only thing that ever asked for a render, and the film's playhead is
     * only inside a clip for as long as that clip is long - a second or two - while a render on a cold
     * connection takes longer than that. So the first pass over a freshly typed line was silent, or
     * played just the tail of the audio when the render happened to land while the playhead was still
     * inside the clip. Starting the render while the user is looking at the clip panel is what makes
     * that first pass audible.</p>
     */
    public static void prefetch(VoiceData data)
    {
        get(data);
    }

    /** @return how long this clip's audio plays for, in ticks, or 0 while it is not rendered yet. */
    public static int audioTicks(VoiceData data)
    {
        if (data == null)
        {
            return 0;
        }

        if (data.usesRecording())
        {
            Link link = data.recordingLink();

            if (link == null)
            {
                return 0;
            }

            try
            {
                return ticksOf(link.toString(), BBSMod.getProvider().getFile(link));
            }
            catch (Exception e)
            {
                return 0;
            }
        }

        if (data.text.get() == null || data.text.get().trim().isEmpty())
        {
            return 0;
        }

        String signature = resolveSignature(data);

        return ticksOf(signature, fileFor(signature));
    }

    /** Reads a wav's length once and remembers it; the file lives for the whole session. */
    private static int ticksOf(String key, File file)
    {
        Integer cached = TICKS.get(key);

        if (cached != null)
        {
            return cached;
        }

        int ticks = file != null && file.isFile() ? ClipFitRules.ticksFor(WavFile.seconds(file)) : 0;

        TICKS.put(key, ticks);

        return ticks;
    }

    public static Status status(VoiceData data)
    {
        if (data == null)
        {
            return Status.EMPTY;
        }

        if (data.usesRecording())
        {
            if (data.recordingLink() == null)
            {
                return Status.EMPTY;
            }

            return existingRecording(data) != null ? Status.READY : Status.FAILED;
        }

        if (data.text.get() == null || data.text.get().trim().isEmpty())
        {
            return Status.EMPTY;
        }

        String signature = resolveSignature(data);

        if (READY.containsKey(signature) || fileFor(signature).isFile())
        {
            return Status.READY;
        }

        if (isCoolingDown(signature))
        {
            return Status.FAILED;
        }

        return PENDING.contains(signature) ? Status.RENDERING : Status.EMPTY;
    }

    /** @return why the last render of this line failed, or {@code null} when it did not. */
    public static String failureReason(VoiceData data)
    {
        if (data == null || data.usesRecording())
        {
            return null;
        }

        Failure failure = FAILED.get(resolveSignature(data));

        return failure == null ? null : failure.reason();
    }

    /** Drops the cached render of this line and queues it again (the manual "re-synthesise" button). */
    public static void retry(VoiceData data)
    {
        if (data == null || data.usesRecording())
        {
            return;
        }

        if (data.text.get() == null || data.text.get().trim().isEmpty())
        {
            return;
        }

        String signature = data.toRequest().signature();

        FAILED.remove(signature);
        READY.remove(signature);
        TICKS.remove(signature);

        File file = fileFor(signature);

        if (file.isFile())
        {
            file.delete();
        }

        /* Forcefully re-render, even when automatic rendering is switched off. */
        if (PENDING.add(signature))
        {
            submit(signature, data, file);
        }
    }

    public static File folder()
    {
        return BBSMod.getAssetsPath(FOLDER);
    }

    private static boolean isCoolingDown(String signature)
    {
        Failure failure = FAILED.get(signature);

        return failure != null && System.currentTimeMillis() - failure.at() < FAILURE_COOLDOWN_MS;
    }

    private static File fileFor(String signature)
    {
        return new File(folder(), signature + ".wav");
    }

    /** @return the link of a recording that is actually on disk, or {@code null}. */
    private static Link existingRecording(VoiceData data)
    {
        Link link = data.recordingLink();

        if (link == null)
        {
            return null;
        }

        try
        {
            File file = BBSMod.getProvider().getFile(link);

            return file != null && file.isFile() && file.length() > 44 ? link : null;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Link linkFor(String signature)
    {
        return Link.assets(FOLDER + "/" + signature + ".wav");
    }

    private static void submit(String signature, VoiceData data, File file)
    {
        POOL.submit(() ->
        {
            try
            {
                Files.createDirectories(file.getParentFile().toPath());

                SpeechRequest speech = data.toSpeechRequest();

                if (speech == null)
                {
                    throw new IOException("nothing to say");
                }

                new EdgeTtsBackend().render(speech, file);

                FAILED.remove(signature);

                int ticks = ClipFitRules.ticksFor(WavFile.seconds(file));

                if (ticks > 0)
                {
                    TICKS.put(signature, ticks);
                }

                onClientThread(() ->
                {
                    READY.put(signature, linkFor(signature));

                    /* The user has just written this line: give it a clip long enough to hold it. */
                    VoiceClipFit.onRendered(data, ticks);
                });

                prune();
            }
            catch (Throwable throwable)
            {
                /* A failed render must not leave a half written file behind to be mistaken for a hit. */
                file.delete();

                String reason = throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();

                FAILED.put(signature, new Failure(reason, System.currentTimeMillis()));

                System.err.println("[BBS Voice] Could not synthesise \"" + data.text.get() + "\": " + reason);
            }
            finally
            {
                PENDING.remove(signature);
            }
        });
    }

    private static void onClientThread(Runnable runnable)
    {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client == null)
        {
            runnable.run();
        }
        else
        {
            client.execute(runnable);
        }
    }

    /** Keeps the cache folder at or below the configured file count. */
    private static void prune()
    {
        File[] files = folder().listFiles((dir, name) -> name.endsWith(".wav"));

        if (files == null || files.length <= BBSVoiceSettings.maxCacheFiles())
        {
            return;
        }

        List<File> sorted = new ArrayList<>();

        for (File file : files)
        {
            sorted.add(file);
        }

        sorted.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        int remove = sorted.size() - BBSVoiceSettings.maxCacheFiles();

        for (int i = 0; i < remove; i++)
        {
            sorted.get(i).delete();
        }
    }
}
