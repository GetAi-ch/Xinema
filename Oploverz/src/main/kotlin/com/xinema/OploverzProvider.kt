package com.xinema

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
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
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    )

    private fun Element.imageUrl(): String? {
        val image = selectFirst("img") ?: return null
        val value = image.attr("data-src")
            .ifBlank { image.attr("data-lazy-src") }
            .ifBlank { image.attr("abs:srcset").substringBefore(" ") }
            .ifBlank { image.attr("src") }
        return value.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
    }

    private fun Element.toResult(): SearchResponse? {
        val anchor = selectFirst("div.animposx > a[href], a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))
        if (href.isBlank()) return null
        val title = selectFirst("div.title > h2, .tt, h2, h3")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").ifBlank { anchor.text().trim() }
        if (title.isBlank()) return null
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = imageUrl()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(
            "$mainUrl/anime-list/page/$page/",
            referer = "$mainUrl/",
            headers = requestHeaders
        ).document
        val items = doc.select("div.relat > article")
            .mapNotNull { runCatching { it.toResult() }.getOrNull() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "$mainUrl/anime-list/page/1/?title=$encoded",
            "$mainUrl/page/1/?s=$encoded"
        )
        for (url in urls) {
            val doc = runCatching {
                app.get(url, referer = "$mainUrl/", headers = requestHeaders).document
            }.getOrNull() ?: continue
            val results = doc.select("div.relat > article, .site-main.relat > article")
                .mapNotNull { runCatching { it.toResult() }.getOrNull() }
                .distinctBy { it.url }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/", headers = requestHeaders).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: throw ErrorLoadingException("Judul anime tidak ditemukan")
        val poster = doc.selectFirst("div.bigcontent img, .infoanime img")?.let { image ->
            val value = image.attr("data-src")
                .ifBlank { image.attr("data-lazy-src") }
                .ifBlank { image.attr("src") }
            value.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }
        val episodes = doc.select("div.lstepsiode.listeps > ul li").mapNotNull { li ->
            val episodeAnchor = li.selectFirst("span.eps > a[href]")
                ?: li.selectFirst("a[href]") ?: return@mapNotNull null
            val episodeName = li.selectFirst("span.lchx > a")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: episodeAnchor.text().trim()
            val episodeUrl = episodeAnchor.attr("href")
            if (episodeUrl.isBlank()) null else newEpisode(fixUrl(episodeUrl)) {
                name = episodeName.ifBlank { "Episode" }
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            addEpisodes(DubStatus.Subbed, episodes)
            posterUrl = poster
            plot = doc.selectFirst("div.entry-content")?.text()
            tags = doc.select("div.genxed a").map { it.text() }
        }
    }

    private suspend fun extractBloggerStreams(
        iframeUrl: String,
        data: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val body = app.get(iframeUrl, referer = data, headers = requestHeaders).text
        val jsonText = body.substringAfter("= ", body)
            .substringBefore("</")
            .trim()
            .let { candidate ->
                if (candidate.startsWith("{")) candidate
                else Regex("""(?s)(\{.*?"streams"\s*:.*?\})""")
                    .find(body)?.groupValues?.get(1)
            } ?: return false

        val streams = runCatching {
            JSONObject(jsonText).optJSONArray("streams")
        }.getOrNull() ?: return false

        var found = false
        for (index in 0 until streams.length()) {
            val stream = streams.optJSONObject(index) ?: continue
            val videoUrl = stream.optString("play_url").takeIf { it.isNotBlank() } ?: continue
            val formatId = stream.optString("format_id")
            val quality = when (formatId) {
                "18" -> Qualities.P360.value
                "22" -> Qualities.P720.value
                else -> Qualities.Unknown.value
            }
            callback(
                newExtractorLink(
                    name,
                    if (quality == Qualities.Unknown.value) "Oploverz" else "Oploverz ${if (formatId == "18") "360p" else "720p"}",
                    videoUrl
                ) {
                    this.quality = quality
                    referer = iframeUrl
                }
            )
            found = true
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = "$mainUrl/", headers = requestHeaders).document
        val servers = doc.select(
            "#server > ul > li > div[data-post][data-nume], #server > ul > li > div.east_player_option"
        ).distinctBy { "${it.attr("data-post")}:${it.attr("data-nume")}" }

        for (server in servers) {
            val post = server.attr("data-post")
            val nume = server.attr("data-nume")
            val type = server.attr("data-type")
            if (post.isBlank() || nume.isBlank()) continue

            val response = runCatching {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    headers = requestHeaders + ("X-Requested-With" to "XMLHttpRequest"),
                    referer = data
                )
            }.getOrNull() ?: continue

            val iframe = response.document.selectFirst("iframe.playeriframe, iframe")
                ?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                ?: Regex("""(?:src|data-src)=["']([^"']+)["']""")
                    .find(response.text)?.groupValues?.get(1)
                ?: continue
            val iframeUrl = fixUrl(iframe)
            if (iframeUrl.contains("blogger", ignoreCase = true)) {
                val parsed = runCatching {
                    extractBloggerStreams(iframeUrl, data, callback)
                }.getOrDefault(false)
                if (!parsed) loadExtractor(iframeUrl, data, subtitleCallback, callback)
            } else {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        return true
    }
}