package me.ash.reader.ui.page.home.reading.podcast

private val audioEnclosureRegex =
    """<enclosure\s+url=(["'])(.*?)\1\s+type=(["'])audio/[^"']+\3\s*/?>"""
        .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val audioTagRegex =
    """<audio[^>]*src=(["'])(.*?)\1[^>]*>"""
        .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val sourceAudioTagRegex =
    """<source[^>]*src=(["'])(.*?)\1[^>]*type=(["'])audio/[^"']+\3[^>]*>"""
        .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

fun extractPodcastMediaUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null

    return audioEnclosureRegex.find(text)?.groupValues?.getOrNull(2)
        ?: audioTagRegex.find(text)?.groupValues?.getOrNull(2)
        ?: sourceAudioTagRegex.find(text)?.groupValues?.getOrNull(2)
}
