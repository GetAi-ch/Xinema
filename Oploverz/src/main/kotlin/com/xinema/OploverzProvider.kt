package com.xinema

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class OploverzProvider : MainAPI() {
    override var mainUrl = "https://oploverz.diy"
    override var name = "Oploverz"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val requestHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    )

    private fun resolveUrl(value: String, base: String): String {
        if (value.isBlank()) return ""
        return runCatching { URI(base).resolve(value.trim()).toString() }
            .getOrDefault(fixUrl(value.trim()))
    }

    private fun Element.imageUrl(base: String): String? {
        val image = if (tagName() == "img") this else selectFirst("img") ?: return null
        val value = listOf("data-src", "data-lazy-src", "data-original", "src")
            .firstNotNullOfOrNull { image.attr(it).takeIf(String::isNotBlank) }
            ?: image.attr("srcset").substringBefore(" ").takeIf(String::isNotBlank)
            ?: return null
        return resolveUrl(value, base).takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }

    private fun Element.toResult(base: String): SearchResponse? {
        val anchor = selectFirst("div.animposx > a[href], a[href]") ?: return null
        val href = resolveUrl(anchor.attr("href"), base)
        if (href.isBlank()) return null
        val title = selectFirst("div.title > h2, .tt h2, .tt, h2, h3")?.text()?.trim()
            ?.takeIf(String::isNotBlank)
            ?: anchor.attr("title").takeIf(String::isNotBlank)
            ?: anchor.text().trim().takeIf(String::isNotBlank)
            ?: return null
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = imageUrl(base)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val requestedUrl = "$mainUrl/anime-list/page/$page/"
        val doc = app.get(requestedUrl, referer = "$mainUrl/", headers = requestHeaders).document
        val base = doc.location().takeIf { it.startsWith("http") } ?: requestedUrl
        val items = doc.select(
            "div.relat > article, .site-main.relat > article, div.relat article, div.bsx"
        ).mapNotNull { runCatching { it.toResult(base) }.getOrNull() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "$mainUrl/anime-list/page/1/?title=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/?s=$encoded"
        )
        for (url in urls) {
            val doc = runCatching {
                app.get(url, referer = "$mainUrl/", headers = requestHeaders).document
            }.getOrNull() ?: continue
            val base = doc.location().takeIf { it.startsWith("http") } ?: url
            val results = doc.select(
                "div.relat > article, .site-main.relat > article, div.bsx, article.bs"
            ).mapNotNull { runCatching { it.toResult(base) }.getOrNull() }
                .distinctBy { it.url }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        var finalUrl = url
        var doc = app.get(finalUrl, referer = "$mainUrl/", headers = requestHeaders).document
        val currentBase = doc.location().takeIf { it.startsWith("http") } ?: finalUrl

        // Jika link yang dibuka adalah halaman episode, cari halaman induk series.
        if (!finalUrl.contains("/series/", true)) {
            val seriesHref = doc.selectFirst(
                "div.nvs.nvsc a[href], a[aria-label*=Episode], a[href*='/series/']"
            )?.attr("href")
            if (!seriesHref.isNullOrBlank()) {
                finalUrl = resolveUrl(seriesHref, currentBase)
                doc = app.get(finalUrl, referer = currentBase, headers = requestHeaders).document
            }
        }

        val base = doc.location().takeIf { it.startsWith("http") } ?: finalUrl
        val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?.replace(Regex("\\s*Subtitle\\s*Indonesia.*", RegexOption.IGNORE_CASE), "")
            ?.trim()?.takeIf(String::isNotBlank)
            ?: throw ErrorLoadingException("Judul anime tidak ditemukan")

        val poster = doc.selectFirst(
            "div.bigcontent img, .infoanime img, div.thumb img, img[src*=upload]"
        )?.imageUrl(base)
        val episodes = doc.select(
            "div.lstepsiode.listeps > ul li, div.eplister ul li, ul#episodelist li, .episodelist li"
        ).mapNotNull { li ->
            val anchor = li.selectFirst("span.eps > a[href], a[href]") ?: return@mapNotNull null
            val epUrl = resolveUrl(anchor.attr("href"), base)
            if (epUrl.isBlank()) return@mapNotNull null
            val epName = li.selectFirst("span.lchx a, div.epl-title, .epl-title")
                ?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: anchor.text().trim().takeIf(String::isNotBlank)
                ?: "Episode"
            val epNumber = li.selectFirst(".epl-num, .epx")?.text()
                ?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
                ?: Regex("(?i)episode[-\\s]*(\\d+)").find(epName)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(epUrl) {
                name = epName
                episode = epNumber
            }
        }.distinctBy { it.data }.reversed()

        return newAnimeLoadResponse(title, finalUrl, TvType.Anime) {
            addEpisodes(DubStatus.Subbed, episodes)
            posterUrl = poster
            plot = doc.selectFirst("div.entry-content, div.synp p")?.text()?.trim()
            tags = doc.select("div.genxed a, a[href*=genres]").map { it.text().trim() }
                .filter(String::isNotBlank)
        }
    }

    private fun decodeBase64(value: String): String? = runCatching {
        val cleaned = value.trim().replace(Regex("\\s+"), "")
        String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()?.takeIf(String::isNotBlank)

    private suspend fun extractBloggerStreams(
        iframeUrl: String,
        data: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val body = app.get(iframeUrl, referer = data, headers = requestHeaders).text
        val jsonText = Regex("""(?s)(\\{.*?"streams"\\s*:\\s*\\[.*?\\].*?\\})""")
            .find(body)?.groupValues?.get(1) ?: return false
        val streams = runCatching { JSONObject(jsonText).optJSONArray("streams") }.getOrNull()
            ?: return false
        var found = false
        for (index in 0 until streams.length()) {
            val stream = streams.optJSONObject(index) ?: continue
            val videoUrl = stream.optString("play_url").takeIf(String::isNotBlank) ?: continue
            val formatId = stream.optString("format_id")
            val quality = when (formatId) {
                "18" -> Qualities.P360.value
                "22" -> Qualities.P720.value
                else -> Qualities.Unknown.value
            }
            callback(newExtractorLink(
                name,
                if (quality == Qualities.Unknown.value) name else "$name ${if (formatId == "18") "360p" else "720p"}",
                videoUrl
            ) {
                this.quality = quality
                referer = iframeUrl
            })
            found = true
        }
        return found
    }

    private suspend fun handlePlayerUrl(
        url: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.startsWith("http")) return
        if (url.contains("blogger", true)) {
            val parsed = runCatching { extractBloggerStreams(url, data, callback) }.getOrDefault(false)
            if (!parsed) loadExtractor(url, data, subtitleCallback, callback)
        } else {
            loadExtractor(url, data, subtitleCallback, callback)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, referer = "$mainUrl/", headers = requestHeaders)
        val doc = response.document
        val base = doc.location().takeIf { it.startsWith("http") } ?: data
        val players = linkedSetOf<String>()

        // Server dinamis yang memerlukan AJAX.
        doc.select("#server [data-post][data-nume], #server .east_player_option").forEach { server ->
            val post = server.attr("data-post")
            val nume = server.attr("data-nume")
            if (post.isBlank() || nume.isBlank()) return@forEach
            val ajax = runCatching {
                app.post(
                    resolveUrl("/wp-admin/admin-ajax.php", base),
                    data = mapOf(
                        "action" to "player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to server.attr("data-type")
                    ),
                    headers = requestHeaders + ("X-Requested-With" to "XMLHttpRequest"),
                    referer = data
                )
            }.getOrNull() ?: return@forEach
            val iframe = ajax.document.selectFirst("iframe[src], iframe[data-src]")
                ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                ?: Regex("""(?:src|data-src)=["']([^"']+)["']""")
                    .find(ajax.text)?.groupValues?.getOrNull(1)
            if (!iframe.isNullOrBlank()) players.add(resolveUrl(iframe, base))
        }

        // Tombol server alternatif dari tema Oploverz.
        doc.select("button.dsu-server").forEach { button ->
            button.attr("data-url").takeIf(String::isNotBlank)?.let {
                players.add(resolveUrl(it, base))
            }
            button.attr("data-embed").takeIf(String::isNotBlank)?.let { encoded ->
                val decoded = decodeBase64(encoded) ?: return@let
                val parsed = Jsoup.parse(decoded, base)
                parsed.select("iframe[src], iframe[data-src]").forEach { frame ->
                    val src = frame.attr("src").ifBlank { frame.attr("data-src") }
                    if (src.isNotBlank()) players.add(resolveUrl(src, base))
                }
                Regex("""https?://[^\\s"'<>]+""").findAll(decoded).forEach {
                    players.add(it.value.trimEnd(')', ']', ','))
                }
            }
        }

        // Iframe biasa dan mirror yang dienkode Base64.
        doc.select(
            "#pembed iframe, .player-embed iframe, .video-content iframe, iframe"
        ).forEach { frame ->
            val src = listOf("src", "data-src", "data-lazy-src")
                .firstNotNullOfOrNull { frame.attr(it).takeIf(String::isNotBlank) }
            if (src != null) players.add(resolveUrl(src, base))
        }
        doc.select("select.mirror option[value], select[name=mirror] option[value]").forEach { option ->
            val decoded = decodeBase64(option.attr("value")) ?: return@forEach
            val parsed = Jsoup.parse(decoded, base)
            parsed.select("iframe[src], iframe[data-src], frame[src]").forEach { frame ->
                val src = frame.attr("src").ifBlank { frame.attr("data-src") }
                if (src.isNotBlank()) players.add(resolveUrl(src, base))
            }
            Regex("""https?://[^\\s"'<>]+""").findAll(decoded).forEach {
                players.add(it.value.trimEnd(')', ']', ','))
            }
        }

        // Link download langsung.
        doc.select("a[href*=gofile.io], a.dsu-download[href]").forEach { anchor ->
            val href = resolveUrl(anchor.attr("href"), base)
            if (href.startsWith("http")) players.add(href)
        }

        for (player in players) {
            runCatching { handlePlayerUrl(player, data, subtitleCallback, callback) }
        }
        return players.isNotEmpty()
    }
}
