package com.bbsvoice.client.ui;

import com.bbsvoice.client.voice.VoiceAudioCache;
import com.bbsvoice.tts.FailureHint;
import com.bbsvoice.client.voice.VoiceClipFit;
import com.bbsvoice.tts.Voice;
import com.bbsvoice.tts.VoiceCatalog;
import com.bbsvoice.tts.speech.VoiceEmotion;
import com.bbsvoice.voice.IVoiceClip;
import com.bbsvoice.voice.VoiceData;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.audio.UIAudioRecorder;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UICirculate;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.framework.elements.overlay.UISoundOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIStringOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UITextareaOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.utils.UILabel;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIUtils;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import mchorse.bbs_mod.utils.clips.Clip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Shared clip editor for both voice clip flavours (film level camera clip and per actor action
 * clip). Everything the user can tweak lives here: the dubbing text, the speaker, the dubbing source
 * (EdgeTTS synthesis or the user's own recording), gain, pitch, pace, offset, fit-to-clip and the
 * gain/pace/pitch automation curves.
 */
public abstract class UIVoiceClipBase <T extends Clip & IVoiceClip> extends UIClip<T>
{
    public UITextbox text;
    public UIButton editText;
    public UIButton rerender;
    /** The speaker. Opens a searchable list, because the catalog outgrew a cycling button. */
    public UIButton voicePicker;

    public UICirculate sourceCycle;
    public UIIcon record;
    public UIIcon pickRecording;
    public UILabel recordingName;

    public UITrackpad pitch;
    public UITrackpad volume;
    public UITrackpad speed;
    public UITrackpad offset;
    public UIToggle autoFit;

    public UIToggle volumeCurve;
    public UIToggle speedCurve;
    public UIToggle pitchCurve;

    /** Emotion preset picker; picking one stamps keyframes onto the three prosody curves. */
    public UICirculate emotionCycle;

    public UILabel status;
    public UIIcon openFolder;

    /** Set while {@link #fillData()} pushes values into the widgets so their callbacks stay quiet. */
    private boolean updating;
    private int frame;

    public UIVoiceClipBase(T clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    public VoiceData voice()
    {
        return this.clip.getVoiceData();
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.text = new UITextbox(4000, (value) ->
        {
            this.editor.editMultiple(this.voice().text, (property) -> property.set(value));

            this.prefetch();
        });
        this.text.delayedInput().tooltip(VoiceUIKeys.TEXT_TOOLTIP);

        this.editText = new UIButton(VoiceUIKeys.EDIT_TEXT, (b) -> this.openTextEditor());
        this.rerender = new UIButton(VoiceUIKeys.RERENDER, (b) -> this.rerender());

        this.voicePicker = new UIButton(VoiceUIKeys.CHARACTER, (b) -> this.openVoicePicker());
        this.voicePicker.tooltip(VoiceUIKeys.VOICE_PICKER_TOOLTIP);

        /* --- dubbing source --- */

        this.sourceCycle = new UICirculate((cycle) ->
        {
            if (!this.updating)
            {
                String source = cycle.getValue() == 1 ? VoiceData.SOURCE_REC : VoiceData.SOURCE_TTS;

                this.editor.editMultiple(this.voice().source, (property) -> property.set(source));
                this.prefetch();
                this.updateStatus();
            }
        });
        this.sourceCycle.addLabel(VoiceUIKeys.SOURCE_TTS);
        this.sourceCycle.addLabel(VoiceUIKeys.SOURCE_REC);

        this.record = new UIIcon(Icons.MICROPHONE, (b) -> this.startRecording());
        this.record.tooltip(VoiceUIKeys.RECORD_HINT);
        this.pickRecording = new UIIcon(Icons.SOUND, (b) -> this.pickRecording());
        this.pickRecording.tooltip(VoiceUIKeys.PICK_RECORDING);
        this.recordingName = new UILabel(VoiceUIKeys.NO_RECORDING);

        /* --- mix --- */

        this.pitch = this.trackpad(VoiceUIKeys.PITCH, 0.25D, 4D, (value) -> this.editor.editMultiple(this.voice().pitch, (property) -> property.set(value.floatValue())));
        this.volume = this.trackpad(VoiceUIKeys.VOLUME, 0D, 4D, (value) -> this.editor.editMultiple(this.voice().volume, (property) -> property.set(value.floatValue())));
        this.speed = this.trackpad(VoiceUIKeys.SPEED, 0.25D, 4D, (value) -> this.editor.editMultiple(this.voice().speed, (property) -> property.set(value.floatValue())));
        this.offset = this.trackpad(VoiceUIKeys.OFFSET, -600D, 600D, (value) -> this.editor.editMultiple(this.voice().offset, (property) -> property.set(value.floatValue())));
        this.offset.tooltip(VoiceUIKeys.OFFSET_TOOLTIP);

        this.autoFit = new UIToggle(VoiceUIKeys.AUTO_FIT, (toggle) -> this.editor.editMultiple(this.voice().autoFit, (property) -> property.set(toggle.getValue())));
        this.autoFit.tooltip(VoiceUIKeys.AUTO_FIT_TOOLTIP);

        /* --- curves: enabling one creates its track, which the BBS timeline then edits --- */

        this.volumeCurve = new UIToggle(VoiceUIKeys.VOLUME_CURVE, (toggle) ->
        {
            VoiceData data = this.voice();

            this.editor.editMultiple(data.volumeCurve, (value) -> data.setVolumeCurve(toggle.getValue()));
        });
        this.volumeCurve.tooltip(VoiceUIKeys.CURVE_TOOLTIP);

        this.speedCurve = new UIToggle(VoiceUIKeys.SPEED_CURVE, (toggle) ->
        {
            VoiceData data = this.voice();

            this.editor.editMultiple(data.speedCurve, (value) -> data.setSpeedCurve(toggle.getValue()));
        });
        this.speedCurve.tooltip(VoiceUIKeys.CURVE_TOOLTIP);

        this.pitchCurve = new UIToggle(VoiceUIKeys.PITCH_CURVE, (toggle) ->
        {
            VoiceData data = this.voice();

            this.editor.editMultiple(data.pitchCurve, (value) -> data.setPitchCurve(toggle.getValue()));
        });
        this.pitchCurve.tooltip(VoiceUIKeys.CURVE_TOOLTIP);

        /*
         * The emotion presets write ordinary keyframes into the three curves rather than a private
         * scale, so after picking one the points can still be dragged on the timeline - preset for a
         * starting shape, curve for the fine tuning.
         */
        this.emotionCycle = new UICirculate((cycle) ->
        {
            if (!this.updating)
            {
                VoiceData data = this.voice();
                VoiceEmotion emotion = VoiceEmotion.all().get(cycle.getValue());

                this.editor.editMultiple(data.emotion, (value) -> data.applyEmotion(emotion));
            }
        });

        for (VoiceEmotion emotion : VoiceEmotion.all())
        {
            this.emotionCycle.addLabel(L10n.lang("bbsvoice.ui.emotion." + emotion.id));
        }

        this.status = new UILabel(VoiceUIKeys.STATUS_EMPTY);
        this.openFolder = new UIIcon(Icons.FOLDER, (b) -> UIUtils.openFolder(VoiceAudioCache.folder()));
        this.openFolder.tooltip(VoiceUIKeys.OPEN_FOLDER);
    }

    private UITrackpad trackpad(IKey label, double min, double max, Consumer<Double> callback)
    {
        UITrackpad trackpad = new UITrackpad(callback);

        trackpad.limit(min, max).increment(0.01D).forcedLabel(label);

        return trackpad;
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(UI.column(UI.label(VoiceUIKeys.TEXT), this.text, UI.row(this.editText, this.rerender)).marginTop(12));
        this.panels.add(UI.column(UI.label(VoiceUIKeys.CHARACTER), this.voicePicker).marginTop(12));
        this.panels.add(UI.column(UI.label(VoiceUIKeys.DUBBING_SOURCE), this.sourceCycle, UI.row(this.record, this.pickRecording, this.recordingName)).marginTop(12));
        this.panels.add(UI.column(UI.label(VoiceUIKeys.MIX), UI.row(this.pitch, this.volume), UI.row(this.speed, this.offset), this.autoFit).marginTop(12));
        this.panels.add(UI.column(UI.label(VoiceUIKeys.EMOTION), this.emotionCycle).marginTop(12));
        this.panels.add(UI.column(UI.label(VoiceUIKeys.CURVES), this.volumeCurve, this.speedCurve, this.pitchCurve).marginTop(12));
        this.panels.add(UI.column(UI.label(VoiceUIKeys.STATUS), UI.row(this.status, this.openFolder)).marginTop(12));
    }

    @Override
    public void fillData()
    {
        super.fillData();

        VoiceData data = this.voice();

        this.updating = true;

        this.text.setText(data.text.get());
        this.pitch.setValue(data.pitch.get());
        this.volume.setValue(data.volume.get());
        this.speed.setValue(data.speed.get());
        this.offset.setValue(data.offset.get());
        this.autoFit.setValue(data.autoFit.get());
        this.volumeCurve.setValue(data.volumeCurveOn());
        this.speedCurve.setValue(data.speedCurveOn());
        this.pitchCurve.setValue(data.pitchCurveOn());

        int emotionIndex = 0;
        for (int i = 0; i < VoiceEmotion.all().size(); i++)
        {
            if (VoiceEmotion.all().get(i).id.equals(data.emotion.get()))
            {
                emotionIndex = i;
            }
        }

        this.emotionCycle.setValue(emotionIndex);

        this.voicePicker.label = VoiceUIKeys.voice(data.voice.get());
        this.sourceCycle.setValue(data.usesRecording() ? 1 : 0);

        this.updating = false;

        /* The panel is open on this clip, so its line should be rendered before anyone hits play. */
        this.prefetch();
        this.updateStatus();
    }

    /**
     * Queues the render of this clip's line while the user is still looking at the panel.
     *
     * <p>Playback is far too late to ask for a network render: the playhead is only inside the clip
     * for as long as the clip is long. Doing it when the text, the speaker or the source changes means
     * the first playback of a line is already audible.</p>
     */
    private void prefetch()
    {
        VoiceAudioCache.prefetch(this.voice());
    }

    @Override
    public void render(UIContext context)
    {
        super.render(context);

        if ((this.frame++ % 10) == 0)
        {
            this.updateStatus();
        }
    }

    private void updateStatus()
    {
        VoiceData data = this.voice();
        Link recording = data.recordingLink();

        this.recordingName.label = recording == null ? VoiceUIKeys.NO_RECORDING : IKey.constant(fileName(recording));

        VoiceAudioCache.Status state = VoiceAudioCache.status(data);
        IKey key;

        switch (state)
        {
            case RENDERING: key = VoiceUIKeys.STATUS_RENDERING; break;
            case READY: key = VoiceUIKeys.STATUS_READY; break;
            case FAILED: key = data.usesRecording() ? VoiceUIKeys.STATUS_MISSING_REC : failureKey(data); break;
            default: key = data.usesRecording() ? VoiceUIKeys.STATUS_NO_REC : VoiceUIKeys.STATUS_EMPTY; break;
        }

        /* A clip shorter than its line cuts the line off; say so instead of letting it look broken. */
        String cut = key == VoiceUIKeys.STATUS_READY ? this.cutWarning(data) : null;

        this.status.label = cut == null ? key : IKey.constant(key.get() + "，" + cut);

        /*
         * The line stays short - one piece of advice - and the message the engine threw goes in the
         * tooltip. That message is what separates "you are being rate limited" from "the service
         * refused you" from "your own network is down", but it is far too long and too ugly to sit in
         * the panel next to the buttons.
         */
        String detail = state == VoiceAudioCache.Status.FAILED && !data.usesRecording() ? failureDetail(data) : null;

        if (detail == null)
        {
            this.status.removeTooltip();
        }
        else
        {
            this.status.tooltip(IKey.constant(detail));
        }
    }

    /**
     * @return a note explaining that this clip cuts its line off, or {@code null} when it does not.
     *
     * <p>Without the note a clip that is too short just looks like the mod failed to play the line:
     * the audio stops dead at the clip's right edge because leaving a clip stops its sound. "Fit to
     * clip" is what the user wants there, so the note names it.</p>
     */
    private String cutWarning(VoiceData data)
    {
        int audioTicks = VoiceAudioCache.audioTicks(data);

        if (audioTicks <= 0 || data.autoFit.get())
        {
            return null;
        }

        int clipTicks = this.clip.duration.get();

        if (clipTicks >= audioTicks)
        {
            return null;
        }

        return VoiceUIKeys.STATUS_CUT.get()
            + "：" + secondsOf(audioTicks) + "s > " + secondsOf(clipTicks) + "s";
    }

    private static String secondsOf(int ticks)
    {
        return String.format(Locale.ROOT, "%.1f", ticks / 20F);
    }

    /**
     * A failed render shows *why* right in the status line.
     *
     * <p>With a network backed engine "合成失败" on its own is useless - it is the difference between
     * "this machine is offline" and "the service rejected the voice", and the user cannot be expected to
     * find the game log to tell them apart.</p>
     */
    private static IKey failureKey(VoiceData data)
    {
        String reason = VoiceAudioCache.failureReason(data);

        if (reason == null)
        {
            return VoiceUIKeys.STATUS_FAILED;
        }

        FailureHint hint = hint(data, reason);

        /* Nothing matched, so the raw message is the only honest thing the line can say. */
        if (hint == FailureHint.UNKNOWN)
        {
            return IKey.constant(VoiceUIKeys.STATUS_FAILED.get() + "：" + trim(reason, 90));
        }

        return IKey.constant(VoiceUIKeys.STATUS_FAILED.get() + "：" + VoiceUIKeys.hint(hint).get());
    }

    /** @return the tooltip of a failed render: the advice, then the message the engine itself threw. */
    private static String failureDetail(VoiceData data)
    {
        String reason = VoiceAudioCache.failureReason(data);

        if (reason == null)
        {
            return VoiceUIKeys.STATUS_FAILED.get();
        }

        return VoiceUIKeys.hint(hint(data, reason)).get() + " | " + trim(reason, 240);
    }

    private static FailureHint hint(VoiceData data, String reason)
    {
        return FailureHint.classify(VoiceCatalog.isExternal(data.voice.get()), reason);
    }

    private static String trim(String text, int max)
    {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String fileName(Link link)
    {
        String path = link.toString();
        int index = path.lastIndexOf('/');

        return index < 0 ? path : path.substring(index + 1);
    }

    private void openTextEditor()
    {
        VoiceData data = this.voice();
        UITextareaOverlayPanel panel = new UITextareaOverlayPanel(VoiceUIKeys.TEXT_TITLE, VoiceUIKeys.TEXT_MESSAGE, (value) ->
        {
            this.editor.editMultiple(data.text, (property) -> property.set(value));
            this.text.setText(value);

            if (VoiceAudioCache.status(data) != VoiceAudioCache.Status.RENDERING)
            {
                VoiceAudioCache.retry(data);
            }
        });

        panel.text.setText(data.text.get());
        UIOverlay.addOverlay(this.getContext(), panel, 0.9F, 0.7F);
    }

    /** Opens BBS' own microphone recorder and turns the result into this clip's dubbing. */
    private void startRecording()
    {
        UIAudioRecorder.start(this.getContext(), "bbsvoice_" + System.currentTimeMillis(), (name, wave) ->
        {
            try
            {
                UIAudioRecorder.saveWave(name, wave);
            }
            catch (Exception e)
            {
                System.err.println("[BBS Voice] Could not save the recording:");
                e.printStackTrace();
            }

            Link link = UIAudioRecorder.toAudioLink(name);
            VoiceData data = this.voice();

            this.editor.editMultiple(data.recording, (property) -> property.set(link));
            this.editor.editMultiple(data.source, (property) -> property.set(VoiceData.SOURCE_REC));

            /* A fresh take sizes the clip to itself, exactly like a synthesised line does. */
            VoiceClipFit.onAudioAttached(data);

            this.fillData();
        });
    }

    /** Lets the user reuse any audio file that is already in BBS' audio folder. */
    private void pickRecording()
    {
        VoiceData data = this.voice();
        UISoundOverlayPanel panel = new UISoundOverlayPanel((link) ->
        {
            this.editor.editMultiple(data.recording, (property) -> property.set(link));
            this.editor.editMultiple(data.source, (property) -> property.set(VoiceData.SOURCE_REC));

            VoiceClipFit.onAudioAttached(data);

            this.fillData();
        }, this.getContext());

        UIOverlay.addOverlay(this.getContext(), panel, 0.9F, 0.6F);
    }

    /**
     * Opens the speaker list as BBS' own searchable overlay.
     *
     * <p>This used to be a cycling button, which stops working the moment the list is longer than a
     * handful of entries — and the catalog is 27 now that the multilingual and character voices are in.
     * The overlay brings a search field with it, so "曼波", "东北" and "Ava" each find their voice
     * without scrolling.</p>
     */
    private void openVoicePicker()
    {
        List<String> labels = new ArrayList<>(VoiceCatalog.all().size());
        Map<String, Voice> byLabel = new LinkedHashMap<>();

        for (Voice voice : VoiceCatalog.all())
        {
            String label = VoiceUIKeys.voice(voice.id()).get();

            /* The list identifies a row by its text, so a duplicated label would be unclickable. */
            if (byLabel.containsKey(label))
            {
                label = label + " (" + voice.id() + ")";
            }

            labels.add(label);
            byLabel.put(label, voice);
        }

        UIStringOverlayPanel panel = new UIStringOverlayPanel(VoiceUIKeys.CHARACTER, labels, (picked) ->
        {
            Voice voice = byLabel.get(picked);

            if (voice != null)
            {
                this.applyVoice(voice);
            }
        });

        panel.set(VoiceUIKeys.voice(this.voice().voice.get()).get());
        UIOverlay.addOverlay(this.getContext(), panel, 0.9F, 0.7F);
    }

    private void applyVoice(Voice voice)
    {
        VoiceData data = this.voice();

        this.editor.editMultiple(data.voice, (property) -> property.set(voice.id()));

        this.fillData();
    }

    private void rerender()
    {
        VoiceAudioCache.retry(this.voice());
        this.updateStatus();
    }
}
