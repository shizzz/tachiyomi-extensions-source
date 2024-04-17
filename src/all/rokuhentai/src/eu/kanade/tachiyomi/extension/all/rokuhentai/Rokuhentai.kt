package eu.kanade.tachiyomi.extension.all.rokuhentai
import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.randomua.addRandomUAPreferenceToScreen
import eu.kanade.tachiyomi.lib.randomua.getPrefCustomUA
import eu.kanade.tachiyomi.lib.randomua.getPrefUAType
import eu.kanade.tachiyomi.lib.randomua.setRandomUserAgent
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Rokuhentai(
    override val lang: String,
) : ParsedHttpSource(), ConfigurableSource {
    override val baseUrl = "https://rokuhentai.com"
    override val name = "Rokuhentai"
    override val supportsLatest = false
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        addRandomUAPreferenceToScreen(screen)
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private var currentMangaId: String? = null

    private val unknownTitle = "Unknown"
    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("referer", baseUrl)
        .set("sec-fetch-mode", "no-cors")
        .set("sec-fetch-site", "cross-site")

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .setRandomUserAgent(
                userAgentType = preferences.getPrefUAType(),
                customUA = preferences.getPrefCustomUA(),
                filterInclude = listOf("chrome"),
            )
            .rateLimit(1)
            .build()
    }

    override fun chapterFromElement(element: Element) = throw Exception("Not used")

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val chapter = SChapter.create().apply {
            url = "${manga.url}/0"
            name = "Ch. 1"
        }
        return Observable.just(listOf(chapter))
    }

    override fun chapterListSelector() = throw UnsupportedOperationException()

    override fun imageUrlParse(document: Document) = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("Not used")

    override fun latestUpdatesNextPageSelector() = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("Not used")

    override fun latestUpdatesSelector() = throw Exception("Not used")

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        setUrlWithoutDomain("${document.baseUri()}/0")
        title = document.getMangaDetailTitle()
        thumbnail_url = document.selectFirst(".mdc-card__media").getMangaImageUrlFromElement()
        genre = document.parseGenres()

        Log.d("MyActivity", "mangaFromElement.sManga.url: $url")
    }

    override fun pageListParse(document: Document): List<Page> {
        val imgList = document.select(".site-reader__image").mapIndexedTo(ArrayList()) { index, elm ->
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
    override fun popularMangaFromElement(element: Element) = throw Exception("Not used")

    override fun popularMangaNextPageSelector() = throw Exception("Not used")

    override fun popularMangaRequest(page: Int): Request {
        val builder = baseUrl
            .toHttpUrl()
            .newBuilder()

        if (page == 1) {
            currentMangaId = null
        } else {
            builder
                .addQueryParameter("p", currentMangaId)
        }
        return GET(builder.toString(), headers)
    }

    override fun popularMangaSelector() = ".site-manga-card"

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val elements = document.select(popularMangaSelector())
        if (!elements.any()) {
            return MangasPage(listOf(), false)
        }

        val mangas = elements.mapNotNull { element ->
            mangaFromElement(element)
        }

        val sMangas = mangas.map { manga -> manga.sManga }
        val lastMangaId = mangas.last().mangaId
        val hasNextPage = currentMangaId != lastMangaId
        currentMangaId = lastMangaId

        return MangasPage(sMangas, hasNextPage)
    }

    override fun searchMangaFromElement(element: Element) = throw Exception("Not used")

    override fun searchMangaNextPageSelector() = throw Exception("Not used")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isBlank()) {
            return popularMangaRequest(page)
        }
        val builder = baseUrl
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("q", "\"$query\"")

        if (page == 1) {
            currentMangaId = null
        } else {
            builder
                .addQueryParameter("p", currentMangaId)
        }
        return GET(builder.toString(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    private fun Element?.parseGenres(): String {
        val genreBlocks = this?.select(".mdc-chip__text")
        val genres = genreBlocks?.mapNotNull { el -> el.text().trim() }
        return genres?.joinToString(separator = ", ") ?: ""
    }

    private fun mangaFromElement(element: Element): MangaDetDto? {
        val sManga = SManga.create().apply {
            setUrlWithoutDomain(element.getMangaUrlFromElement())
            title = element.getMangaTitle()
            thumbnail_url = element.selectFirst(".mdc-card__media").getMangaImageUrlFromElement()
        }

        return if (sManga.title == unknownTitle) {
            null
        } else {
            MangaDetDto(
                mangaId = element.getMangaUrlFromElement().getMangaIdFromUrl(),
                sManga = sManga,
            )
        }
    }

    private fun Element?.getMangaTitle(): String {
        return this?.selectFirst(".site-manga-card__title--primary")?.text() ?: unknownTitle
    }

    private fun Element?.getMangaDetailTitle(): String {
        return this?.selectFirst(".site-manga-info__title-text > a")?.text() ?: unknownTitle
    }

    private fun Element?.getMangaUrlFromElement(): String {
        return this
            ?.selectFirst("a.mdc-button")
            ?.absUrl("href")
            ?: ""
    }

    private fun Element?.getMangaDetailUrlFromElement(): String {
        return this
            ?.selectFirst(".site-page-card > a")
            ?.absUrl("href")
            ?: ""
    }

    private fun String?.getMangaIdFromUrl(): String {
        val match = Regex("^https://.*/(.*)\$").find(this ?: "")
        val id = match?.groupValues?.get(1)
        return id ?: ""
    }

    private fun Element?.getMangaImageUrlFromElement(): String? {
        val style = this?.attr("style") ?: ""
        val match = Regex("(https:.*)&quot|(https:.*)\"").find(style)
        val url = match?.groupValues?.get(2)
        return if (url?.isEmpty() == true) null else url
    }

    data class MangaDetDto(
        val mangaId: String,
        val sManga: SManga,
    )
}
