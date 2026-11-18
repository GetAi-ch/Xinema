package com.xinema

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

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
        "series/?country%5B%5D=taiwan&type=Movie&order=update" to "Film Taiwan",
        "series/?country%5B%5D=philippines&type=Movie&order=update" to "Film Filipina",
        "series/?country%5B%5D=india&status=&type=Movie&order=update" to "Film India",
        "series/?country%5B%5D=united-states&status=&type=Movie&order=update" to "Film Barat",
        "series/?genre%5B0%5D=animation&type=&order=update" to "Animasi"
    )

    private fun resolveUrl(value: String, base: String): String {
        if (value.isBlank()) return ""
        return runCatching { URI(base).resolve(value.trim()).toString() }
            .getOrDefault(fixUrl(value.trim()))
    }

    private fun pageBase(doc: org.jsoup.nodes.Document, fallback: String): String =
        doc.location().takeIf { it.startsWith("http") } ?: fallback

    private fun Element.posterUrl(base: String): String? {
        val img = if (tagName() == "img") this else selectFirst("img") ?: return null
        val raw = listOf("data-src", "data-lazy-src", "data-original", "src")
            .firstNotNullOfOrNull { img.attr(it).takeIf(String::isNotBlank) }
            ?: img.attr("srcset").substringBefore(" ").takeIf(String::isNotBlank)
            ?: return null
        return resolveUrl(raw, base).takeIf {
            it.isNotBlank() && !it.startsWith("data:")
        }
    }

    private fun Element.toResult(base: String): SearchResponse? {
        val anchor = if (tagName() == "a" && hasAttr("href")) this
            else selectFirst("a[href]") ?: return null
        val href = resolveUrl(anchor.attr("href"), base)
        if (href.isBlank()) return null

        val title = selectFirst(".tt h2, .tt, h2, h3")?.text()?.trim()
            ?.takeIf(String::isNotBlank)
            ?: anchor.attr("title").takeIf(String::isNotBlank)
            ?: selectFirst("img")?.attr("alt")?.takeIf(String::isNotBlank)
            ?: anchor.text().trim().takeIf(String::isNotBlank)
            ?: return null

        val poster = posterUrl(base)
        val typeText = selectFirst(".typez")?.text()?.trim().orEmpty()
        val type = when {
            typeText.equals("Movie", true) -> TvType.Movie
            typeText.equals("TV Show", true) -> TvType.TvSeries
            href.contains("/movie-", true) -> TvType.Movie
            else -> TvType.AsianDrama
        }
        return newMovieSearchResponse(title, href, type) { posterUrl = poster }
    }

    private fun parseListing(doc: org.jsoup.nodes.Document, base: String): List<SearchResponse> =
        doc.select(".listupd .bsx, .bs .bsx, .listupd article.bs, article.bs, div.bsx > a[href], div.bsx a[href]")
            .mapNotNull { runCatching { it.toResult(base) }.getOrNull() }
            .distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val route = request.data.trimStart('/')
            .replace(Regex("([?&])page=[^&]*"), "")
            .trimEnd('&', '?')
        val separator = if (route.contains("?")) "&" else "?"
        val requestedUrl = "$mainUrl/$route${separator}page=$page"
        val doc = app.get(requestedUrl, referer = "$mainUrl/", headers = requestHeaders).document
        val base = pageBase(doc, requestedUrl)
        val items = parseListing(doc, base)
        return newHomePageResponse(HomePageList(request.name, items), hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val requestedUrl = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val doc = app.get(requestedUrl, referer = "$mainUrl/", headers = requestHeaders, timeout = 50L).document
        return parseListing(doc, pageBase(doc, requestedUrl))
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/", headers = requestHeaders).document
        val base = pageBase(doc, url)
        val title = doc.selectFirst(
            ".infolimit h2, h1.entry-title, h1[itemprop=name], h1.title"
        )?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.takeIf(String::isNotBlank)
            ?: throw ErrorLoadingException("Judul tidak ditemukan")

        val poster = doc.selectFirst(
            ".bigcontent .thumb img, .bigcontent img, .single-info .thumb img, .poster img"
        )?.posterUrl(base)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf(String::isNotBlank)?.let { resolveUrl(it, base) }

        val plotElement = doc.selectFirst(
            ".single-info .desc.mindes, .desc.mindes, .entry-content .desc, .entry-content"
        )
        val plot = plotElement?.text()?.trim()
        val typeText = doc.selectFirst(".spe span:contains(Tipe), span:matchesOwn(Tipe:)")?.text().orEmpty()
        val episodeElements = doc.select(
            ".eplister ul li, .bxcl ul li, #chapterlist ul li, .episodelist ul li, #singlepisode .episodelist ul li, div.eplister li"
        )
        val episodes = episodeElements.mapNotNull { item ->
            val anchor = item.selectFirst("a[href]") ?: return@mapNotNull null
            val epUrl = resolveUrl(anchor.attr("href"), base)
            if (epUrl.isBlank()) return@mapNotNull null
            val epName = item.selectFirst(".epl-title, .playinfo h4, .lchx")?.text()?.trim()
                ?.takeIf(String::isNotBlank)
                ?: anchor.attr("title").takeIf(String::isNotBlank)
                ?: anchor.text().trim().takeIf(String::isNotBlank)
                ?: "Episode"
            val epNumber = Regex("""(?i)(?:eps|episode|ep)\s*(\d+)""")
                .find(epName)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode(epUrl) {
                name = epName
                episode = epNumber
            }
        }.distinctBy { it.data }.reversed()

        val isMovie = typeText.contains("movie", true) ||
            title.startsWith("Movie ", true) || url.contains("/movie-", true)

        return if (isMovie) {
            val versions = if (episodes.isNotEmpty()) episodes else listOf(newEpisode(url) { name = title })
            newMovieLoadResponse(title, url, TvType.Movie, versions.first().data) {
                posterUrl = poster
                this.plot = plot
                tags = doc.select(".genxed a, .genre a").map { it.text().trim() }
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                posterUrl = poster
                this.plot = plot
                tags = doc.select(".genxed a, .genre a").map { it.text().trim() }
            }
        }
    }

    private fun Element.frameUrl(base: String): String? {
        val raw = listOf("data-litespeed-src", "data-src", "data-lazy-src", "src")
            .firstNotNullOfOrNull { attr(it).takeIf(String::isNotBlank) }
            ?: return null
        return resolveUrl(raw, base).takeIf { it.startsWith("http") }
    }

    private fun decodeMirror(value: String): String? = runCatching {
        val cleaned = value.trim().replace(Regex("\\s+"), "")
        String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()?.takeIf(String::isNotBlank)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, referer = "$mainUrl/", headers = requestHeaders)
        val doc = response.document
        val base = pageBase(doc, data)
        val embeds = linkedSetOf<String>()

        // Mirror Base64: format HTML iframe, URL langsung, atau iframe yang atributnya lazy.
        doc.select("select.mirror option[value], select[name=mirror] option[value]").forEach { option ->
            val encoded = option.attr("value")
            if (encoded.isBlank()) return@forEach
            val decoded = decodeMirror(encoded) ?: return@forEach
            val decodedDoc = Jsoup.parse(decoded, base)
            decodedDoc.select("iframe, frame").forEach { frame ->
                frame.frameUrl(base)?.let(embeds::add)
            }
            Regex("""https?://[^\s"'<>]+""").findAll(decoded).forEach {
                val candidate = it.value.trimEnd(')', ']', ',')
                if (candidate.contains("://")) embeds.add(candidate)
            }
        }

        // Pemutar utama dan variasi markup yang digunakan tema Oppadrama.
        doc.select(
            ".player-embed iframe, #pembed iframe, .mvelement iframe, .megavid iframe, iframe"
        ).forEach { frame ->
            frame.frameUrl(base)?.let(embeds::add)
        }

        // Beberapa halaman menaruh link server di kotak download.
        doc.select(".dlbox li a[href], .download a[href]").forEach { anchor ->
            val href = resolveUrl(anchor.attr("href"), base)
            if (href.startsWith("http")) embeds.add(href)
        }

        var emitted = 0
        for (embed in embeds) {
            runCatching {
                loadExtractor(embed, data, subtitleCallback) { link ->
                    callback(link)
                    emitted++
                }
            }
        }
        return emitted > 0
    }
}
