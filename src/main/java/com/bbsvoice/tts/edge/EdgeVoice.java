package com.bbsvoice.tts.edge;

/**
 * One selectable voice of the Edge read-aloud service ("配音人物").
 *
 * <p>The list is not invented: these are the {@code zh-*} entries the service itself reports through
 * {@code .../readaloud/voices/list}. The id is the short name that goes into the SSML {@code <voice>}
 * element, so a typo here is a request the service rejects — {@link #byId(String)} therefore always
 * answers with a voice that exists.</p>
 */
public final class EdgeVoice
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

    private static final EdgeVoice[] VOICES = new EdgeVoice[] {
        ZH_XIAOXIAO, ZH_XIAOYI, ZH_YUNXI, ZH_YUNJIAN, ZH_YUNYANG, ZH_YUNXIA,
        ZH_XIAOBEI, ZH_XIAONI,
        ZH_HIU_GAAI, ZH_HIU_MAAN, ZH_WAN_LUNG,
        ZH_HSIAO_CHEN, ZH_HSIAO_YU, ZH_YUN_JHE
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
    public String toString()
    {
        return this.id;
    }
}
