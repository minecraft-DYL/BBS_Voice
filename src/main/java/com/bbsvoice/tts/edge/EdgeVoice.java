package com.bbsvoice.tts.edge;

import com.bbsvoice.tts.Voice;

/**
 * One selectable voice of the Edge read-aloud service ("配音人物").
 *
 * <p>The list is not invented: these are entries the service itself reports through
 * {@code .../readaloud/voices/list}. The id is the short name that goes into the SSML {@code <voice>}
 * element, so a typo here is a request the service rejects — {@link #byId(String)} therefore always
 * answers with a voice that exists.</p>
 *
 * <p>Beside the 14 Chinese voices the list carries the 12 {@code *MultilingualNeural} ones: they are
 * named after the locale they were recorded in, but the service documents that they read any of its
 * languages, Chinese included. They are here because the Chinese list is genuinely exhausted —
 * {@code zh-*} holds exactly the 14 this build already had, so "more Chinese voices" was not an
 * option.</p>
 *
 * <p>Character voices (曼波 and the like) are deliberately absent: Edge has none. Their ids also could
 * not satisfy the shape the tests assert, because that shape is pasted straight into the SSML. They
 * live in {@link com.bbsvoice.tts.external.ExternalVoice} and meet these voices in
 * {@link com.bbsvoice.tts.VoiceCatalog}.</p>
 */
public final class EdgeVoice implements Voice
{
    /** Voice used when a clip carries no (or an unknown) voice — including films saved by the old engine. */
    public static final String DEFAULT_ID = "zh-CN-XiaoxiaoNeural";

    public final String id;
    public final String labelKey;
    public final String labelFallback;
    public final String locale;
    public final String gender;

    private EdgeVoice(String id, String labelFallback, String locale, String gender)
    {
        this.id = id;
        this.labelKey = "bbsvoice.voice." + id;
        this.labelFallback = labelFallback;
        this.locale = locale;
        this.gender = gender;
    }

    public boolean isFemale()
    {
        return "Female".equals(this.gender);
    }

    /* Mainland Mandarin. */
    public static final EdgeVoice ZH_XIAOXIAO = new EdgeVoice("zh-CN-XiaoxiaoNeural", "晓晓 · 女声", "zh-CN", "Female");
    public static final EdgeVoice ZH_XIAOYI = new EdgeVoice("zh-CN-XiaoyiNeural", "晓伊 · 女声（活泼）", "zh-CN", "Female");
    public static final EdgeVoice ZH_YUNXI = new EdgeVoice("zh-CN-YunxiNeural", "云希 · 男声", "zh-CN", "Male");
    public static final EdgeVoice ZH_YUNJIAN = new EdgeVoice("zh-CN-YunjianNeural", "云健 · 男声（浑厚）", "zh-CN", "Male");
    public static final EdgeVoice ZH_YUNYANG = new EdgeVoice("zh-CN-YunyangNeural", "云扬 · 男声（播报）", "zh-CN", "Male");
    public static final EdgeVoice ZH_YUNXIA = new EdgeVoice("zh-CN-YunxiaNeural", "云夏 · 童声", "zh-CN", "Male");

    /* Dialects. */
    public static final EdgeVoice ZH_XIAOBEI = new EdgeVoice("zh-CN-liaoning-XiaobeiNeural", "晓北 · 东北话", "zh-CN-liaoning", "Female");
    public static final EdgeVoice ZH_XIAONI = new EdgeVoice("zh-CN-shaanxi-XiaoniNeural", "晓妮 · 陕西话", "zh-CN-shaanxi", "Female");

    /* Cantonese. */
    public static final EdgeVoice ZH_HIU_GAAI = new EdgeVoice("zh-HK-HiuGaaiNeural", "曉佳 · 粤语女声", "zh-HK", "Female");
    public static final EdgeVoice ZH_HIU_MAAN = new EdgeVoice("zh-HK-HiuMaanNeural", "曉曼 · 粤语女声", "zh-HK", "Female");
    public static final EdgeVoice ZH_WAN_LUNG = new EdgeVoice("zh-HK-WanLungNeural", "雲龍 · 粤语男声", "zh-HK", "Male");

    /* Taiwanese Mandarin. */
    public static final EdgeVoice ZH_HSIAO_CHEN = new EdgeVoice("zh-TW-HsiaoChenNeural", "曉臻 · 台湾女声", "zh-TW", "Female");
    public static final EdgeVoice ZH_HSIAO_YU = new EdgeVoice("zh-TW-HsiaoYuNeural", "曉雨 · 台湾女声", "zh-TW", "Female");
    public static final EdgeVoice ZH_YUN_JHE = new EdgeVoice("zh-TW-YunJheNeural", "雲哲 · 台湾男声", "zh-TW", "Male");

    /*
     * Multilingual voices: recorded in one locale, documented to read every language the service
     * speaks. This is how a Chinese line can be read by Ava or Florian, and it is what makes a
     * dub cast feel like a cast instead of the same six Chinese voices.
     */
    public static final EdgeVoice EN_US_AVA_ML = new EdgeVoice("en-US-AvaMultilingualNeural", "Ava · 多语种女声", "en-US", "Female");
    public static final EdgeVoice EN_US_ANDREW_ML = new EdgeVoice("en-US-AndrewMultilingualNeural", "Andrew · 多语种男声", "en-US", "Male");
    public static final EdgeVoice EN_US_EMMA_ML = new EdgeVoice("en-US-EmmaMultilingualNeural", "Emma · 多语种女声", "en-US", "Female");
    public static final EdgeVoice EN_US_BRIAN_ML = new EdgeVoice("en-US-BrianMultilingualNeural", "Brian · 多语种男声", "en-US", "Male");
    public static final EdgeVoice EN_AU_WILLIAM_ML = new EdgeVoice("en-AU-WilliamMultilingualNeural", "William · 多语种男声（澳洲）", "en-AU", "Male");
    public static final EdgeVoice DE_DE_FLORIAN_ML = new EdgeVoice("de-DE-FlorianMultilingualNeural", "Florian · 多语种男声", "de-DE", "Male");
    public static final EdgeVoice DE_DE_SERAPHINA_ML = new EdgeVoice("de-DE-SeraphinaMultilingualNeural", "Seraphina · 多语种女声", "de-DE", "Female");
    public static final EdgeVoice FR_FR_REMY_ML = new EdgeVoice("fr-FR-RemyMultilingualNeural", "Remy · 多语种男声", "fr-FR", "Male");
    public static final EdgeVoice FR_FR_VIVIENNE_ML = new EdgeVoice("fr-FR-VivienneMultilingualNeural", "Vivienne · 多语种女声", "fr-FR", "Female");
    public static final EdgeVoice IT_IT_GIUSEPPE_ML = new EdgeVoice("it-IT-GiuseppeMultilingualNeural", "Giuseppe · 多语种男声", "it-IT", "Male");
    public static final EdgeVoice KO_KR_HYUNSU_ML = new EdgeVoice("ko-KR-HyunsuMultilingualNeural", "Hyunsu · 多语种男声", "ko-KR", "Male");
    public static final EdgeVoice PT_BR_THALITA_ML = new EdgeVoice("pt-BR-ThalitaMultilingualNeural", "Thalita · 多语种女声", "pt-BR", "Female");

    private static final EdgeVoice[] VOICES = new EdgeVoice[] {
        ZH_XIAOXIAO, ZH_XIAOYI, ZH_YUNXI, ZH_YUNJIAN, ZH_YUNYANG, ZH_YUNXIA,
        ZH_XIAOBEI, ZH_XIAONI,
        ZH_HIU_GAAI, ZH_HIU_MAAN, ZH_WAN_LUNG,
        ZH_HSIAO_CHEN, ZH_HSIAO_YU, ZH_YUN_JHE,
        EN_US_AVA_ML, EN_US_ANDREW_ML, EN_US_EMMA_ML, EN_US_BRIAN_ML, EN_AU_WILLIAM_ML,
        DE_DE_FLORIAN_ML, DE_DE_SERAPHINA_ML,
        FR_FR_REMY_ML, FR_FR_VIVIENNE_ML,
        IT_IT_GIUSEPPE_ML, KO_KR_HYUNSU_ML, PT_BR_THALITA_ML
    };

    public static EdgeVoice[] all()
    {
        return VOICES;
    }

    public static EdgeVoice defaultVoice()
    {
        return byId(DEFAULT_ID);
    }

    /** @return the voice with this short name, or the default when the id is unknown/absent. */
    public static EdgeVoice byId(String id)
    {
        if (id != null)
        {
            for (EdgeVoice voice : VOICES)
            {
                if (voice.id.equals(id))
                {
                    return voice;
                }
            }
        }

        return ZH_XIAOXIAO;
    }

    /** @return whether the id names a voice this build knows about. */
    public static boolean exists(String id)
    {
        if (id == null)
        {
            return false;
        }

        for (EdgeVoice voice : VOICES)
        {
            if (voice.id.equals(id))
            {
                return true;
            }
        }

        return false;
    }

    public static int indexOf(String id)
    {
        for (int i = 0; i < VOICES.length; i++)
        {
            if (VOICES[i].id.equals(id))
            {
                return i;
            }
        }

        return 0;
    }

    @Override
    public String id()
    {
        return this.id;
    }

    @Override
    public String labelKey()
    {
        return this.labelKey;
    }

    @Override
    public String labelFallback()
    {
        return this.labelFallback;
    }

    @Override
    public String toString()
    {
        return this.id;
    }
}
