package com.xinema
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class OppadramaProvider : MainAPI() {
 override var mainUrl = "https://oppa.biz"
 override var name = "Oppadrama"
 override val lang = "id"
 override val hasMainPage = true
 override val hasSearch = true
 override val hasQuickSearch = false
 override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

 private fun Element.toResult(): SearchResponse {
  val a = selectFirst("a") ?: this
  val title = selectFirst(".tt, .entry-title, h2, h3")?.text()?.ifBlank { a.text() } ?: a.text()
  val poster = selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
  return newAnimeSearchResponse(title, a.attr("href"), TvType.AsianDrama) { posterUrl = poster }
 }
 override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
  val doc = app.get("$mainUrl/series/?page=$page").document
  return newHomePageResponse(request.name, doc.select("article.bs, article, .bsx").mapNotNull { runCatching { it.toResult() }.getOrNull() })
 }
 override suspend fun search(query: String): List<SearchResponse> {
  val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
  return doc.select("article.bs, article, .bsx").mapNotNull { runCatching { it.toResult() }.getOrNull() }
 }
 override suspend fun load(url: String): LoadResponse {
  val doc = app.get(url).document
  val title = doc.selectFirst("h1.entry-title")?.text() ?: throw ErrorLoadingException("Judul tidak ditemukan")
  val poster = doc.selectFirst("div.bigcontent img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
  val episodes = doc.select("div.eplister ul li a").map { newEpisode(it.attr("href"), it.text()) }.reversed()
  val plot = doc.selectFirst("div.entry-content")?.text()
  val type = if (episodes.size > 1) TvType.TvSeries else TvType.Movie
  return newTvSeriesLoadResponse(title, url, type, episodes) {
   posterUrl = poster
   this.plot = plot
   tags = doc.select("div.genxed a").map { it.text() }
  }
 }
 override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
  val doc = app.get(data).document
  doc.select("div.player-embed iframe").forEach { frame ->
   val src = frame.attr("src")
   if (src.isNotBlank()) loadExtractor(fixUrl(src), data, subtitleCallback, callback)
  }
  doc.select("select.mirror option").forEach { option ->
   runCatching {
    val decoded = String(android.util.Base64.decode(option.attr("value"), android.util.Base64.DEFAULT))
    val src = org.jsoup.Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")
    if (!src.isNullOrBlank()) loadExtractor(fixUrl(src), data, subtitleCallback, callback)
   }
  }
  return true
 }
}