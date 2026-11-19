package com.xinema

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject
import com.lagradost.nicehttp.JsonAsString
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class OppadramaProvider : MainAPI() {
    override var mainUrl = "https://v2.oppadrama.cc"
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
            val seriesEpisodes = episodes.ifEmpty {
                listOf(newEpisode(url) {
                    name = title
                    episode = 1
                })
            }
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, seriesEpisodes) {
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

        fun addCandidate(rawValue: String?) {
            val raw = rawValue?.trim()?.trim('"', '\'', ' ', ';') ?: return
            if (raw.isBlank() || raw.startsWith("javascript:", true) ||
                raw.startsWith("data:", true) || raw == "#") return
            val normalized = resolveUrl(raw.replace("\\/", "/").replace("&amp;", "&"), base)
            if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
                embeds.add(normalized)
            }
        }

        // Mirror selector: nilai dapat berupa URL langsung, Base64, atau HTML iframe.
        doc.select("select.mirror option[value], select[name*=mirror] option[value], select#server option[value]").forEach { option ->
            val value = option.attr("value").trim()
            addCandidate(value)
            val decoded = decodeMirror(value) ?: return@forEach
            val decodedDoc = Jsoup.parse(decoded, base)
            decodedDoc.select("iframe, frame, embed, video source, source[src]").forEach { node ->
                listOf("src", "data-src", "data-litespeed-src", "data-lazy-src").forEach { attr ->
                    addCandidate(node.attr(attr))
                }
            }
            Regex("""https?://[^\s"'<>]+""").findAll(decoded).forEach {
                addCandidate(it.value.trimEnd(')', ']', ',', ';'))
            }
            Regex("""(?:src|url)\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                .findAll(decoded).forEach { addCandidate(it.groupValues[1]) }
        }

        // Semua elemen pemutar, termasuk lazy iframe dan source video.
        doc.select("iframe, frame, embed, video, video source, source[src], object[data]").forEach { node ->
            listOf(
                "src", "data-src", "data-litespeed-src", "data-lazy-src",
                "data-embed", "data-url", "data", "data-original"
            ).forEach { attr -> addCandidate(node.attr(attr)) }
        }

        // Tombol/mirror server yang menyimpan URL pada atribut data-*.
        doc.select(
            ".player, .player-embed, #pembed, #player, #server, .server, .servers, " +
                ".episodelink, .mirror, .download, .dlbox, [data-embed], [data-url]"
        ).forEach { element ->
            listOf("href", "src", "data-src", "data-embed", "data-url", "data-link", "data-href")
                .forEach { attr -> addCandidate(element.attr(attr)) }
            element.select("[href], [src], [data-src], [data-embed], [data-url], [data-link]").forEach { child ->
                listOf("href", "src", "data-src", "data-embed", "data-url", "data-link")
                    .forEach { attr -> addCandidate(child.attr(attr)) }
            }
        }

        // Link server eksplisit, tidak mengambil semua link navigasi situs.
        doc.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            if (Regex("""(?i)(/e/|/embed/|/file/|player|stream|video|watch|kotakajaib|byse|upns|hydra|m3u8|\.mp4)""")
                    .containsMatchIn(href)) addCandidate(href)
        }

        // URL yang tertanam di JSON/JavaScript. Normalisasi slash yang di-escape dahulu.
        val htmlText = response.text
            .replace("\\\\/", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
        val scriptPatterns = listOf(
            Regex("""(?i)["'](?:embedUrl|iframe|src|file|url|link)["']\s*:\s*["']([^"']+)["']"""),
            Regex("""(?i)\b(?:embedUrl|iframe|src|file|url|link)\s*=\s*["']([^"']+)["']"""),
            Regex("""(?i)<iframe[^>]+(?:src|data-src)=["']([^"']+)["']""")
        )
        scriptPatterns.forEach { pattern ->
            pattern.findAll(htmlText).forEach { match ->
                match.groupValues.getOrNull(1)?.let(::addCandidate)
            }
        }

        var emitted = 0
        for (embed in embeds) {
            try {
                when {
                    embed.contains("vidara", true) -> extractVidara(embed, data, subtitleCallback) { link ->
                        callback(link)
                        emitted++
                    }
                    embed.contains("kotakajaib.me", true) ->
                        extractKotakAjaib(embed, data, subtitleCallback) { link ->
                            callback(link)
                            emitted++
                        }
                    embed.contains("byse", true) || embed.contains("bysesukior", true) ->
                        extractByse(embed, subtitleCallback) { link ->
                            callback(link)
                            emitted++
                        }
                    embed.contains("upns", true) || embed.contains("strp2p", true) ||
                        embed.contains("p2pstream", true) ->
                        extractUpns(embed, subtitleCallback) { link ->
                            callback(link)
                            emitted++
                        }
                    embed.contains("abyssplayer", true) || embed.contains("hydrax", true) ||
                        embed.contains("abyss.to", true) || embed.contains("playhydrax", true) ->
                        extractAbyss(embed, data, subtitleCallback) { link ->
                            callback(link)
                            emitted++
                        }
                    else -> loadExtractor(embed, data, subtitleCallback) { link ->
                        callback(link)
                        emitted++
                    }
                }
            } catch (_: Exception) {
                // Satu mirror yang gagal tidak boleh menghentikan mirror berikutnya.
            }
        }
        return emitted > 0
    }

    // Vidara menyediakan streaming_url melalui endpoint JSON /api/stream.
    private suspend fun extractVidara(
        embedUrl: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val fileCode = Regex("""/[ev]/([A-Za-z0-9]+)""")
                .find(embedUrl)?.groupValues?.getOrNull(1)
                ?: embedUrl.substringAfterLast("/").substringBefore("?").takeIf {
                    it.matches(Regex("""[A-Za-z0-9]+"""))
                }
                ?: return false

            val pageText = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to requestHeaders["User-Agent"].orEmpty(),
                    "Referer" to refererUrl
                )
            ).text
            val mirrorOrigin = Regex("""MIRROR_ORIGIN\\s*=\\s*['"]([^'"]+)['"]""")
                .find(pageText)?.groupValues?.getOrNull(1)
            val parsedOrigin = runCatching {
                val uri = URI(embedUrl)
                "${uri.scheme}://${uri.host}"
            }.getOrNull()
            val origin = (mirrorOrigin ?: parsedOrigin ?: "https://vidara.to").trimEnd('/')
            val apiResponse = app.post(
                "$origin/api/stream",
                headers = mapOf(
                    "User-Agent" to requestHeaders["User-Agent"].orEmpty(),
                    "Referer" to embedUrl,
                    "Origin" to origin,
                    "Accept" to "application/json, text/plain, */*",
                    "Content-Type" to "application/json"
                ),
                json = JsonAsString(JSONObject()
                    .put("filecode", fileCode)
                    .put("device", "web")
                    .toString())
            ).text
            val streamJson = JSONObject(apiResponse)
            val streamUrl = streamJson.optString("streaming_url").trim()
            if (!streamUrl.startsWith("http")) return false

            val subtitles = streamJson.optJSONArray("subtitles")
            if (subtitles != null) {
                for (i in 0 until subtitles.length()) {
                    val sub = subtitles.optJSONObject(i) ?: continue
                    val subUrl = sub.optString("file_path").trim()
                    val language = sub.optString("language").trim()
                    if (subUrl.startsWith("http") && language.isNotBlank()) {
                        subtitleCallback(newSubtitleFile(language, subUrl))
                    }
                }
            }

            val links = runCatching {
                M3u8Helper.generateM3u8(
                    "Vidara",
                    streamUrl,
                    "$origin/",
                    headers = mapOf("Referer" to "$origin/")
                )
            }.getOrDefault(emptyList())
            if (links.isNotEmpty()) {
                links.forEach(callback)
            } else {
                callback(newExtractorLink(
                    "Vidara",
                    "Vidara HLS",
                    streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$origin/"
                    this.headers = mapOf("Referer" to "$origin/")
                })
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // KotakAjaib memakai token per-embed dan endpoint source per provider.
    // Referensi implementasi: provider PusatFilm pada ExtremeBoyGG/nonton-indo.
    private suspend fun extractKotakAjaib(
        url: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val share = Regex("""kotakajaib\.me/(?:embed|file)/([A-Za-z0-9_-]+)""")
                .find(url)?.groupValues?.getOrNull(1) ?: return false
            val embedUrl = "https://kotakajaib.me/embed/$share"
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                "Referer" to refererUrl
            )
            val embedDoc = app.get(embedUrl, headers = headers).document
            val player = embedDoc.selectFirst("#kp") ?: return false
            val token = player.attr("data-token").ifBlank { return false }
            val providers = player.select("button[data-p]")
                .map { it.attr("data-p").trim() }
                .filter { it.isNotBlank() && !it.contains("vikingfile", true) }
                .distinct()
                .distinct()

            var found = 0
            for (provider in providers) {
                try {
                    val encodedToken = URLEncoder.encode(token, "UTF-8")
                    val sourceUrl = "https://kotakajaib.me/embed/$share/source/$provider?t=$encodedToken"
                    val sourceText = app.get(
                        sourceUrl,
                        headers = headers + mapOf(
                            "Referer" to embedUrl,
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    ).text
                    val target = JSONObject(sourceText).optString("u").trim()
                    if (target.isBlank()) continue
                    val targetUrl = if (target.startsWith("//")) "https:$target" else target
                    val before = found
                    when {
                        targetUrl.contains("playhydrax.com", true) ||
                            targetUrl.contains("abyssplayer.com", true) ||
                            targetUrl.contains("abyss.to", true) ->
                            if (extractAbyss(targetUrl, embedUrl, subtitleCallback) { link ->
                                    callback(link)
                                    found++
                                }) Unit
                        else -> loadExtractor(targetUrl, embedUrl, subtitleCallback) { link ->
                            callback(link)
                            found++
                        }
                    }
                    if (found == before) continue
                } catch (_: Exception) {
                    // Try the next provider; one unavailable mirror must not stop extraction.
                }
            }
            found > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun extractSrcFromOption(valStr: String): String? {
        if (valStr.isBlank()) return null
        val directMatch = Regex("""src\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(valStr)?.groupValues?.getOrNull(1)
            ?: if (valStr.startsWith("http")) valStr else null
        if (!directMatch.isNullOrBlank()) return directMatch

        return try {
            val decoded = String(android.util.Base64.decode(valStr, android.util.Base64.DEFAULT), Charsets.UTF_8)
            Regex("""src\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE).find(decoded)?.groupValues?.getOrNull(1)?.trim()
                ?: if (decoded.startsWith("http")) decoded.trim() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun base64UrlDecode(str: String): ByteArray {
        var s = str.trim().replace('-', '+').replace('_', '/')
        val pad = s.length % 4
        if (pad > 0) {
            s += "=".repeat(4 - pad)
        }
        return android.util.Base64.decode(s, android.util.Base64.DEFAULT)
    }

    private suspend fun extractByse(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val domain = Regex("""https?://([^/]+)""").find(embedUrl)?.groupValues?.getOrNull(1) ?: return false
            val code = Regex("""/e/([a-zA-Z0-9_-]+)""").find(embedUrl)?.groupValues?.getOrNull(1) ?: return false
            val apiUrl = "https://$domain/api/videos/$code/"

            val resp = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Referer" to embedUrl,
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text.trim()

            val json = JSONObject(resp)
            val playback = json.optJSONObject("playback") ?: return false
            val ivStr = playback.optString("iv").ifBlank { return false }
            val payloadStr = playback.optString("payload").ifBlank { return false }
            val version = playback.optString("version").toIntOrNull() ?: 1
            val keyParts = playback.optJSONArray("key_parts") ?: return false
            if (keyParts.length() < 30) return false

            val idx1 = version
            val idx2 = 31 - version
            if (idx1 < 1 || idx1 > keyParts.length() || idx2 < 1 || idx2 > keyParts.length()) return false

            val part1 = keyParts.getString(idx1 - 1)
            val part2 = keyParts.getString(idx2 - 1)

            val keyBytes = base64UrlDecode(part1) + base64UrlDecode(part2)
            val ivBytes = base64UrlDecode(ivStr)
            val payloadBytes = base64UrlDecode(payloadStr)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val decryptedBytes = cipher.doFinal(payloadBytes)
            val decryptedJson = JSONObject(String(decryptedBytes, Charsets.UTF_8))

            val sources = decryptedJson.optJSONArray("sources") ?: return false
            for (i in 0 until sources.length()) {
                val source = sources.getJSONObject(i)
                val m3u8Url = source.optString("url").ifBlank { null } ?: continue
                val label = source.optString("label", "1080p")
                val quality = when {
                    label.contains("1080") -> Qualities.P1080.value
                    label.contains("720") -> Qualities.P720.value
                    label.contains("480") -> Qualities.P480.value
                    label.contains("360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }

                val links = try {
                    M3u8Helper.generateM3u8(
                        "Byse",
                        m3u8Url,
                        "https://$domain/",
                        headers = mapOf("Referer" to "https://$domain/")
                    )
                } catch (_: Exception) {
                    emptyList()
                }

                if (links.isNotEmpty()) {
                    links.forEach(callback)
                } else {
                    callback(
                        newExtractorLink(
                            "Byse",
                            "Byse $label",
                            m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = quality
                            this.referer = "https://$domain/"
                            this.headers = mapOf("Referer" to "https://$domain/")
                        }
                    )
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractUpns(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val hashId = if (embedUrl.contains("#")) {
                embedUrl.substringAfter("#", "").substringBefore("&").trim()
            } else {
                embedUrl.substringAfterLast("/").substringBefore("?").trim()
            }
            if (hashId.isBlank()) return false

            val domain = Regex("""https?://([^/]+)""").find(embedUrl)?.groupValues?.getOrNull(1) ?: return false
            val apiUrl = "https://$domain/api/v1/video?id=$hashId"

            val hexResp = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Referer" to "https://$domain/",
                    "Accept" to "application/json, text/plain, */*"
                )
            ).text.trim()

            if (hexResp.isBlank() || hexResp.length % 32 != 0) return false

            val decryptedJson = decryptAes128Cbc(hexResp, "kiemtienmua911ca", "1234567890oiuytr") ?: return false
            val json = JSONObject(decryptedJson)
            val masterM3u8 = json.optString("cfNative").ifBlank { null } ?: json.optString("source").ifBlank { null } ?: return false

            val links = try {
                M3u8Helper.generateM3u8(
                    "UPNShare",
                    masterM3u8,
                    "https://$domain/",
                    headers = mapOf("Referer" to "https://$domain/")
                )
            } catch (_: Exception) {
                emptyList()
            }

            if (links.isNotEmpty()) {
                links.forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        "UPNShare",
                        "UPNShare HLS",
                        masterM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://$domain/"
                        this.headers = mapOf("Referer" to "https://$domain/")
                    }
                )
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    private fun decryptAes128Cbc(hex: String, keyStr: String, ivStr: String): String? {
        return try {
            val key = keyStr.toByteArray(Charsets.UTF_8)
            val iv = ivStr.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

            val cipherBytes = ByteArray(hex.length / 2)
            for (i in 0 until hex.length step 2) {
                cipherBytes[i / 2] = hex.substring(i, i + 2).toInt(16).toByte()
            }
            val decryptedBytes = cipher.doFinal(cipherBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun extractAbyss(
        embedUrl: String,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val areq = app.get(
                embedUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Referer" to refererUrl
                )
            ).text

            val enc = Regex("""(?:const|var|let)\s+datas\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(areq)?.groupValues?.getOrNull(1) ?: return false

            extractAbyssSubtitles(enc, subtitleCallback)

            val jsonPayload = JSONObject().put("text", enc).toString()
            val decRes = app.post(
                "https://enc-dec.app/api/dec-abyss",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                    "Content-Type" to "application/json",
                    "Origin" to "https://playhydrax.com",
                    "Referer" to "https://playhydrax.com/"
                ),
                json = JsonAsString(jsonPayload)
            ).text

            val decJson = JSONObject(decRes)
            val result = decJson.optJSONObject("result") ?: return false
            val sources = result.optJSONArray("sources") ?: return false

            var emitted = 0
            for (i in 0 until sources.length()) {
                val srcObj = sources.optJSONObject(i) ?: continue
                if (!srcObj.optBoolean("status", true)) continue
                val srcUrl = srcObj.optString("url")
                if (srcUrl.isBlank() || !srcUrl.startsWith("http")) continue
                val type = srcObj.optString("type")
                val q = when {
                    type.contains("1080") -> Qualities.P1080.value
                    type.contains("720") -> Qualities.P720.value
                    type.contains("480") -> Qualities.P480.value
                    type.contains("360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                callback(
                    newExtractorLink(
                        "HydraX",
                        "HydraX $type",
                        srcUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = q
                        this.referer = "https://abyssplayer.com/"
                        this.headers = mapOf("Referer" to "https://abyssplayer.com/")
                    }
                )
                emitted++
            }
            emitted > 0
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun extractAbyssSubtitles(enc: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            val rawBytes = android.util.Base64.decode(enc, android.util.Base64.DEFAULT)
            val rawJsonStr = String(rawBytes, Charsets.ISO_8859_1)
            val json = JSONObject(rawJsonStr)
            val md5Id = json.optString("md5_id")
            if (md5Id.isBlank()) return

            val subs = json.optJSONObject("config")?.optJSONArray("subtitles") ?: return
            for (i in 0 until subs.length()) {
                val subObj = subs.optJSONObject(i) ?: continue
                val subSlug = subObj.optString("slug")
                if (subSlug.isBlank()) continue
                val subType = subObj.optString("type", "srt").ifBlank { "srt" }
                val rawLang = subObj.optString("lang", "id").ifBlank { "id" }
                val langLabel = when (rawLang.lowercase()) {
                    "id", "ind", "indonesian", "indonesia" -> "Indonesian"
                    "en", "eng", "english" -> "English"
                    "ms", "may", "malay" -> "Malay"
                    else -> rawLang
                }
                val subUrl = "https://cdn.iamcdn.net/subtitle/$md5Id/$subSlug.$subType"
                subtitleCallback(newSubtitleFile(langLabel, subUrl))
            }
        } catch (_: Exception) {
        }
    }
}
