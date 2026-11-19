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
        return try {
            val body = app.get(iframeUrl, referer = data, headers = requestHeaders).text
            var found = false
            val scripts = Jsoup.parse(body).select("script")
            for (script in scripts) {
                val scriptText = script.data().ifBlank { script.html() }
                val marker = Regex(""""streams"\s*:\s*\[""", RegexOption.IGNORE_CASE).find(scriptText)
                    ?: continue
                val arrayStart = marker.range.last + 1
                val arrayEnd = scriptText.indexOf(']', arrayStart)
                if (arrayEnd <= arrayStart) continue
                val arrayText = scriptText.substring(arrayStart, arrayEnd)
                val streams = runCatching {
                    JSONObject("""{"streams":[$arrayText]}""").optJSONArray("streams")
                }.getOrNull() ?: continue
                for (index in 0 until streams.length()) {
                    val source = streams.optJSONObject(index) ?: continue
                    val videoUrl = source.optString("play_url").trim()
                    if (!videoUrl.startsWith("http")) continue
                    val formatId = source.optInt("format_id", -1)
                    val quality = when (formatId) {
                        18 -> Qualities.P360.value
                        22 -> Qualities.P720.value
                        else -> Qualities.Unknown.value
                    }
                    callback(newExtractorLink(
                        name,
                        if (quality == Qualities.Unknown.value) "Blogger" else "Blogger " + quality.toString() + "p",
                        videoUrl
                    ) {
                        this.quality = quality
                        this.referer = "https://www.youtube.com/"
                    })
                    found = true
                }
                if (found) break
            }
            found
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun handlePlayerUrl(
        url: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        if (!url.startsWith("http")) return 0
        var emitted = 0
        val emit: (ExtractorLink) -> Unit = { link ->
            callback(link)
            emitted++
        }
        when {
            url.contains("blogger.com", true) -> {
                loadExtractor(url, data, subtitleCallback, emit)
                if (emitted == 0) {
                    runCatching { extractBloggerStreams(url, data, emit) }
                }
            }
            url.contains("vkspeed.com", true) -> {
                runCatching {
                    val html = app.get(url, referer = data, headers = requestHeaders).text
                    val unpacked = getPacked(html) ?: html
                    Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""")
                        .findAll(unpacked).map { it.value.trimEnd(')', ']', ',') }.distinct().forEach { stream ->
                            emit(newExtractorLink("VKSpeed", "VKSpeed MP4", stream, type = ExtractorLinkType.VIDEO) {
                                this.referer = "https://vkspeed.com/"
                            })
                        }
                    Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                        .findAll(unpacked).map { it.value.trimEnd(')', ']', ',') }.distinct().forEach { stream ->
                            emit(newExtractorLink("VKSpeed", "VKSpeed HLS", stream, type = ExtractorLinkType.M3U8) {
                                this.referer = "https://vkspeed.com/"
                            })
                        }
                }
            }
            url.contains("xtwap.top", true) -> {
                runCatching {
                    val html = app.get(url, referer = data, headers = requestHeaders).text
                    Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                        .findAll(html).map { it.value.trimEnd(')', ']', ',') }.distinct().forEach { stream ->
                            emit(newExtractorLink("XTWAP", "XTWAP HLS", stream, type = ExtractorLinkType.M3U8) {
                                this.referer = "https://play.xtwap.top/"
                            })
                        }
                    val direct = Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.getOrNull(1)
                    if (!direct.isNullOrBlank()) {
                        val stream = resolveUrl(direct, url)
                        if (stream.startsWith("http")) emit(newExtractorLink("XTWAP", "XTWAP Video", stream, type = ExtractorLinkType.VIDEO) {
                            this.referer = "https://play.xtwap.top/"
                        })
                    }
                }
            }
            else -> runCatching { loadExtractor(url, data, subtitleCallback, emit) }
        }
        return emitted
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

        fun addPlayer(raw: String?) {
            val value = raw?.trim()?.trim('"', '\'', ' ', ';') ?: return
            if (value.isBlank() || value.startsWith("javascript:", true) ||
                value.startsWith("data:", true) || value == "#") return
            val normalized = resolveUrl(value.replace("\\/", "/").replace("&amp;", "&"), base)
            if (normalized.startsWith("http://") || normalized.startsWith("https://")) players.add(normalized)
        }

        fun decodePlayerValue(value: String) {
            addPlayer(value)
            val decoded = decodeBase64(value) ?: return
            val parsed = Jsoup.parse(decoded, base)
            parsed.select("iframe, frame, embed, source, video").forEach { node ->
                listOf("src", "data-src", "data-lazy-src", "data-litespeed-src", "data-url").forEach {
                    addPlayer(node.attr(it))
                }
            }
            Regex("""https?://[^\s"'<>]+""").findAll(decoded).forEach {
                addPlayer(it.value.trimEnd(')', ']', ',', ';'))
            }
            Regex("""(?:src|url)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(decoded).forEach { addPlayer(it.groupValues[1]) }
        }

        // AJAX player tabs (WordPress player_ajax / dsu-server patterns).
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
                    headers = requestHeaders + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    ),
                    referer = data
                )
            }.getOrNull() ?: return@forEach
            val ajaxDoc = ajax.document
            ajaxDoc.select("iframe, frame, embed, source").forEach { frame ->
                listOf("src", "data-src", "data-lazy-src", "data-litespeed-src").forEach {
                    addPlayer(frame.attr(it))
                }
            }
            Regex("""(?:src|data-src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(ajax.text).forEach { addPlayer(it.groupValues[1]) }
        }

        // Theme server buttons and encoded embed values.
        doc.select("button.dsu-server, [data-embed], [data-url]").forEach { button ->
            addPlayer(button.attr("data-url"))
            button.attr("data-embed").takeIf(String::isNotBlank)?.let(::decodePlayerValue)
        }

        // Direct/lazy iframe and mirror options.
        doc.select("iframe, frame, embed, video, video source, source[src]").forEach { frame ->
            listOf("src", "data-src", "data-lazy-src", "data-litespeed-src", "data-url").forEach {
                addPlayer(frame.attr(it))
            }
        }
        doc.select("select.mirror option[value], select[name=mirror] option[value], select#server option[value]").forEach { option ->
            decodePlayerValue(option.attr("value"))
        }

        // Server links and download mirrors only; avoid collecting ordinary site navigation.
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            if (Regex("""(?i)(gofile\.io|/e/|/embed/|/file/|player|stream|video|watch|blogger|vkspeed|xtwap|\.m3u8|\.mp4)""")
                    .containsMatchIn(href)) addPlayer(href)
        }

        // JSON and inline scripts can hold escaped iframe URLs not represented in the DOM.
        val sourceText = response.text.replace("\\\\/", "/").replace("\\/", "/").replace("&amp;", "&")
        val patterns = listOf(
            Regex("""(?i)["'](?:embedUrl|iframe|src|file|url|link)["']\s*:\s*["']([^"']+)["']"""),
            Regex("""(?i)\b(?:embedUrl|iframe|src|file|url|link)\s*=\s*["']([^"']+)["']"""),
            Regex("""(?i)<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""")
        )
        patterns.forEach { pattern ->
            pattern.findAll(sourceText).forEach { match -> addPlayer(match.groupValues[1]) }
        }

        var emitted = 0
        for (player in players) {
            emitted += runCatching {
                handlePlayerUrl(player, data, subtitleCallback) { link ->
                    callback(link)
                }
            }.getOrDefault(0)
        }
        return emitted > 0
    }

}
