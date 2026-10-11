package com.bbsvoice.client.voice;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Guards every touch of BBS' OpenAL backed audio.
 *
 * <p>BBS plays audio through {@code SoundBuffer}, which calls {@code alGenBuffers} directly, so it
 * assumes Minecraft's sound engine already created and bound an OpenAL context. Calling into AL
 * before that is worse than a one off failure: LWJGL's capability holder
 * ({@code org.lwjgl.openal.AL$ICDStatic$WriteOnce}) fails to initialise, and that class stays broken
 * for the rest of the JVM session - <b>every</b> sound in the game, BBS' own audio included, dies
 * with {@code NoClassDefFoundError: Could not initialize class org.lwjgl.openal.AL$ICDStatic$WriteOnce}.
 * So the mod has to know that OpenAL is really usable before it touches it.</p>
 *
 * <p>{@code AL.getCapabilities()} cannot be used to find out: it is the call that triggers the
 * poisoning. The capability value itself is read straight out of LWJGL's capability holder, which
 * only initialises that holder class and never runs the poisoned nested initialiser.</p>
 */
public final class AudioSupport
{
    private AudioSupport()
    {}

    /** Whether OpenAL is usable right now (false on a machine without a working audio device, too). */
    public static boolean isReady()
    {
        Boolean alCapabilities = alCapabilitiesSet();

        if (alCapabilities != null)
        {
            return alCapabilities;
        }

        /* Unknown LWJGL layout: fall back to the device level check. */
        return alcCapabilitiesSet();
    }

    /**
     * @return true/false when LWJGL's AL capability holder can be inspected,
     *         null when its layout is unknown.
     */
    private static Boolean alCapabilitiesSet()
    {
        try
        {
            Class<?> holder = Class.forName("org.lwjgl.openal.AL$ICDStatic");
            Field field = holder.getDeclaredField("tempCaps");

            field.setAccessible(true);

            return field.get(null) != null;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /** {@code ALC.getCapabilities()} is a safe getter: it throws a plain exception while unset. */
    private static boolean alcCapabilitiesSet()
    {
        try
        {
            return org.lwjgl.openal.ALC.getCapabilities() != null;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** Whether the capability holder this class relies on looks like the one LWJGL 3.3.x uses. */
    public static boolean supported()
    {
        try
        {
            Class<?> holder = Class.forName("org.lwjgl.openal.AL$ICDStatic");

            for (Field field : holder.getDeclaredFields())
            {
                if (Modifier.isStatic(field.getModifiers()) && org.lwjgl.openal.ALCapabilities.class.isAssignableFrom(field.getType()))
                {
                    return true;
                }
            }
        }
        catch (Throwable ignored)
        {}

        return false;
    }
}
