package com.bbsvoice.client.voice;

import com.bbsvoice.BBSVoiceSettings;
import com.bbsvoice.voice.VoiceData;
import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.audio.SoundPlayer;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.resources.Link;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Drives a {@link SoundPlayer} for one voice clip.
 *
 * <p>The utterance itself is baked at speed 1; the user's volume and speed curves are applied live:
 * volume goes straight into {@code setVolume}, while speed goes into {@code setPitch} (tape style,
 * rate and pitch move together — that is the documented v1 trade off) and is integrated into the
 * expected playback position so that a speed ramp actually changes how far into the line the actor
 * is, and so scrubbing the timeline seeks the right spot.</p>
 *
 * <p>When "fit to clip" is enabled and the clip is too short for the utterance, the speed is
 * multiplied by {@code utterance / clip} so the whole line still fits inside the clip.</p>
 */
public final class VoicePlayback
{
    private static final double SYNC_THRESHOLD = 0.08D;
    private static final float MIN_SPEED = 0.25F;
    private static final float MAX_SPEED = 4F;
    private static final float MAX_FIT = 4F;
    private static final double IDLE_BEFORE_PAUSE = 0.25D;

    private static class State
    {
        Link link;
        float lastTick = Float.NaN;
        double position;
        double idle;
        long nanos;
    }

    private static final Map<VoiceData, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private VoicePlayback()
    {}

    public static void tick(VoiceData data, boolean playing, float relativeTick, IEntity entity, int clipDuration)
    {
        if (data == null)
        {
            return;
        }

        if (relativeTick < 0F)
        {
            stop(data);

            return;
        }

        State state = STATES.computeIfAbsent(data, (key) -> new State());

        long now = System.nanoTime();
        double wall = state.nanos == 0L ? 0D : (now - state.nanos) / 1.0E9D;

        state.nanos = now;

        if (wall > 0.5D)
        {
            wall = 0.5D;
        }

        float delta = Float.isNaN(state.lastTick) ? 0F : relativeTick - state.lastTick;
        boolean fresh = Float.isNaN(state.lastTick);

        state.lastTick = relativeTick;
        state.idle = delta == 0F ? state.idle + wall : 0D;

        Link link = VoiceAudioCache.get(data);

        if (link == null)
        {
            return;
        }

        if (!AudioSupport.isReady())
        {
            /* OpenAL is not up yet (or this machine has no usable audio device). Touching it here
               would permanently break sound for the whole session, so just stay silent for now. */
            return;
        }

        if (!link.equals(state.link))
        {
            if (state.link != null)
            {
                BBSModClient.getSounds().stop(state.link);
            }

            state.link = link;
            state.idle = 0D;
            state.position = -1D;
        }

        SoundPlayer player = BBSModClient.getSounds().playUnique(link);

        if (player == null || player.getBuffer() == null)
        {
            return;
        }

        double duration = player.getBuffer().getDuration();
        float fit = 1F;

        if (data.autoFit.get() && clipDuration > 0 && duration > 0D)
        {
            fit = clamp((float) (duration / (clipDuration / 20D)), 1F, MAX_FIT);
        }

        /*
         * The automation curves are baked into the rendered audio (that is what makes the pitch curve
         * sound like intonation instead of resampling), so playback must not apply them a second time.
         * Only the clip's plain multipliers are still live here.
         *
         * data.pitch is deliberately absent from the speed term as well: folding pitch into the
         * playback rate made raising the pitch shorten the line (the chipmunk effect). Pitch is now
         * part of the render.
         */
        float speed = clamp(data.speed.get() * fit, MIN_SPEED, MAX_SPEED);
        boolean moving = playing && state.idle < IDLE_BEFORE_PAUSE;

        if (state.position < 0D || fresh)
        {
            state.position = relativeTick / 20D * speed;
        }
        else if (moving)
        {
            if (delta < 0F || delta > 40F)
            {
                state.position = relativeTick / 20D * speed;
            }
            else
            {
                state.position += delta / 20D * speed;
            }
        }

        double target = state.position + data.offset.get() / 20D;

        player.setVolume(clamp(data.volume.get(), 0F, 4F));
        player.setPitch(speed);

        if (target < 0D || target >= duration)
        {
            if (player.isPlaying())
            {
                player.pause();
            }

            return;
        }

        if (entity != null && BBSVoiceSettings.spatial())
        {
            player.setRelative(false);
            player.setPosition((float) entity.getX(), (float) entity.getY(), (float) entity.getZ());
        }
        else
        {
            player.setRelative(true);
        }

        if (moving)
        {
            if (!player.isPlaying())
            {
                player.play();
            }
        }
        else if (player.isPlaying())
        {
            player.pause();
        }

        if (Math.abs(player.getPlaybackPosition() - target) > SYNC_THRESHOLD)
        {
            player.setPlaybackPosition((float) target);
        }
    }

    public static void stop(VoiceData data)
    {
        State state = STATES.remove(data);

        if (state != null && state.link != null && AudioSupport.isReady())
        {
            BBSModClient.getSounds().stop(state.link);
        }
    }

    /** Drops the cached playback position of every clip, used when the timeline is restarted. */
    public static void reset(VoiceData data)
    {
        State state = STATES.get(data);

        if (state != null)
        {
            state.position = -1D;
            state.lastTick = Float.NaN;
        }
    }

    private static float clamp(float value, float min, float max)
    {
        return value < min ? min : Math.min(value, max);
    }
}
