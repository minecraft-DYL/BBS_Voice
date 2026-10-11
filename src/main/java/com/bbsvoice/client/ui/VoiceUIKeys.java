package com.bbsvoice.client.ui;

import com.bbsvoice.tts.edge.EdgeVoice;
import mchorse.bbs_mod.l10n.L10n;
import mchorse.bbs_mod.l10n.keys.IKey;

import java.util.HashMap;
import java.util.Map;

/**
 * Translation keys of the addon UI. The strings live in
 * {@code assets/bbsvoice/strings/<language>.json}.
 */
public class VoiceUIKeys
{
    public static final IKey TEXT = L10n.lang("bbsvoice.ui.text");
    public static final IKey TEXT_TOOLTIP = L10n.lang("bbsvoice.ui.text.tooltip");
    public static final IKey TEXT_TITLE = L10n.lang("bbsvoice.ui.text.title");
    public static final IKey TEXT_MESSAGE = L10n.lang("bbsvoice.ui.text.message");
    public static final IKey EDIT_TEXT = L10n.lang("bbsvoice.ui.edit_text");
    public static final IKey CHARACTER = L10n.lang("bbsvoice.ui.character");
    public static final IKey MIX = L10n.lang("bbsvoice.ui.mix");
    public static final IKey PITCH = L10n.lang("bbsvoice.ui.pitch");
    public static final IKey VOLUME = L10n.lang("bbsvoice.ui.volume");
    public static final IKey SPEED = L10n.lang("bbsvoice.ui.speed");
    public static final IKey OFFSET = L10n.lang("bbsvoice.ui.offset");
    public static final IKey OFFSET_TOOLTIP = L10n.lang("bbsvoice.ui.offset.tooltip");
    public static final IKey CURVES = L10n.lang("bbsvoice.ui.curves");
    public static final IKey VOLUME_CURVE = L10n.lang("bbsvoice.ui.volume_curve");
    public static final IKey SPEED_CURVE = L10n.lang("bbsvoice.ui.speed_curve");
    public static final IKey PITCH_CURVE = L10n.lang("bbsvoice.ui.pitch_curve");
    public static final IKey EMOTION = L10n.lang("bbsvoice.ui.emotion");
    public static final IKey CURVE_TOOLTIP = L10n.lang("bbsvoice.ui.curve.tooltip");
    public static final IKey DUBBING_SOURCE = L10n.lang("bbsvoice.ui.dubbing_source");
    public static final IKey SOURCE_TTS = L10n.lang("bbsvoice.ui.source.tts");
    public static final IKey SOURCE_REC = L10n.lang("bbsvoice.ui.source.rec");
    public static final IKey RECORD_HINT = L10n.lang("bbsvoice.ui.record.hint");
    public static final IKey PICK_RECORDING = L10n.lang("bbsvoice.ui.pick_recording");
    public static final IKey NO_RECORDING = L10n.lang("bbsvoice.ui.recording.none");
    public static final IKey AUTO_FIT = L10n.lang("bbsvoice.ui.auto_fit");
    public static final IKey AUTO_FIT_TOOLTIP = L10n.lang("bbsvoice.ui.auto_fit.tooltip");
    public static final IKey STATUS = L10n.lang("bbsvoice.ui.status");
    public static final IKey STATUS_EMPTY = L10n.lang("bbsvoice.ui.status.empty");
    public static final IKey STATUS_RENDERING = L10n.lang("bbsvoice.ui.status.rendering");
    public static final IKey STATUS_READY = L10n.lang("bbsvoice.ui.status.ready");
    public static final IKey STATUS_CUT = L10n.lang("bbsvoice.ui.status.cut");
    public static final IKey STATUS_FAILED = L10n.lang("bbsvoice.ui.status.failed");
    public static final IKey STATUS_MISSING_REC = L10n.lang("bbsvoice.ui.status.missing_rec");
    public static final IKey STATUS_NO_REC = L10n.lang("bbsvoice.ui.status.no_rec");
    public static final IKey RERENDER = L10n.lang("bbsvoice.ui.rerender");
    public static final IKey OPEN_FOLDER = L10n.lang("bbsvoice.ui.open_folder");

    private static final Map<String, IKey> VOICES = new HashMap<>();

    static
    {
        for (EdgeVoice voice : EdgeVoice.all())
        {
            VOICES.put(voice.id, L10n.lang(voice.labelKey, voice.labelFallback, null));
        }
    }

    /** @return the label of a speaker; the cloud fallback covers a voice the language file lacks. */
    public static IKey voice(String id)
    {
        IKey key = VOICES.get(id);

        return key == null ? IKey.constant(id) : key;
    }
}
