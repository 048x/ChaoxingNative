package com.chaoxing.nativeapp

/**
 * 与原 Python 版 api/config.py 完全一致的常量。
 */
object Cfg {
    const val AES_KEY = "u2oh6Vu^HWe4_AES"

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/118.0.0.0 Safari/537.36"

    /** 原 gc.THRESHOLD = 1（秒） */
    const val THRESHOLD_MS = 1000L

    const val VIDEO_REFERER =
        "https://mooc1.chaoxing.com/ananas/modules/video/index.html?v=2025-0725-1842"

    const val AUDIO_REFERER =
        "https://mooc1.chaoxing.com/ananas/modules/audio/index_new.html?v=2025-0725-1842"

    val HEADERS: Map<String, String> = mapOf(
        "User-Agent" to UA,
        "sec-ch-ua" to "\"Chromium\";v=\"118\", \"Google Chrome\";v=\"118\", \"Not=A?Brand\";v=\"99\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
    )

    /** 对应原 gc.VIDEO_HEADERS */
    val VIDEO_HEADERS: Map<String, String> = mapOf("Referer" to VIDEO_REFERER)

    /** 对应原 gc.AUDIO_HEADERS */
    val AUDIO_HEADERS: Map<String, String> = mapOf("Referer" to AUDIO_REFERER)
}
