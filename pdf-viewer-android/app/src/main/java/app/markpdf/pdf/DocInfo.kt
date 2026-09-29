package app.markpdf.pdf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.security.MessageDigest

/**
 * 表示名と、ハイライト保存用のキー。
 *
 * 同じファイルでも「開く」経路（ファイルアプリ / ピッカー）で URI が変わるため、
 * キーは「ファイル名 + サイズ」から作る。取れない場合のみ URI を使う。
 */
data class DocInfo(val displayName: String, val key: String) {
    companion object {
        fun query(context: Context, uri: Uri): DocInfo {
            var name: String? = null
            var size = -1L
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
            }
            val display = name ?: uri.lastPathSegment ?: "document.pdf"
            val source = if (name != null && size >= 0) "$name\u0000$size" else uri.toString()
            return DocInfo(display, sha256(source))
        }

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
