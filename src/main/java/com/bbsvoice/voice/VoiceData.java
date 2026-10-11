package com.bbsvoice.voice;

import com.bbsvoice.tts.edge.EdgeTtsRequest;
import com.bbsvoice.tts.edge.EdgeVoice;
import com.bbsvoice.tts.speech.ProsodyPlanner;
import com.bbsvoice.tts.speech.ProsodySpan;
import com.bbsvoice.tts.speech.SpeechRequest;
import com.bbsvoice.tts.speech.VoiceEmotion;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.settings.values.base.BaseKeyframeFactoryValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.core.ValueLink;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;

import java.util.List;

/**
 * Everything that makes up one voice-over: the line of text, the speaker, and the three automation
 * curves that behave like the ones in a video editor.
 *
 * <p>All of the fields are BBS {@code BaseValue}s so they are saved with the film and are undoable
 * through BBS' own undo system. The curves are ordinary {@link KeyframeChannel}s, which is exactly
 * what BBS' keyframe graph editor consumes.</p>
 *
 * <p><b>What is baked and what is live</b> — the split is the important part of this class, because
 * getting it wrong either double-applies a value or forces a network render on every slider drag:</p>
 *
 * <ul>
 *   <li><b>Baked into the audio</b> (part of the cache key, re-renders when changed): the text, the
 *       speaker, <em>pitch</em>, and the <em>three curves</em>. The curves are baked because the SSML
 *       is the only place that can shape prosody mid-line, and pitch is baked because changing pitch at
 *       playback time would resample the whole line (the chipmunk effect) instead of sounding like
 *       intonation.</li>
 *   <li><b>Applied live at playback</b> (never invalidates the cache): the plain gain and pace of the
 *       clip, the offset, and fit-to-clip. Those are what the user drags while watching the timeline,
 *       so they must stay instant.</li>
 * </ul>
 *
 * <p>The curve values are therefore deliberately <em>not</em> multiplied by the plain gain/pace here:
 * the plain values are applied once, during playback. A curve fingerprint that also carried them would
 * re-render — over the network — on every nudge of a slider, and the gain would be applied twice.</p>
 */
public class VoiceData extends ValueGroup
{
    /** Dubbing source: synthesise the text with EdgeTTS. */
    public static final String SOURCE_TTS = "tts";

    /** Dubbing source: use a recording of the user's own voice. */
    public static final String SOURCE_REC = "rec";

    /* --- content --- */

    public final ValueString text = new ValueString("text", "");

    /** Edge voice short name, e.g. {@code zh-CN-XiaoxiaoNeural}. */
    public final ValueString voice = new ValueString("voice", EdgeVoice.DEFAULT_ID);

    /* --- dubbing source --- */

    public final ValueString source = new ValueString("source", SOURCE_TTS);
    public final ValueLink recording = new ValueLink("recording", null);

    /* --- utterance level controls (applied live during playback) --- */

    public final ValueFloat pitch = new ValueFloat("pitch", 1F, 0.25F, 4F);
    public final ValueFloat volume = new ValueFloat("volume", 1F, 0F, 4F);
    public final ValueFloat speed = new ValueFloat("speed", 1F, 0.25F, 4F);
    public final ValueFloat offset = new ValueFloat("offset", 0F, -600F, 600F);

    /**
     * When the clip is shorter than the utterance, speed the utterance up so that it still fits
     * inside the clip instead of being cut off half way through (video editor "fit to duration").
     */
    public final ValueBoolean autoFit = new ValueBoolean("auto_fit", false);

    /* --- automation curves --- */

    /**
     * The curves are real BBS animatable values ({@code BaseKeyframeFactoryValue}) rather than bare
     * {@link KeyframeChannel}s, because that is what BBS discovers when it builds the track sheets of
     * the timeline. Creating the track is what "enabling" the curve means.
     */
    public final BaseKeyframeFactoryValue<Double> volumeCurve = new BaseKeyframeFactoryValue<>("volume_curve", KeyframeFactories.DOUBLE, 1D);
    public final BaseKeyframeFactoryValue<Double> speedCurve = new BaseKeyframeFactoryValue<>("speed_curve", KeyframeFactories.DOUBLE, 1D);

    /**
     * Pitch automation, the third prosody axis.
     *
     * <p>Like the speed and gain curves this is <b>baked into the rendered audio</b> rather than applied
     * during playback, which is what makes it sound like intonation instead of the chipmunk effect that
     * resampling a finished render would give.</p>
     */
    public final BaseKeyframeFactoryValue<Double> pitchCurve = new BaseKeyframeFactoryValue<>("pitch_curve", KeyframeFactories.DOUBLE, 1D);

    /** Emotion preset id. Picking one stamps keyframes onto the three prosody curves. */
    public final ValueString emotion = new ValueString("emotion", VoiceEmotion.NONE);

    public VoiceData(String id)
    {
        super(id);

        this.add(this.text);
        this.add(this.voice);
        this.add(this.source);
        this.add(this.recording);

        this.add(this.pitch);
        this.add(this.volume);
        this.add(this.speed);
        this.add(this.offset);
        this.add(this.autoFit);

        this.add(this.volumeCurve);
        this.add(this.speedCurve);
        this.add(this.pitchCurve);
        this.add(this.emotion);
    }

    /** @return the speaker of this clip; never null, even for a film saved by the old engine. */
    public EdgeVoice edgeVoice()
    {
        return EdgeVoice.byId(this.voice.get());
    }

    /** @return true when this clip should play a recording instead of synthesising speech. */
    public boolean usesRecording()
    {
        return SOURCE_REC.equals(this.source.get());
    }

    public Link recordingLink()
    {
        return this.recording.get();
    }

    /** Switches the speaker for this one clip. */
    public void setVoice(EdgeVoice voice)
    {
        this.voice.set(voice.id);
    }

    /**
     * Build the (pure Java, Minecraft free) render request for this clip.
     *
     * <p>Note what is missing from it: the gain and the pace. They are playback effects, so they are not
     * part of the cache key and changing them must never touch the network.</p>
     */
    public EdgeTtsRequest toRequest()
    {
        return new EdgeTtsRequest(this.text.get(), this.edgeVoice().id, this.prosodyFingerprint());
    }

    public VoiceEmotion emotionPreset()
    {
        return VoiceEmotion.byId(this.emotion.get());
    }

    /* --- baked prosody ------------------------------------------------ */

    /** Baked gain multiplier at a tick inside the clip (0 = clip start). Curve only; see the class doc. */
    public float bakedVolumeAt(float tick)
    {
        return Math.max(0F, this.volumeCurve.evaluateAt(tick).floatValue());
    }

    /** Baked pace multiplier at a tick inside the clip (0 = clip start). Curve only; see the class doc. */
    public float bakedSpeedAt(float tick)
    {
        return Math.max(0.05F, this.speedCurve.evaluateAt(tick).floatValue());
    }

    /**
     * Baked pitch multiplier at a tick inside the clip (0 = clip start).
     *
     * <p>The only axis where the plain value is folded in: pitch cannot be applied at playback without
     * resampling the line, so the clip's pitch and its curve are multiplied here and sent to the engine
     * as one baked number.</p>
     */
    public float bakedPitchAt(float tick)
    {
        return Math.max(0.25F, this.pitch.get() * this.pitchCurve.evaluateAt(tick).floatValue());
    }

    /** Compact, order sensitive rendering of the prosody plan, used as part of the cache key. */
    public String prosodyFingerprint()
    {
        StringBuilder out = new StringBuilder();

        for (ProsodySpan span : this.prosodyPlan())
        {
            out.append(span.text).append('~')
                .append(Math.round(span.speed * 1000F)).append(',')
                .append(Math.round(span.pitch * 1000F)).append(',')
                .append(Math.round(span.volume * 1000F)).append(';');
        }

        return out.toString();
    }

    /**
     * The tick span the prosody curves are drawn over: the longest keyframe extent among the three.
     *
     * <p>Prosody is sampled relative to this rather than to the clip's duration, which keeps the mapping
     * free of any caller having to know how long the clip is, and means a preset stamped onto a longer
     * timeline still shapes the whole line instead of only its beginning.</p>
     */
    public float curveExtent()
    {
        return Math.max(lengthOf(this.volumeCurve), Math.max(lengthOf(this.speedCurve), lengthOf(this.pitchCurve)));
    }

    private static float lengthOf(BaseKeyframeFactoryValue<Double> curve)
    {
        KeyframeChannel<Double> track = curve.getTrack();

        return track == null || track.isEmpty() ? 0F : (float) track.getLength();
    }

    /**
     * Stamps an emotion preset onto the three prosody curves.
     *
     * <p>Presets write ordinary keyframes, so after picking one the user can drag the points around -
     * that is what makes "preset plus curve" work. A null axis removes that curve.</p>
     */
    public void applyEmotion(VoiceEmotion preset)
    {
        this.emotion.set(preset.id);

        stamp(this.volumeCurve, preset.ticks, preset.volume);
        stamp(this.speedCurve, preset.ticks, preset.speed);
        stamp(this.pitchCurve, preset.ticks, preset.pitch);
    }

    private static void stamp(BaseKeyframeFactoryValue<Double> curve, float[] ticks, float[] values)
    {
        if (values == null)
        {
            if (curve.hasTrack())
            {
                curve.removeTrack();
            }

            return;
        }

        KeyframeChannel<Double> track = curve.hasTrack() ? curve.getTrack() : curve.createTrack();

        track.removeAll();

        for (int i = 0; i < ticks.length && i < values.length; i++)
        {
            track.insert(ticks[i], (double) values[i]);
        }
    }

    /**
     * The utterance cut into prosody spans, with the three curves sampled across their own extent.
     * This is what the engine bakes into the audio.
     *
     * <p>The curve-less branch is not just "everything unity": the plain <b>pitch</b> is baked here even
     * when no curve exists, because nothing else applies it. The plain gain and pace deliberately stay
     * out (playback effects), so folding them in would double-apply them; the plain pitch has no such
     * second application, and leaving it out made the pitch slider do nothing at all until a pitch
     * curve was switched on. The first in-game self test caught exactly that.</p>
     */
    public List<ProsodySpan> prosodyPlan()
    {
        String line = this.text.get();
        float extent = this.curveExtent();

        if (extent <= 0F)
        {
            float plainPitch = Math.max(0.25F, this.pitch.get());

            return ProsodyPlanner.plan(line, ProsodyPlanner.Curve.ONE, (t) -> plainPitch, ProsodyPlanner.Curve.ONE);
        }

        return ProsodyPlanner.plan(line,
            (t) -> this.bakedSpeedAt(t * extent),
            (t) -> this.bakedPitchAt(t * extent),
            (t) -> this.bakedVolumeAt(t * extent));
    }

    /** The render request for this clip, or {@code null} when there is nothing to say. */
    public SpeechRequest toSpeechRequest()
    {
        List<ProsodySpan> plan = this.prosodyPlan();

        return plan.isEmpty() ? null : new SpeechRequest(this.edgeVoice().id, plan);
    }

    public boolean speedCurveOn()
    {
        return this.speedCurve.hasTrack();
    }

    public boolean volumeCurveOn()
    {
        return this.volumeCurve.hasTrack();
    }

    public boolean pitchCurveOn()
    {
        return this.pitchCurve.hasTrack();
    }

    /** Enabling a curve means creating its track, and that is what the timeline edits. */
    public void setVolumeCurve(boolean enabled)
    {
        if (enabled && !this.volumeCurve.hasTrack())
        {
            this.volumeCurve.createTrack().insert(0F, 1D);
        }
        else if (!enabled && this.volumeCurve.hasTrack())
        {
            this.volumeCurve.removeTrack();
        }
    }

    public void setSpeedCurve(boolean enabled)
    {
        if (enabled && !this.speedCurve.hasTrack())
        {
            this.speedCurve.createTrack().insert(0F, 1D);
        }
        else if (!enabled && this.speedCurve.hasTrack())
        {
            this.speedCurve.removeTrack();
        }
    }

    public void setPitchCurve(boolean enabled)
    {
        if (enabled && !this.pitchCurve.hasTrack())
        {
            this.pitchCurve.createTrack().insert(0F, 1D);
        }
        else if (!enabled && this.pitchCurve.hasTrack())
        {
            this.pitchCurve.removeTrack();
        }
    }
}
