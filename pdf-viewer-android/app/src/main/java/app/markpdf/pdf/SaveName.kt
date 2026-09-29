package app.markpdf.pdf

/** 保存時に提案するファイル名。 */
object SaveName {
    private const val SUFFIX = "_highlighted"

    fun suggest(displayName: String): String {
        var base = displayName.trim()
        if (base.lowercase().endsWith(".pdf")) base = base.dropLast(4)
        if (base.isEmpty()) base = "document"
        if (!base.endsWith(SUFFIX)) base += SUFFIX
        return "$base.pdf"
    }
}
