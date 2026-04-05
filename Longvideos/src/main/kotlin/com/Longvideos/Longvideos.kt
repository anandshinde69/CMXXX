package com.megix

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Longvideos : MainAPI() {
    override var mainUrl              = "https://www.longporn.com"
    override var name                 = "Longvideos"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Latest",
        "page/1/views/week" to "Most Viewed",
        "free-videos/anal" to "Anal",
        "video/tag/milf" to "MILF",
        "free-videos/casting" to "Casting",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val section = request.data.trim('/')
        val targetUrl = when {
            section.isBlank() -> if (page <= 1) mainUrl else "$mainUrl/page/$page/"
            section.startsWith("page/") -> "$mainUrl/$section/"
            else -> "$mainUrl/$section/${if (page <= 1) "" else "page/$page/"}"
        }
        val document = app.get(targetUrl).document
        val home     = document.select("div.entry.videothumb, div.entry.flipbook").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
                list    = HomePageList(
                name    = request.name,
                list    = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val anchor    = this.selectFirst("a.img, h3 a, a[href]")!!
        val title     = anchor.attr("title").ifBlank { this.selectFirst("h3 a")?.text().orEmpty() }
        val href      = anchor.attr("href")
        var posterUrl = this.selectFirst("img")?.attr("src").orEmpty()

        if(posterUrl.contains("data:image")) {
            posterUrl = this.selectFirst("img")?.attr("data-src").orEmpty()
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..7) {
            val document = app.get("$mainUrl/page/$i/?s=$query").document
            val results  = document.select("div.entry.videothumb, div.entry.flipbook").mapNotNull { it.toSearchResult() }

            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title       = document.select("meta[property=og:title]").attr("content")
        val poster      = document.select("meta[property='og:image']").attr("content")
        val description = document.select("meta[property=og:description]").attr("content")


        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        var found = false

        val directSources = linkedSetOf<Pair<String, Int>>()
        document.select("video.video-js > source, #video > source, video source, source[src]").forEach {
            val url     = it.attr("src")
            val quality = it.attr("label").replace("p", "").toIntOrNull() ?: Qualities.Unknown.value
            if (url.isBlank()) return@forEach
            if (!url.startsWith("http")) return@forEach
            directSources += url to quality
        }

        directSources.distinctBy { it.first }.forEach { (url, quality) ->
            found = true
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    url,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.quality = quality
                    this.referer = data
                }
            )
        }

        if (!found) {
            val candidateUrls = linkedSetOf<String>()

            listOf(
                "iframe[src]" to "src",
                "iframe[data-src]" to "data-src",
                "iframe[data-litespeed-src]" to "data-litespeed-src",
                "a[href]" to "href",
            ).forEach { (selector, attr) ->
                document.select(selector).forEach { element ->
                    val value = element.attr(attr)
                    val normalized = when {
                        value.isBlank() -> null
                        value.startsWith("//") -> "https:$value"
                        value.startsWith("/") -> fixUrl(value)
                        value.startsWith("http") -> value
                        else -> null
                    }
                    val looksPlayable = normalized?.let {
                        val lower = it.lowercase()
                        lower.contains(".m3u8") ||
                            lower.contains(".mp4") ||
                            lower.contains("eporner.com/embed") ||
                            lower.contains("embed") ||
                            lower.contains("player") ||
                            lower.contains("stream") ||
                            lower.contains("dood") ||
                            lower.contains("vidguard")
                    } == true
                    if (looksPlayable) candidateUrls.add(normalized!!)
                }
            }

            candidateUrls.forEach { url ->
                val lower = url.lowercase()
                if (lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mkv")) {
                    found = true
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = url,
                            type = INFER_TYPE
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = data
                        }
                    )
                } else {
                    found = loadExtractor(url, data, subtitleCallback, callback) || found
                }
            }
        }

        return found
    }
}
