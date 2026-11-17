package com.xinema
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class OploverzProvider : MainAPI() {
 override var mainUrl = "https://oploverz.diy"
 override var name = "Oploverz"
 override var lang = "id"
 override val hasMainPage = true
 override val hasQuickSearch = false
 override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

 private fun Element.toResult(): SearchResponse {
  val a = selectFirst("a") ?: this
  val title = selectFirst(".tt, h2, h3")?.text()?.ifBlank { a.text() } ?: a.text()
  val poster = selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
  return newAnimeSearchResponse(title, a.attr("href"), TvType.Anime) { posterUrl = poster }
 }
 override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
  val doc = app.get("$mainUrl/anime-list/page/$page/").document
  return newHomePageResponse(request.name, doc.select("div.relat > article").mapNotNull { runCatching { it.toResult() }.getOrNull() })
 }
 override suspend fun search(query: String): List<SearchResponse> {
  val doc = app.get("$mainUrl/page/1/?s=${query.replace(" ", "+")}").document
  return doc.select(".site-main.relat > article").mapNotNull { runCatching { it.toResult() }.getOrNull() }
 }
 override suspend fun load(url: String): LoadResponse {
  val doc = app.get(url).document
  val title = doc.selectFirst("h1.entry-title")?.text() ?: throw ErrorLoadingException("Judul anime tidak ditemukan")
  val poster = doc.selectFirst("div.bigcontent img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
  val episodes = doc.select("div.lstepsiode.listeps ul li").map { li ->
   val a = li.selectFirst("a") ?: li
   newEpisode(a.attr("href")) { name = a.text() }
  }.reversed()
  return newAnimeLoadResponse(title, url, TvType.Anime) {
   addEpisodes(DubStatus.Subbed, episodes)
   posterUrl = poster
   plot = doc.selectFirst("div.entry-content")?.text()
   tags = doc.select("div.genxed a").map { it.text() }
  }
 }
 override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
  val doc = app.get(data).document
  for (server in doc.select("div#server ul li div")) {
   val post = server.attr("data-post")
   val nume = server.attr("data-nume")
   val type = server.attr("data-type")
   if (post.isBlank() || nume.isBlank()) continue
   val response = app.post("$mainUrl/wp-admin/admin-ajax.php",
    data = mapOf("action" to "player_ajax", "post" to post, "nume" to nume, "type" to type),
    headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
   val iframe = response.document.selectFirst("iframe")?.attr("src")
    ?: Regex("""src=["']([^"']+)["']""").find(response.text)?.groupValues?.get(1)
   if (!iframe.isNullOrBlank()) loadExtractor(fixUrl(iframe), data, subtitleCallback, callback)
  }
  return true
 }
}