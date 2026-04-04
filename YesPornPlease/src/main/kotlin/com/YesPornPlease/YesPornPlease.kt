package com.megix

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class YesPornPlease : MainAPI() {
    override var mainUrl              = "https://yespornpleasexxx.com"
    override var name                 = "YesPornPlease"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}" to "Home",
        "${mainUrl}/xnxx/small-tits/" to "Small Tits",
        "${mainUrl}/xnxx/teen/" to "Teen",
        "${mainUrl}/xnxx/threesome/" to "Threesome",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}/page/${page}/").document
        val home = document.select("div.post-preview-styling").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("a")?.attr("title") ?:""
        val href = this.selectFirst("a")?.attr("href") ?:""
        var posterUrl = this.selectFirst("a > img")?.attr("data-src") ?: ""
        if(posterUrl.isEmpty()) {
            posterUrl = this.selectFirst("a > img")?.attr("src") ?:""
        }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..5) {
            val document = app.get("${mainUrl}/page/${i}/?s=${query}").document

            val results = document.select("div.post-preview-styling").mapNotNull { it.toSearchResult() }

            searchResponse.addAll(results)

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content") ?:""
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content") ?:""
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
        ): Boolean {

        val document = app.get(data).document
        val iframe = fixUrlNull(
            document.select("#post > div.wp-video > div > iframe").attr("data-litespeed-src")
                .ifBlank { document.select("#post > div.wp-video > div > iframe").attr("src") }
        )
        val candidates = linkedSetOf<String>()

        iframe?.let { iframeUrl ->
            candidates += iframeUrl
            val iframeDoc = app.get(iframeUrl, referer = data).document
            candidates += iframeDoc.select("video a, video source, video, source, iframe, a").mapNotNull {
                fixUrlNull(
                    it.attr("href")
                        .ifBlank { it.attr("src") }
                        .ifBlank { it.attr("data-src") }
                        .ifBlank { it.attr("data-litespeed-src") }
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
