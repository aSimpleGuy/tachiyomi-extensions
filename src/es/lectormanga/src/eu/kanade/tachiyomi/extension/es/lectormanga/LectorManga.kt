package eu.kanade.tachiyomi.extension.es.lectormanga

import android.app.Application
import android.content.SharedPreferences
import android.support.v7.preference.ListPreference
import android.support.v7.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Note: this source is similar to TuMangaOnline.
 */
class LectorManga : ConfigurableSource, ParsedHttpSource() {

    //Info

    override val name = "LectorManga"
    override val baseUrl = "https://lectormanga.com"
    override val lang = "es"
    override val supportsLatest = true

    //Client

    private val rateLimitInterceptor = RateLimitInterceptor(1)
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addNetworkInterceptor(rateLimitInterceptor)
        .connectTimeout(1, TimeUnit.MINUTES)
        .readTimeout(1, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.132 Safari/537.36"
    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", userAgent)
            .add("Referer", "$baseUrl/")
    }

    private fun getBuilder(url: String, headers: Headers, formBody: FormBody?, method: String): String {
        val req = Request.Builder()
            .headers(headers)
            .url(url)
            .method(method, formBody)
            .build()

        return client.newCall(req)
            .execute()
            .request()
            .url()
            .toString()
    }

    //Popular

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/library?order_item=likes_count&order_dir=desc&type=&filter_by=title&page=$page", headers)
    override fun popularMangaNextPageSelector() = ".pagination .page-item:not(.disabled) a[rel='next']"
    override fun popularMangaSelector() = ".col-6 .card"
    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.select("a").attr("href"))
        title = element.select("a").text()
        thumbnail_url = element.select("img").attr("src")
    }


    //Latest

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun latestUpdatesSelector() = "div.table-responsive:first-child td[scope=row]:nth-child(5n-3)"
    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(latestUpdatesSelector())
            .distinctBy { it.select("td").text().trim() }
            .map { latestUpdatesFromElement(it) }
        val hasNextPage = false
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesFromElement(element: Element): SManga {

        val manga = SManga.create()
        manga.setUrlWithoutDomain(element.select("a").first().attr("href"))
        manga.title = element.select("td").text().trim()
        return manga
    }

    //Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = HttpUrl.parse("$baseUrl/library")!!.newBuilder()

        url.addQueryParameter("title", query)
        url.addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is Types -> {
                    url.addQueryParameter("type", filter.toUriPart())
                }
                is Demography -> {
                    url.addQueryParameter("demography", filter.toUriPart())
                }
                is FilterBy -> {
                    url.addQueryParameter("filter_by", filter.toUriPart())
                }
                is OrderBy -> {
                    url.addQueryParameter("order_item", filter.toUriPart())
                }
                is OrderDir -> {
                    url.addQueryParameter("order_dir", filter.toUriPart())
                }
                is WebcomicFilter -> {
                    url.addQueryParameter("webcomic", when (filter.state) {
                        Filter.TriState.STATE_INCLUDE -> "true"
                        Filter.TriState.STATE_EXCLUDE -> "false"
                        else -> ""
                    })
                }
                is FourKomaFilter -> {
                    url.addQueryParameter("yonkoma", when (filter.state) {
                        Filter.TriState.STATE_INCLUDE -> "true"
                        Filter.TriState.STATE_EXCLUDE -> "false"
                        else -> ""
                    })
                }
                is AmateurFilter -> {
                    url.addQueryParameter("amateur", when (filter.state) {
                        Filter.TriState.STATE_INCLUDE -> "true"
                        Filter.TriState.STATE_EXCLUDE -> "false"
                        else -> ""
                    })
                }
                is EroticFilter -> {
                    url.addQueryParameter("erotic", when (filter.state) {
                        Filter.TriState.STATE_INCLUDE -> "true"
                        Filter.TriState.STATE_EXCLUDE -> "false"
                        else -> ""
                    })
                }
                is GenreList -> {
                    filter.state
                        .filter { genre -> genre.state }
                        .forEach { genre -> url.addQueryParameter("genders[]", genre.id) }
                }
            }
        }

        return GET(url.build().toString(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    //Details

    override fun mangaDetailsParse(document: Document) = SManga.create().apply {
        genre = document.select("a.py-2").joinToString(", ") {
            it.text()
        }
        description = document.select(".col-12.mt-2")?.text()
        status = parseStatus(document.select(".status-publishing")?.text().orEmpty())
        thumbnail_url = document.select(".text-center img.img-fluid").attr("src")
    }

    private fun parseStatus(status: String) = when {
        status.contains("Publicándose") -> SManga.ONGOING
        status.contains("Finalizado") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    //Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        time = serverTime() //Get time when the chapter page is opened

        val document = response.asJsoup()
        val chapterUrl = response.request().url().toString()

        // One-shot
        if (document.select("#chapters").isEmpty()) {
            return document.select(oneShotChapterListSelector()).map { oneShotChapterFromElement(it, chapterUrl) }
        }

        // Regular list of chapters
        val chapters = mutableListOf<SChapter>()
        val dupselect = getduppref()!!
        val chapterNames = document.select("#chapters h4.text-truncate")
        val chapterNumbers = chapterNames.map { it.text().substringAfter("Capítulo").substringBefore("|").trim().toFloat() }
        val chapterInfos = document.select("#chapters .chapter-list")
        chapterNames.forEachIndexed { index, _ ->
            val scanlator = chapterInfos[index].select("li")
            if (dupselect == "one") {
                scanlator.last { chapters.add(regularChapterFromElement(chapterNames[index].text(), it, chapterNumbers[index], chapterUrl)) }
            } else {
                scanlator.forEach { chapters.add(regularChapterFromElement(chapterNames[index].text(), it, chapterNumbers[index], chapterUrl)) }
            }
        }
        return chapters
    }

    override fun chapterListSelector() = throw UnsupportedOperationException("Not used")
    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException("Not used")

    private fun oneShotChapterListSelector() = "div.chapter-list-element > ul.list-group li.list-group-item"

    private fun oneShotChapterFromElement(element: Element, chapterUrl: String) = SChapter.create().apply {
        url = "$chapterUrl#${element.select("div.row > .text-right > form").attr("id")}"
        name = "One Shot"
        scanlator = element.select("div.col-md-6.text-truncate")?.text()
        date_upload = element.select("span.badge.badge-primary.p-2").first()?.text()?.let { parseChapterDate(it) }
            ?: 0
    }

    private fun regularChapterFromElement(chapterName: String, info: Element, number: Float, chapterUrl: String): SChapter {
        val chapter = SChapter.create()
        chapter.url = "$chapterUrl#${info.select("div.row > .text-right > form").attr("id")}"
        chapter.name = chapterName
        chapter.scanlator = info.select("div.col-md-6.text-truncate")?.text()
        chapter.date_upload = info.select("span.badge.badge-primary.p-2").first()?.text()?.let { parseChapterDate(it) }
            ?: 0
        chapter.chapter_number = number
        return chapter
    }

    private fun parseChapterDate(date: String): Long = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date)?.time
        ?: 0

    //Utilities
    
    private var time = serverTime() //Grab time at app launch, can be updated
    private fun serverTime(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("GMT+1") //Convert time to match server
        return formatter.format(Date())
    }

    //Pages

    override fun pageListRequest(chapter: SChapter): Request {

        val (chapterURL, chapterID) = chapter.url.split("#")
        val response = client.newCall(GET(chapterURL, headers)).execute() //Get chapter page for current token
        if (!response.isSuccessful) throw Exception("Lector Manga HTTP Error ${response.code()}")
        val document = response.asJsoup()
        val geturl = document.select("form#$chapterID").attr("action") + "/$time" //Get redirect URL
        val token = document.select("form#$chapterID input").attr("value") //Get token
        val method = document.select("form#$chapterID").attr("method") //Check POST or GET
        time = serverTime() //Update time for next chapter

        val getHeaders = headersBuilder()
            .add("User-Agent", userAgent)
            .add("Referer", chapterURL)
            .add("Content-Type", "application/x-www-form-urlencoded")
            .build()

        val formBody = when (method) {
            "GET" -> null
            "POST" -> FormBody.Builder()
                .add("_token", token)
                .build()
            else -> throw UnsupportedOperationException("Lector Manga something else broke.")
        }

        val newurl = getBuilder(geturl, getHeaders, formBody, method)

        // Get /cascade instead of /paginate to get all pages at once
        val url = if (getPageMethod() == "cascade" && newurl.contains("paginated")) {
            newurl.substringBefore("paginated") + "cascade"
        } else if (getPageMethod() == "paginated" && newurl.contains("cascade")) {
            newurl.substringBefore("cascade") + "paginated"
        } else newurl

        val headers = headersBuilder()
            .add("User-Agent", userAgent)
            .add("Referer", newurl)
            .build()

        return GET(url, headers)
    }

    override fun pageListParse(document: Document): List<Page> = mutableListOf<Page>().apply {
        if (getPageMethod() == "cascade") {
            document.select("img.viewer-img").forEach {
                add(Page(size, "", it.let {
                    if (it.hasAttr("data-src"))
                        it.attr("abs:data-src") else it.attr("abs:src")
                }))
            }
        } else {
            val pageList = document.select("#viewer-pages-select").first().select("option").map { it.attr("value").toInt() }
            val url = document.baseUri().substringBefore("/paginated") //Accounts for url ending in number "/paginated/1"
            pageList.forEach {
                add(Page(it, "$url/paginated/$it"))
            }
        }
    }

    override fun imageUrlParse(document: Document): String = document.select("img.viewer-image").attr("src")

    //Filters

    private class Types : UriPartFilter("Tipo", arrayOf(
        Pair("Ver todo", ""),
        Pair("Manga", "manga"),
        Pair("Manhua", "manhua"),
        Pair("Manhwa", "manhwa"),
        Pair("Novela", "novel"),
        Pair("One shot", "one_shot"),
        Pair("Doujinshi", "doujinshi"),
        Pair("Oel", "oel")
    ))

    private class Demography : UriPartFilter("Demografía", arrayOf(
        Pair("Ver todo", ""),
        Pair("Seinen", "seinen"),
        Pair("Shoujo", "shoujo"),
        Pair("Shounen", "shounen"),
        Pair("Josei", "josei"),
        Pair("Kodomo", "kodomo")
    ))

    private class FilterBy : UriPartFilter("Ordenar por", arrayOf(
        Pair("Título", "title"),
        Pair("Autor", "author"),
        Pair("Compañia", "company")
    ))

    private class OrderBy : UriPartFilter("Ordenar por", arrayOf(
        Pair("Me gusta", "likes_count"),
        Pair("Alfabético", "alphabetically"),
        Pair("Puntuación", "score"),
        Pair("Creación", "creation"),
        Pair("Fecha estreno", "release_date")
    ))

    private class OrderDir : UriPartFilter("Ordenar por", arrayOf(
        Pair("DESC", "desc"),
        Pair("ASC", "asc")
    ))

    private class WebcomicFilter : Filter.TriState("Webcomic")
    private class FourKomaFilter : Filter.TriState("Yonkoma")
    private class AmateurFilter : Filter.TriState("Amateur")
    private class EroticFilter : Filter.TriState("Erótico")

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)
    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Géneros", genres)

    override fun getFilterList() = FilterList(
        Types(),
        Demography(),
        Filter.Separator(),
        FilterBy(),
        OrderBy(),
        OrderDir(),
        Filter.Separator(),
        WebcomicFilter(),
        FourKomaFilter(),
        AmateurFilter(),
        EroticFilter(),
        GenreList(getGenreList())
    )

    // Array.from(document.querySelectorAll('#advancedSearch .custom-checkbox')).map(a => `Genre("${a.querySelector('label').innerText}", "${a.querySelector('input').value}")`).join(',\n')
    // on https://lectormanga.com/library
    private fun getGenreList() = listOf(
        Genre("Acción", "1"),
        Genre("Aventura", "2"),
        Genre("Comedia", "3"),
        Genre("Drama", "4"),
        Genre("Recuentos de la vida", "5"),
        Genre("Ecchi", "6"),
        Genre("Fantasia", "7"),
        Genre("Magia", "8"),
        Genre("Sobrenatural", "9"),
        Genre("Horror", "10"),
        Genre("Misterio", "11"),
        Genre("Psicológico", "12"),
        Genre("Romance", "13"),
        Genre("Ciencia Ficción", "14"),
        Genre("Thriller", "15"),
        Genre("Deporte", "16"),
        Genre("Girls Love", "17"),
        Genre("Boys Love", "18"),
        Genre("Harem", "19"),
        Genre("Mecha", "20"),
        Genre("Supervivencia", "21"),
        Genre("Reencarnación", "22"),
        Genre("Gore", "23"),
        Genre("Apocalíptico", "24"),
        Genre("Tragedia", "25"),
        Genre("Vida Escolar", "26"),
        Genre("Historia", "27"),
        Genre("Militar", "28"),
        Genre("Policiaco", "29"),
        Genre("Crimen", "30"),
        Genre("Superpoderes", "31"),
        Genre("Vampiros", "32"),
        Genre("Artes Marciales", "33"),
        Genre("Samurái", "34"),
        Genre("Género Bender", "35"),
        Genre("Realidad Virtual", "36"),
        Genre("Ciberpunk", "37"),
        Genre("Musica", "38"),
        Genre("Parodia", "39"),
        Genre("Animación", "40"),
        Genre("Demonios", "41"),
        Genre("Familia", "42"),
        Genre("Extranjero", "43"),
        Genre("Niños", "44"),
        Genre("Realidad", "45"),
        Genre("Telenovela", "46"),
        Genre("Guerra", "47"),
        Genre("Oeste", "48")
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // Preferences

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val deduppref = androidx.preference.ListPreference(screen.context).apply {
            key = DEDUP_PREF_Title
            title = DEDUP_PREF_Title
            entries = arrayOf("All scanlators", "One scanlator per chapter")
            entryValues = arrayOf("all", "one")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(DEDUP_PREF, entry).commit()
            }
        }

        val pageMethod = androidx.preference.ListPreference(screen.context).apply {
            key = PAGEGET_PREF_Title
            title = PAGEGET_PREF_Title
            entries = arrayOf("Cascada", "Paginada")
            entryValues = arrayOf("cascade", "paginated")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(PAGEGET_PREF, entry).commit()
            }
        }
        screen.addPreference(deduppref)
        screen.addPreference(pageMethod)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val deduppref = ListPreference(screen.context).apply {
            key = DEDUP_PREF_Title
            title = DEDUP_PREF_Title
            entries = arrayOf("All scanlators", "One scanlator per chapter")
            entryValues = arrayOf("all", "one")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(DEDUP_PREF, entry).commit()
            }
        }

        val pageMethod = ListPreference(screen.context).apply {
            key = PAGEGET_PREF_Title
            title = PAGEGET_PREF_Title
            entries = arrayOf("Cascada", "Paginada")
            entryValues = arrayOf("cascade", "paginated")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = this.findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(PAGEGET_PREF, entry).commit()
            }
        }
        screen.addPreference(deduppref)
        screen.addPreference(pageMethod)

    }

    private fun getduppref() = preferences.getString(DEDUP_PREF, "all")
    private fun getPageMethod() = preferences.getString(PAGEGET_PREF, "cascade")


    companion object {
        private const val DEDUP_PREF_Title = "Chapter List Scanlator Preference"
        private const val DEDUP_PREF = "deduppref"
        private const val PAGEGET_PREF_Title = "Método para obtener imágenes"
        private const val PAGEGET_PREF = "pagemethodpref"
    }
}
