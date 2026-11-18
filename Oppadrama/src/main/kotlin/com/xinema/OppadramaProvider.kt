package com.xinema

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URI

class OppadramaProvider : MainAPI() {
    override var mainUrl = "https://oppa.biz"
    override var name = "Oppadrama"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    private val requestHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cookie" to "user_is_human=true",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "series/?status=&type=&order=update" to "Update Terbaru",
        "series/?status=Ongoing&type=&order=update" to "Ongoing",
        "series/?type=TV+Show&order=update" to "Variety Show",

        "series/?country%5B%5D=south-korea&status=&type=Drama&order=update" to "Drama Korea",
        "series/?country%5B%5D=china&type=Drama&order=update" to "Drama China",
        "series/?country%5B%5D=thailand&status=&type=Drama&order=update" to "Drama Thailand",
        "series/?country%5B%5D=japan&type=Drama&order=update" to "Drama Jepang",
        "series/?country%5B%5D=taiwan&status=&type=Drama&order=update" to "Drama Taiwan",
        "series/?country%5B%5D=philippines&type=Drama&order=update" to "Drama Filipina",
        "series/?country%5B%5D=usa&type=Drama&order=update" to "Drama Barat",
        "network/netflix/" to "Netflix",
        "series/?status=Completed&type=Drama&order=update" to "Completed",

        "series/?country%5B%5D=south-korea&status=&type=Movie&order=update" to "Film Korea",
        "series/?country%5B%5D=china&type=Movie&order=update" to "Film China",
        "series/?country%5B%5D=hong-kong&type=Movie&order=update" to "Film Hong Kong",
        "series/?country%5B%5D=thailand&status=&type=Movie&order=update" to "Film Thailand",
        "series/?country%5B%5D=japan&type=Movie&order=update" to "Film Jepang",
        "series/?country%5B%5D=taiwan&status=&type=Movie&order=update" to "Film Taiwan",
        "series/?country%5B%5D=philippines&type=Movie&order=update" to "Film Filipina",
        "series/?country%5B%5D=india&status=&type=Movie&order=update" to "Film India",
        "series/?country%5B%5D=united-states&status=&type=Movie&order=update" to "Film Barat",

        "series/?genre%5B0%5D=animation&type=&order=update" to "Animasi"
    )

    private fun resolveUrl(value: String, base: String): String =
        runCatching { URI(base).resolve(value).toString() }.getOrDefault(fixUrl(value))

    private fun Element.imageUrl(base: String): String? {
        val image = selectFirst("img") ?: return null
        val value = image.attr("data-src")
            .ifBlank { image.attr("data-lazy-src") }
            .ifBlank { image.attr("abs:srcset").substringBefore(" ") }
            .ifBlank { image.attr("src") }
        return value.takeIf { it.isNotBlank() }?.let { resolveUrl(it, base) }
    }

    private fun Element.toResult(base: String): SearchResponse? {
        val anchor = if (tagName() == "a") this else selectFirst("a[href]") ?: return null
        val href = resolveUrl(anchor.attr("href"), base)
        if (href.isBlank()) return null
        val title = anchor.attr("title").ifBlank {
            selectFirst("div.tt, .tt, h2, h3, img[alt]")?.let {
                it.attr("alt").ifBlank { it.text() }
            }.orEmpty()
        }.ifBlank { anchor.text().trim() }
        if (title.isBlank()) return null
        val poster = imageUrl(base)
        return if (href.contains("/series/", true) || href.contains("drama", true)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    private fun parseListing(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        return doc.select("div.listupd article.bs, article.bs, div.bsx > a[href], div.bsx a[href]")
            .mapNotNull { runCatching { it.toResult(doc.location().ifBlank { mainUrl }) }.getOrNull() }
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val route = request.data.trimStart('/')
            .replace(Regex("([?&])page=[^&]*"), "")
            .trimEnd('&', '?')
        val separator = if (route.contains("?")) "&" else "?"
        val url = "$mainUrl/$route${separator}page=$page"
        val doc = app.get(url, referer = "$mainUrl/", headers = requestHeaders).document
        val items = parseListing(doc)
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get(
            "$mainUrl/?s=$encoded",
            referer = "$mainUrl/",
            headers = requestHeaders,
            timeout = 50L
        ).document
        return parseListing(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/", headers = requestHeaders).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: throw ErrorLoadingException("Judul tidak ditemukan")
        val poster = doc.selectFirst("div.bigcontent img")?.let { image ->
            val value = image.attr("data-src")
                .ifBlank { image.attr("data-lazy-src") }
                .ifBlank { image.attr("src") }
            value.takeIf { it.isNotBlank() }?.let { resolveUrl(it, doc.location().ifBlank { mainUrl }) }
        }
        val episodes = doc.select("div.eplister ul li a[href]").mapNotNull { anchor ->
            val href = anchor.attr("href")
            if (href.isBlank()) null else newEpisode(resolveUrl(href, doc.location().ifBlank { mainUrl })) {
                name = anchor.text().trim().ifBlank { "Episode" }
            }
        }.reversed()
        val plot = doc.select("div.entry-content p").joinToString("
") { it.text() }.trim()
            .ifBlank { doc.selectFirst("div.entry-content")?.text().orEmpty() }
        val contentType = doc.selectFirst("span:matchesOwn(Tipe:)")?.ownText().orEmpty()
        val isSeries = url.contains("/series/", true) ||
            contentType.contains("drama", true) ||
            contentType.contains("series", true) ||
            episodes.size > 1

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.plot = plot
                tags = doc.select("div.genxed a").map { it.text() }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.firstOrNull()?.data ?: url) {
                posterUrl = poster
                this.plot = plot
                tags = doc.select("div.genxed a").map { it.text() }
            }
        }
    }

    private fun Element.iframeUrl(): String? {
        return attr("data-litespeed-src")
            .ifBlank { attr("data-src") }
            .ifBlank { attr("src") }
            .takeIf { it.isNotBlank() }
            ?.let { resolveUrl(it, doc.location().ifBlank { mainUrl }) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = "$mainUrl/", headers = requestHeaders).document
        val iframeUrls = linkedSetOf<String>()

        doc.select("#pembed iframe, div.player-embed iframe").forEach { frame ->
            frame.iframeUrl()?.let { iframeUrls.add(it) }
        }

        doc.select("select.mirror option[value]:not([disabled])").forEach { option ->
            val encoded = option.attr("value").replace("\\s".toRegex(), "")
            if (encoded.isBlank()) return@forEach
            runCatching {
                val decoded = String(
                    android.util.Base64.decode(encoded, android.util.Base64.DEFAULT),
                    Charsets.UTF_8
                )
                val frame = Jsoup.parse(decoded).selectFirst("iframe")
                frame?.iframeUrl()?.let { iframeUrls.add(it) }
            }
        }

        doc.select("div.dlbox li span.e a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            if (href.isNotBlank()) iframeUrls.add(resolveUrl(href, doc.location().ifBlank { mainUrl }))
        }

        iframeUrls.forEach { iframe ->
            runCatching {
                loadExtractor(iframe, data, subtitleCallback, callback)
            }
        }
        return true
    }
}