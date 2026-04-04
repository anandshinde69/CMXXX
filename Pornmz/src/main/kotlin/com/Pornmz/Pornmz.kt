package com.megix

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Pornmz : MainAPI() {
    override var mainUrl              = "https://pornmz.com"
    override var name                 = "Pornmz"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Home",
        "/pmvideo/s/brazzers" to "Brazzers",
        "/pmvideo/s/bangbros" to "BangBros",
        "/pmvideo/s/naughtyamerica" to "NaughtyAmerica",
        "/pmvideo/s/realitykings" to "RealityKings",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl${request.data}/page/$page/").document
        val home     = document.select(".videos-list a").mapNotNull { it.toSearchResult() }

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
        val title     = this.attr("title")
        val href      = this.attr("href")
        var posterUrl = this.select("img").attr("src")
        if(posterUrl.isEmpty()) {
            posterUrl = this.select("video").attr("poster")
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..5) {
            val document = app.get("$mainUrl/page/$i/?s=$query").document
            val results = document.select(".videos-list a").mapNotNull { it.toSearchResult() }

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
        val iframe = fixUrlNull(document.select(".responsive-player iframe").attr("src"))
        val candidates = linkedSetOf<String>()

        iframe?.let { iframeUrl ->
            candidates += iframeUrl
            val iframeDoc = app.get(iframeUrl, referer = data).document
            candidates += iframeDoc.select("video source, video, source, iframe, a").mapNotNull {
                fixUrlNull(
                    it.attr("src")
                        .ifBlank { it.attr("data-src") }
                        .ifBlank { it.attr("data-litespeed-src") }
                        .ifBlank { it.attr("href") }
                )
            }
        }

        var found = false
        candidates.forEach { url ->
            val lower = url.lowercase()
            when {
                lower.contains(".m3u8") || lower.contains(".mp4") -> {
                    found = true
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = url,
                            type = INFER_TYPE
                        ) {
                            this.referer = iframe ?: data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                lower.contains("embed") || lower.contains("player") || lower.contains("stream") || lower.contains("video") -> {
                    found = loadExtractor(url, data, subtitleCallback, callback) || found
                }
            }
        }

        return found
    }
}
