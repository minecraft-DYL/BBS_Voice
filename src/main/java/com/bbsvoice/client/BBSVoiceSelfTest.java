package com.bbsvoice.client;

import com.bbsvoice.BBSVoice;
import com.bbsvoice.client.ui.VoiceUIKeys;
import com.bbsvoice.client.voice.AudioSupport;
import com.bbsvoice.client.voice.VoiceActionClientClip;
import com.bbsvoice.client.voice.VoiceAudioCache;
import com.bbsvoice.client.voice.VoiceClientClip;
import com.bbsvoice.client.voice.VoiceClipFit;
import com.bbsvoice.tts.FailureHint;
import com.bbsvoice.tts.WavFile;
import com.bbsvoice.tts.VoiceCatalog;
import com.bbsvoice.tts.edge.EdgeVoice;
import com.bbsvoice.tts.external.ExternalVoice;
import com.bbsvoice.tts.speech.ProsodySpan;
import com.bbsvoice.voice.ClipFitRules;
import com.bbsvoice.voice.VoiceClip;
import com.bbsvoice.voice.VoiceData;
import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.audio.SoundBuffer;
import mchorse.bbs_mod.audio.SoundPlayer;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless in game smoke test, activated with {@code -Dbbsvoice.selftest=1}.
 *
 * <p>It boots inside the real client (inside the real user installation) and exercises everything
 * that can only break at runtime: the clip factory registration, the data serialisation round trip,
 * the language files, the internal source pack, the offline renderer + sound cache + OpenAL load,
 * the recording branch and the editor panel construction. Then it writes a report and quits.</p>
 *
 * <p>This is a development tool, it does nothing unless the system property is set.</p>
 */
public final class BBSVoiceSelfTest
{
    private static final String PREFIX = "[bbsvoice-selftest]";

    private static final List<String> RESULTS = new ArrayList<>();

    private static int ticks;
    private static int waitStart = -1;
    private static int loadStart = -1;
    private static int quitAt = -1;
    private static boolean started;
    private static boolean loaded;
    private static boolean noted;
    private static VoiceClip clip;
    private static VoiceData data;
    private static Link rendered;

    private BBSVoiceSelfTest()
    {}

    public static void register()
    {
        if (System.getProperty("bbsvoice.selftest") == null)
        {
            return;
        }

        System.out.println(PREFIX + " enabled, waiting for the client to settle");

        ClientTickEvents.END_CLIENT_TICK.register(BBSVoiceSelfTest::tick);
    }

    private static void tick(MinecraftClient client)
    {
        /* The title screen is enough, none of the checks need a loaded world. */
        ticks++;

        if (quitAt > 0)
        {
            if (ticks >= quitAt)
            {
                client.scheduleStop();
            }

            return;
        }

        if (!started)
        {
            if (ticks < 40)
            {
                return;
            }

            started = true;

            try
            {
                run();
            }
            catch (Throwable t)
            {
                fail("selftest", t);
                finish(client);
            }

            return;
        }

        if (rendered == null)
        {
            waitForRender(client);
        }
        else if (!loaded)
        {
            if (!noted)
            {
                noted = true;
                note("audio state", "openal=" + AudioSupport.isReady()
                    + " lwjglLayout=" + AudioSupport.supported()
                    + " mixer=" + (client.getSoundManager() == null ? "null" : client.getSoundManager().getClass().getName()));
            }

            if (!AudioSupport.isReady())
            {
                /* The client may still be pulling up its sound engine; do not touch OpenAL before
                   Minecraft created the AL context, and give up honestly if it never does. */
                if (ticks - loadStart > 1200)
                {
                    skip("sound buffer loaded", "OpenAL unavailable in this session (no audio device?)");
                    finish(client);
                }

                return;
            }

            loaded = true;
            loadSound(client);
            finish(client);
        }
    }

    private static void run() throws Exception
    {
        /* 1. the clip factories, which is how a voice track gets created in the editor */
        Clip camera = BBSMod.getFactoryCameraClips().create(BBSVoice.VOICE_CLIP);
        Clip action = BBSMod.getFactoryActionClips().create(BBSVoice.VOICE_CLIP);

        check("clip factory (film track)", camera instanceof VoiceClientClip, describe(camera));
        check("clip factory (actor track)", action instanceof VoiceActionClientClip, describe(action));

        if (!(camera instanceof VoiceClip))
        {
            throw new IllegalStateException("the film factory did not return a voice clip");
        }

        clip = (VoiceClip) camera;
        data = clip.getVoiceData();

        /* 2. serialisation round trip, this is what saving a film does */
        data.text.set("self test 自检");
        data.pitch.set(1.5F);
        data.voice.set(EdgeVoice.ZH_YUNXI.id);
        data.autoFit.set(true);
        data.source.set(VoiceData.SOURCE_TTS);

        BaseType saved = clip.toData();
        VoiceClip reloaded = new VoiceClip();

        reloaded.fromData(saved);

        check("data round trip (text)", "self test 自检".equals(reloaded.getVoiceData().text.get()), reloaded.getVoiceData().text.get());
        check("data round trip (voice)", EdgeVoice.ZH_YUNXI.id.equals(reloaded.getVoiceData().voice.get()), reloaded.getVoiceData().voice.get());
        check("data round trip (numbers)", reloaded.getVoiceData().pitch.get() == 1.5F, String.valueOf(reloaded.getVoiceData().pitch.get()));
        check("data round trip (boolean)", reloaded.getVoiceData().autoFit.get(), String.valueOf(reloaded.getVoiceData().autoFit.get()));
        check("data round trip (link is null safe)", reloaded.getVoiceData().recordingLink() == null, String.valueOf(reloaded.getVoiceData().recordingLink()));

        /* 2b. a film saved by the old (deleted) formant engine: keys this class no longer has must be
           ignored, and the old preset ids must resolve to a real Edge voice instead of a mute clip */
        VoiceData legacy = new VoiceData("voice");
        MapType legacyData = new MapType();

        legacyData.putString("text", "旧片子");
        legacyData.putString("voice", "male");
        legacyData.putFloat("f0", 150F);
        legacyData.putBool("tone", false);
        legacyData.putString("speech_voice", "Microsoft Huihui Desktop");
        legacyData.putInt("excitation", 1);
        legacy.fromData(legacyData);

        check("legacy film: unknown keys ignored", "旧片子".equals(legacy.text.get()), legacy.text.get());
        check("legacy film: old voice id falls back", EdgeVoice.exists(legacy.edgeVoice().id), legacy.edgeVoice().id);

        /*
         * 2c. the speaker catalog spans two engines now. Both halves of that are offline checks: the
         * character voice must resolve to itself (not silently to Xiaoxiao), and the dispatch must send
         * it to the external backend rather than into Edge's handshake.
         */
        check("voice catalog: the character voice is selectable",
            VoiceCatalog.exists(ExternalVoice.MANBO.id()) && VoiceCatalog.byId(ExternalVoice.MANBO.id()) == ExternalVoice.MANBO,
            ExternalVoice.MANBO.id());
        check("voice catalog: the engines are dispatched apart",
            VoiceCatalog.isExternal(ExternalVoice.MANBO.id()) && !VoiceCatalog.isExternal(EdgeVoice.DEFAULT_ID),
            "character=" + VoiceCatalog.isExternal(ExternalVoice.MANBO.id()) + " edge=" + VoiceCatalog.isExternal(EdgeVoice.DEFAULT_ID));

        /*
         * 2d. failure hints - what the user gets instead of reading the log. A 429 has to advise waiting
         * or batching smaller, a 403 has to say risk control, and a dead third-party mirror has to say
         * "switch voice" rather than blame the user's own network. The messages are the ones the backends
         * really throw, so a reworded message fails here instead of quietly reading as "something went
         * wrong".
         */
        check("failure hint: a 429 reads as rate limiting",
            FailureHint.classify(false, "could not open the Edge speech connection: HTTP 429 - the Edge speech service refused the websocket upgrade") == FailureHint.RATE_LIMITED,
            FailureHint.classify(false, "HTTP 429").name());
        check("failure hint: a 403 reads as risk control",
            FailureHint.classify(false, "could not open the Edge speech connection: HTTP 403 - the Edge speech service refused the websocket upgrade") == FailureHint.BLOCKED,
            FailureHint.classify(false, "HTTP 403").name());
        check("failure hint: a dead network is not confused with a refusal",
            FailureHint.classify(false, "java.net.UnknownHostException: api.msedgeservices.com") == FailureHint.OFFLINE,
            FailureHint.classify(false, "java.net.UnknownHostException: x").name());
        check("failure hint: a dead third-party voice says to switch back",
            FailureHint.classify(true, "the mirror said something nobody has seen before") == FailureHint.EXTERNAL_DOWN,
            FailureHint.classify(true, "the mirror said something odd").name());

        /* 3. language files load after the addon reload */
        String text = VoiceUIKeys.TEXT.get();

        check("l10n loaded", text != null && !text.isEmpty() && !"bbsvoice.ui.text".equals(text), text);

        /*
         * The unit tests read the json from the classpath; only this one proves the string pack shipped
         * inside the jar is actually the one L10n loaded, which is the difference between the picker
         * saying "曼波 · 角色配音" and it saying "bbsvoice.voice.ext:manbo".
         */
        check("l10n names the character voice",
            !VoiceUIKeys.voice(ExternalVoice.MANBO.id()).get().startsWith("bbsvoice."),
            VoiceUIKeys.voice(ExternalVoice.MANBO.id()).get());
        check("l10n names the failure hints",
            !VoiceUIKeys.hint(FailureHint.RATE_LIMITED).get().startsWith("bbsvoice."),
            VoiceUIKeys.hint(FailureHint.RATE_LIMITED).get());

        /* 4. our own source pack is registered */
        try (InputStream stream = BBSMod.getProvider().getAsset(new Link(BBSVoice.MOD_ID, "strings/zh_cn.json")))
        {
            check("source pack registered", stream != null && stream.read() >= 0, "strings/zh_cn.json");
        }

        /* 5. the recording branch must never crash, even without a recording */
        data.source.set(VoiceData.SOURCE_REC);
        VoiceAudioCache.Status missing = VoiceAudioCache.status(data);

        check("recording branch without a file", VoiceAudioCache.get(data) == null && missing != VoiceAudioCache.Status.READY, String.valueOf(missing));
        data.source.set(VoiceData.SOURCE_TTS);

        /* 5b. the automation curves are real animatable values with a discoverable track */
        data.setVolumeCurve(true);
        check("volume curve track created", data.volumeCurveOn() && data.bakedVolumeAt(0F) == 1F, data.bakedVolumeAt(0F) + "");
        data.setVolumeCurve(false);
        check("volume curve track removed", !data.volumeCurveOn(), String.valueOf(data.volumeCurveOn()));

        /* 5c. the cache key must move with the text, the speaker and the baked curves - and only those */
        String baseline = data.toRequest().signature();

        data.pitch.set(1.6F);
        check("pitch is baked into the cache key", !baseline.equals(data.toRequest().signature()), "pitch changed");

        /*
         * And it has to actually reach the engine, not merely change the key: the plain pitch is baked
         * because nothing applies it at playback. With no curve track switched on this is the easy path
         * to get wrong - the plan used to come back all-unity here, which made the pitch slider dead.
         */
        List<ProsodySpan> flatPlan = data.prosodyPlan();

        check("plain pitch reaches the engine with no curves on",
            !flatPlan.isEmpty() && Math.abs(flatPlan.get(0).pitch - 1.6F) < 1.0E-3F,
            flatPlan.isEmpty() ? "empty plan" : "pitch=" + flatPlan.get(0).pitch);

        data.pitch.set(1.5F);

        data.voice.set(EdgeVoice.ZH_XIAOXIAO.id);
        check("speaker is part of the cache key", !baseline.equals(data.toRequest().signature()), "voice changed");
        data.voice.set(EdgeVoice.ZH_YUNXI.id);

        data.volume.set(2F);
        data.speed.set(1.75F);
        check("gain/pace stay out of the cache key", baseline.equals(data.toRequest().signature()), "live effects must not re-render");
        data.volume.set(1F);
        data.speed.set(1F);

        /*
         * 5d. One axis animated and the other two switched off. The off axes are still sampled (the
         * plan samples all three), so they must fall back to their plain value - if a missing track
         * evaluated to zero instead, the volume would be baked as -100% and the line would come out
         * silent, which is a failure a user would hear but never be able to explain.
         */
        data.setVolumeCurve(true);

        KeyframeChannel<Double> volumeTrack = data.volumeCurve.getTrack();

        volumeTrack.removeAll();
        volumeTrack.insert(0F, 1D);
        volumeTrack.insert(40F, 0.5D);

        check("an animated curve is sampled", Math.abs(data.bakedVolumeAt(40F) - 0.5F) < 1.0E-3F, String.valueOf(data.bakedVolumeAt(40F)));
        check("un-animated axes stay at their plain value",
            Math.abs(data.bakedSpeedAt(40F) - 1F) < 1.0E-3F && Math.abs(data.bakedPitchAt(40F) - data.pitch.get()) < 1.0E-3F,
            "speed=" + data.bakedSpeedAt(40F) + " pitch=" + data.bakedPitchAt(40F));

        data.setVolumeCurve(false);
        check("removing a curve goes back to the same render", baseline.equals(data.toRequest().signature()), "back to baseline");

        /* 6. the editor panel, built through the same factory the clips panel uses */
        String panel = buildPanel(clip);

        check("editor panel", panel.startsWith("built"), panel);

        /* 7. kick off the EdgeTTS renderer (this one needs the network) */
        VoiceAudioCache.retry(data);
        waitStart = ticks;
    }

    private static String buildPanel(VoiceClip clip)
    {
        try
        {
            IUIClipsDelegate delegate = (IUIClipsDelegate) Proxy.newProxyInstance(
                IUIClipsDelegate.class.getClassLoader(),
                new Class<?>[] { IUIClipsDelegate.class },
                (proxy, method, args) ->
                {
                    Class<?> type = method.getReturnType();

                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == float.class) return 0F;
                    if (type == double.class) return 0D;
                    if (type == long.class) return 0L;

                    return null;
                });

            UIClip panel = UIClip.createPanel(clip, delegate);

            return panel != null && panel.panels != null ? "built, " + panel.getClass().getSimpleName() : "null panel";
        }
        catch (Throwable t)
        {
            fail("editor panel", t);

            return "threw " + t;
        }
    }

    private static void waitForRender(MinecraftClient client)
    {
        VoiceAudioCache.Status status = VoiceAudioCache.status(data);

        if (status == VoiceAudioCache.Status.READY)
        {
            rendered = VoiceAudioCache.get(data);

            if (rendered != null)
            {
                loadStart = ticks;
                check("edge tts render", true, String.valueOf(rendered));
                checkRenderedAudio();
                checkClipFit();
            }
        }
        else if (status == VoiceAudioCache.Status.FAILED)
        {
            fail("edge tts render", new IllegalStateException("the renderer reported FAILED: " + VoiceAudioCache.failureReason(data)));
            finish(client);
        }
        else if (ticks - waitStart > 1200)
        {
            fail("edge tts render", new IllegalStateException("timed out after 60s, last status " + status + " " + VoiceAudioCache.failureReason(data)));
            finish(client);
        }
    }

    /**
     * Reads the cached wav back off the disk and checks it is real audio.
     *
     * <p>"The file exists" is not evidence that anything was synthesised - a silent or truncated file
     * would load into BBS just as happily, and would then be cached as a finished render forever. The
     * sample rate is checked too, because 24 kHz is what the Edge pipeline produces end to end and a
     * different number would mean the decoder or the wav writer lost a detail.</p>
     */
    private static void checkRenderedAudio()
    {
        try
        {
            File file = new File(VoiceAudioCache.folder(), VoiceAudioCache.resolveSignature(data) + ".wav");
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            int rate = bytes.length < 28 ? 0
                : (bytes[24] & 0xff) | ((bytes[25] & 0xff) << 8) | ((bytes[26] & 0xff) << 16) | ((bytes[27] & 0xff) << 24);
            short[] samples = WavFile.read(file);
            int peak = 0;

            for (short sample : samples)
            {
                peak = Math.max(peak, Math.abs((int) sample));
            }

            check("rendered wav decodes to audio", samples.length > 0 && peak > 0 && rate == 24000,
                samples.length + " samples, peak " + peak + ", " + rate + " Hz, " + file.length() + " bytes, "
                    + VoiceAudioCache.resolveSignature(data) + ".wav");
        }
        catch (Throwable t)
        {
            fail("rendered wav decodes to audio", t);
        }
    }

    /**
     * The clip has to end up as long as the line that was rendered into it.
     *
     * <p>A clip that is shorter than its audio is not cosmetic: BBS stops a clip's sound the moment the
     * playhead leaves the clip, so a short clip cuts its line off at the clip's right edge. The mod
     * therefore sizes the clip to its render. This checks the result end to end on real clips together
     * with the two cases where the rule has to refuse: a length the user set by hand, and fit-to-clip.</p>
     */
    private static void checkClipFit()
    {
        int audioTicks = VoiceAudioCache.audioTicks(data);

        check("rendered line reports its length", audioTicks > 0,
            audioTicks + " ticks, " + (audioTicks / 20F) + "s");

        if (audioTicks <= 0)
        {
            return;
        }

        VoiceClip fresh = typedClip();
        boolean grew = VoiceClipFit.onRendered(fresh.voice, audioTicks);

        check("a fresh clip grows to hold its line", grew && fresh.duration.get() == audioTicks,
            "clip " + ClipFitRules.DEFAULT_TICKS + " -> " + fresh.duration.get() + " of " + audioTicks + " ticks");

        int byHand = Math.max(ClipFitRules.DEFAULT_TICKS, audioTicks / 2);
        VoiceClip trimmed = typedClip();

        trimmed.duration.set(byHand);
        VoiceClipFit.fitIfOurs(trimmed.voice);

        check("a clip the user sized is left alone", trimmed.duration.get() == byHand,
            byHand + " ticks stayed " + trimmed.duration.get() + " while the line needs " + audioTicks);

        VoiceClip fitted = typedClip();

        fitted.voice.autoFit.set(true);
        VoiceClipFit.onRendered(fitted.voice, audioTicks);

        check("fit to clip keeps the user's length", fitted.duration.get() == ClipFitRules.DEFAULT_TICKS,
            "auto fit on, length " + fitted.duration.get() + ", line " + audioTicks + " ticks");
    }

    /** A clip carrying the same line as the rendered one, so the cache knows how long that line is. */
    private static VoiceClip typedClip()
    {
        VoiceClip clip = new VoiceClip();

        clip.voice.text.set(data.text.get());
        clip.voice.setVoice(VoiceCatalog.byId(data.voice.get()));

        return clip;
    }

    private static void loadSound(MinecraftClient client)
    {
        if (rendered == null)
        {
            return;
        }

        try
        {
            SoundBuffer buffer = BBSModClient.getSounds().get(rendered, true);
            float duration = buffer == null ? 0F : buffer.getDuration();

            check("sound buffer loaded", buffer != null && duration > 0F, duration + "s");

            SoundPlayer player = BBSModClient.getSounds().playUnique(rendered);
            boolean playing = player != null;

            if (playing)
            {
                player.setVolume(0F);
                player.play();
                player.pause();
                BBSModClient.getSounds().stop(rendered);
            }

            check("openal playback started", playing, playing ? "ok" : "no player");
        }
        catch (Throwable t)
        {
            fail("sound buffer loaded", t);
        }
    }

    private static void check(String name, boolean ok, String detail)
    {
        String line = (ok ? "PASS" : "FAIL") + " | " + name + " | " + detail;

        RESULTS.add(line);
        System.out.println(PREFIX + " " + line);
    }

    private static void fail(String name, Throwable t)
    {
        String line = "FAIL | " + name + " | " + t;

        RESULTS.add(line);
        System.out.println(PREFIX + " " + line);
        t.printStackTrace(System.out);
    }

    /** For checks that cannot run in this environment (reported, never counted as a pass). */
    private static void skip(String name, String reason)
    {
        String line = "SKIP | " + name + " | " + reason;

        RESULTS.add(line);
        System.out.println(PREFIX + " " + line);
    }

    /** Purely informational, ignored by the pass/fail tally. */
    private static void note(String name, String detail)
    {
        String line = "INFO | " + name + " | " + detail;

        RESULTS.add(line);
        System.out.println(PREFIX + " " + line);
    }

    private static String describe(Object object)
    {
        return object == null ? "null" : object.getClass().getName();
    }

    private static void finish(MinecraftClient client)
    {
        int passed = 0;
        int failed = 0;
        int skipped = 0;

        for (String line : RESULTS)
        {
            if (line.startsWith("PASS"))
            {
                passed++;
            }
            else if (line.startsWith("SKIP"))
            {
                skipped++;
            }
            else if (!line.startsWith("INFO"))
            {
                failed++;
            }
        }

        String summary = "SELFTEST " + (failed == 0 ? "PASS" : "FAIL") + " passed=" + passed + " failed=" + failed
            + (skipped > 0 ? " skipped=" + skipped : "");

        RESULTS.add(summary);
        System.out.println(PREFIX + " " + summary);

        try (PrintWriter writer = new PrintWriter(new File(client.runDirectory, "bbsvoice-selftest.txt"), "UTF-8"))
        {
            for (String line : RESULTS)
            {
                writer.println(line);
            }
        }
        catch (Exception e)
        {
            System.out.println(PREFIX + " could not write the report: " + e);
        }

        /* Leave the window up for a moment so the run can be observed, then quit. */
        try
        {
            client.getWindow().setTitle("Minecraft 1.20.4 - BBS Voice selftest " + summary);
        }
        catch (Throwable ignored)
        {}

        quitAt = ticks + 200;
    }
}
