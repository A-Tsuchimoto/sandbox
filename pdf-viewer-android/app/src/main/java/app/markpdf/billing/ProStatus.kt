package app.markpdf.billing

import android.content.Context

/**
 * 無料版 / Pro版（買い切り）の判定。
 *
 * 試作段階では SharedPreferences のフラグのみ。
 * 製品版では Google Play Billing の買い切りアイテム（INAPP）の購入状態で置き換える。
 */
class ProStatus(context: Context) {
    private val prefs = context.getSharedPreferences("pro", Context.MODE_PRIVATE)

    var isPro: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) = prefs.edit().putBoolean(KEY, value).apply()

    private companion object {
        const val KEY = "is_pro"
    }
}
