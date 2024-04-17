package eu.kanade.tachiyomi.extension.zh.smallflyingrat

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.util.Calendar

class Smallflyingrat : ParsedHttpSource(), ConfigurableSource {
    override val name = "smallflyingrat"
    override val lang = "zh"
    override val supportsLatest = true

    private val preferences = getSharedPreferences(id)

    override val baseUrl = when (System.getenv("CI")) {
        "true" -> getCiBaseUrl()
        else -> preferences.baseUrl
    }
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("referer", baseUrl)
        .set("sec-fetch-mode", "no-cors")
        .set("sec-fetch-site", "cross-site")
    override fun popularMangaSelector() = ".page-item-detail.manga"
    override fun latestUpdatesSelector() = ".slider__item"
    override fun searchMangaSelector() = ".row.c-tabs-item__content"
    override fun chapterListSelector() = ".wp-manga-chapter"
    override fun popularMangaNextPageSelector() = ".nav-previous.float-left"
    override fun latestUpdatesNextPageSelector() = "false"
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()
    override fun popularMangaRequest(page: Int): Request {
        var query = "comics"
        if (page > 1) { query = "$query/page/$page" }
        return GET("${baseUrl}$query?m_orderby=views", headers)
    }
    override fun latestUpdatesRequest(page: Int): Request {
        return GET(baseUrl + "comics", headers)
    }
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) {
            return popularMangaRequest(page)
        }
        val builder = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "wp-manga")
            .addQueryParameter("m_orderby", "latest")
        return GET(builder.toString(), headers)
    }
    override fun searchMangaFromElement(element: Element) = mangaFromElement(element)

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/?s=$id&post_type=wp-manga", headers)

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val details = mangaDetailsParse(response)
        details.url = "/?s=$id&post_type=wp-manga"
        return MangasPage(listOf(details), false)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when {
            query.startsWith(PREFIX_ID_SEARCH) -> {
                val id = query.removePrefix(PREFIX_ID_SEARCH)
                client.newCall(searchMangaByIdRequest(id))
                    .asObservableSuccess()
                    .map { response -> searchMangaByIdParse(response, id) }
            }
            query.toIntOrNull() != null -> {
                client.newCall(searchMangaByIdRequest(query))
                    .asObservableSuccess()
                    .map { response -> searchMangaByIdParse(response, query) }
            }
            else -> super.fetchSearchManga(page, query, filters)
        }
    }

    override fun popularMangaFromElement(element: Element) = mangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
        val a = element.selectFirst("h4 > a")!!
        val image = element.selectFirst("img")!!

        url = a.attr("href")
        title = a.text()
        thumbnail_url = image.choseUrl()
    }
    override fun pageListRequest(chapter: SChapter) =
        GET(chapter.url, headers)
    override fun chapterListRequest(manga: SManga) =
        GET(manga.url, headers)
    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        val link = element.selectFirst("a")!!
        val date = if (element.selectFirst(".chapter-release-date > i") != null) {
            element.selectFirst(".chapter-release-date > i")!!.text()
        } else {
            element.selectFirst(".chapter-release-date > a")!!.attr("title")
        }
        url = link.attr("href")
        name = link.text()
        date_upload = date.parseDate()
    }
    override fun imageUrlParse(document: Document) = throw Exception("Not used")
    override fun mangaDetailsParse(document: Document): SManga {
        val summary = document.selectFirst(".summary_image")!!
        val descriptionHtml = document.selectFirst(".manga-excerpt")?.toString() ?: ""

        val manga = SManga.create()
        manga.url = summary.selectFirst("a")!!.attr("href")
        manga.title = parseTitle(document)
        manga.author = (getContentSummaryByHead(document, HEADINGS.Author)!!.selectFirst("a")!!.text() ?: "Unknown")
        manga.artist = manga.author
        manga.thumbnail_url = summary.selectFirst("img")!!.choseUrl()
        manga.status = parseState(document)
        manga.description = Jsoup.parse(descriptionHtml).text()
        manga.genre = parseGenres(document)

        return manga
    }
    private fun parseState(element: Element): Int {
        val state = getContentSummaryByHead(element, HEADINGS.State)?.text()?.trim() ?: ""
        val enumState = enumValues<States>().firstOrNull { state.contains(it.originalValue) } ?: States.Unknown
        return enumState.alternateValue
    }
    private fun parseTitle(element: Element): String {
        val builder = StringBuilder()
        val name = element.selectFirst("h1")?.text()
        val alternativeName = getContentSummaryByHead(element, HEADINGS.Alternative)?.text()
        if (name != null) {
            builder.append(name)
        }
        if (name != null && alternativeName != null) {
            builder.append(" / ")
        }
        if (alternativeName != null) {
            builder.append(alternativeName)
        }
        return builder.toString()
    }
    private fun parseGenres(element: Element): String {
        val syTagStyle = preferences.getBoolean(SY_TAG_STYLE, true)
        val characters = getContentSummaryByHead(element, HEADINGS.Character)
            ?.select("a")
            ?.mapIndexedTo(ArrayList()) { _, el ->
                if (syTagStyle) {
                    "character: ${el.text()}"
                } else {
                    el.text()
                }
            }
        val series = getContentSummaryByHead(element, HEADINGS.Series)
            ?.select("a")
            ?.mapIndexedTo(ArrayList()) { _, el ->
                if (syTagStyle) {
                    "other: ${el.text()}"
                } else {
                    el.text()
                }
            }
        val genres = ArrayList<String>()
        if (syTagStyle) {
            genres.add("artist: smallflyingrat")
            genres.add("artist: 小飞鼠")
        }
        genres.addAll(characters ?: ArrayList())
        genres.addAll(series ?: ArrayList())
        return genres.joinToString(separator = ", ")
    }
    private fun mangaFromElement(element: Element): SManga {
        val link = element.selectFirst("a")!!
        val image = link.selectFirst("img")!!

        val manga = SManga.create()
        manga.url = link.attr("href")
        manga.title = link.attr("title")
        manga.thumbnail_url = image.choseUrl()
        return manga
    }
    override fun pageListParse(document: Document): List<Page> {
        val mainElement = document.selectFirst(".reading-content")
        val imgList = mainElement!!.select("img").mapIndexedTo(ArrayList()) { index, elm ->
            Page(index, imageUrl = elm.choseUrl())
        }

        return imgList
    }
    private fun Element.choseUrl(): String {
        var url = this.absUrl("data-src").trim()
        if (url.isEmpty()) {
            url = this.absUrl("src").trim()
        }
        return url
    }
    private fun String?.parseDate(): Long {
        return runCatching {
            parseRelativeDate(this!!.toString())
        }.getOrDefault(0L)
    }
    private fun getContentSummaryByHead(element: Element, headings: HEADINGS): Element? {
        val items = element.select(".post-content_item")
        for (item in items) {
            val header = item.selectFirst("h5")!!.text()
            if (header.contains(headings.text)) {
                return item.selectFirst(".summary-content")
            }
        }

        return null
    }
    private fun parseRelativeDate(date: String): Long {
        val cal = Calendar.getInstance()

        if (date.contains("ago")) {
            val match = Regex("""(\d+)\s(\D+)\s""").find(date)!!
            val number = match.groupValues[1].toInt()
            val datePart = match.groupValues[2]
            when {
                datePart.contains("小时") -> cal.apply { add(Calendar.DAY_OF_YEAR, -number) }
            }
        } else {
            val matches = Regex("""(\d+)(\D+)""").findAll(date)
            for (match in matches) {
                val number = match.groupValues[1].toInt()
                val datePart = match.groupValues[2]
                when {
                    datePart.contains("年") -> cal.set(Calendar.YEAR, number)
                    datePart.contains("月") -> cal.set(Calendar.MONTH, number)
                    datePart.contains("日") -> cal.set(Calendar.DAY_OF_MONTH, number)
                }
            }
        }

        return cal.timeInMillis
    }

    private enum class HEADINGS(val text: String) {
        Rank("Rank"),
        Alternative("Alternative"),
        Author("作者"),
        Character("角色"),
        Series("系列及分类"),
        Date("发布"),
        State("状态"),
    }

    private enum class States(val originalValue: String, val alternateValue: Int) {
        OnGoing("OnGoing", SManga.ONGOING),
        Completed("Completed", SManga.COMPLETED),
        OnHold("On Hold", SManga.ON_HIATUS),
        Canceled("Canceled", SManga.CANCELLED),
        Unknown("Unknown", SManga.UNKNOWN),
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        getPreferencesInternal(screen.context, preferences).forEach(screen::addPreference)
    }

    companion object {
        const val SY_TAG_STYLE = "tagPrefix"
        const val DEFAULT_LIST_PREF = "defaultBaseUrl"
        const val URL_LIST_PREF = "baseUrlList"
        const val URL_INDEX_PREF = "baseUrlIndex"
        const val PREFIX_ID_SEARCH = ""
    }
}
