package eu.kanade.tachiyomi.extension.zh.smallflyingrat

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.ListPreference
import eu.kanade.tachiyomi.extension.zh.smallflyingrat.Smallflyingrat.Companion.DEFAULT_LIST_PREF
import eu.kanade.tachiyomi.extension.zh.smallflyingrat.Smallflyingrat.Companion.SY_TAG_STYLE
import eu.kanade.tachiyomi.extension.zh.smallflyingrat.Smallflyingrat.Companion.URL_INDEX_PREF
import eu.kanade.tachiyomi.extension.zh.smallflyingrat.Smallflyingrat.Companion.URL_LIST_PREF
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

private const val DEFAULT_LIST = "https://smallflyingrat.net/"

fun getPreferencesInternal(
    context: Context,
    preferences: SharedPreferences,
) = arrayOf(
    ListPreference(context).apply {
        key = URL_INDEX_PREF
        title = "BaseUrl"
        summary = "Site base url"

        val options = preferences.urlList
        val count = options.size
        entries = options.toTypedArray()
        entryValues = Array(count, Int::toString)
    },
    androidx.preference.CheckBoxPreference(context).apply {
        key = SY_TAG_STYLE
        title = "TachiyomiSY tag style"
        summary = "Adds tag prefixes used by TachiyomiSY"
        setDefaultValue(true)
    },
)

val SharedPreferences.baseUrl: String
    get() {
        return "https://smallflyingrat.net/"
    }

val SharedPreferences.urlIndex get() = getString(URL_INDEX_PREF, "-1")!!.toInt()
val SharedPreferences.urlList get() = getString(URL_LIST_PREF, DEFAULT_LIST)!!.split(",")

fun getCiBaseUrl() = DEFAULT_LIST.replace(",", ", ")

fun getSharedPreferences(id: Long): SharedPreferences {
    val preferences: SharedPreferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    if (preferences.getString(DEFAULT_LIST_PREF, "")!! == DEFAULT_LIST) return preferences
    preferences.edit()
        .remove("overrideBaseUrl")
        .putString(DEFAULT_LIST_PREF, DEFAULT_LIST)
        .setUrlList(DEFAULT_LIST, preferences.urlIndex)
        .apply()
    return preferences
}

fun SharedPreferences.Editor.setUrlList(urlList: String, oldIndex: Int): SharedPreferences.Editor {
    putString(URL_LIST_PREF, urlList)
    val maxIndex = urlList.count { it == ',' }
    if (oldIndex in 0..maxIndex) return this
    val newIndex = Random.nextInt(0, maxIndex + 1)
    return putString(URL_INDEX_PREF, newIndex.toString())
}
