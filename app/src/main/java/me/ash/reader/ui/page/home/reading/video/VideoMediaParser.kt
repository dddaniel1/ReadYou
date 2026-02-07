package me.ash.reader.ui.page.home.reading.video

private val videoEnclosureRegex =
    """<enclosure\s+url=(["'])(.*?)\1\s+type=(["'])video/[^"']+\3\s*/?>"""
        .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val videoTagRegex =
    """<video[^>]*src=(["'])(.*?)\1[^>]*>"""
        .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val sourceVideoTagRegex =
    """<source[^>]*src=(["'])(.*?)\1[^>]*type=(["'])video/[^"']+\3[^>]*>"""
        .toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

fun extractVideoMediaUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null

    return videoEnclosureRegex.find(text)?.groupValues?.getOrNull(2)
        ?: videoTagRegex.find(text)?.groupValues?.getOrNull(2)
        ?: sourceVideoTagRegex.find(text)?.groupValues?.getOrNull(2)
}
