package com.CXXX

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class DarknessPorn : MainAPI() {
    override var mainUrl = "https://darknessporn.com"
    override var name = "DarknessPorn"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Latest",
        "/118-bdsm/" to "BDSM",
        "/80649-painal/" to "Painal",
        "/80651-femdom/" to "Femdom",
        "/3-horror-porn/" to "Horror",
        "/80650-piss/" to "Piss",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest) =
        newHomePageResponse(
            HomePageList(
                request.name,
                app.get(pageUrl(request.data, page)).document.extractCards(),
                true
            ),
            hasNext = true
        )

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document.extractCards()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1")?.text()
            ?: name
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img")?.imageAttr()
        )
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("p")?.text()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val candidates = linkedSetOf<String>()

        candidates += extractLinkCandidates(document)

        document.select("iframe[src], iframe[data-src], iframe[data-litespeed-src]").forEach { frame ->
            val frameUrl = fixUrlNull(
                frame.attr("src")
                    .ifBlank { frame.attr("data-src") }
                    .ifBlank { frame.attr("data-litespeed-src") }
            ) ?: return@forEach
            candidates += frameUrl
            runCatching {
                candidates += extractLinkCandidates(app.get(frameUrl, referer = data).document)
            }
        }

        document.select("script").forEach { script ->
            val body = script.data().ifBlank { script.html() }
            Regex("""https?:\/\/[^"'\\\s)]+""").findAll(body).forEach { match ->
                fixUrlNull(match.value)?.let(candidates::add)
            }
        }

        var found = false
        candidates.distinct().forEach { url ->
            val lower = url.lowercase()
            when {
                lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mkv") -> {
                    found = true
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = url,
                            type = INFER_TYPE
                        ) {
                            this.referer = data
                            this.quality = getQualityFromName(url).takeIf { it > 0 }
                                ?: Qualities.Unknown.value
                        }
                    )
                }
                looksLikeExtractor(url) -> {
                    found = loadExtractor(url, data, subtitleCallback, callback) || found
                }
            }
        }

        return found
    }

    private fun pageUrl(path: String, page: Int): String {
        val normalized = if (path.startsWith("http")) path else "$mainUrl$path"
        return if (page <= 1) normalized else normalized.trimEnd('/') + "/page/$page/"
    }

    private fun Document.extractCards(): List<SearchResponse> {
        val selectors = listOf(
            "div.video-block",
            "div.video-loop div.video-block",
            "article",
            "div.item",
            "div.post",
            "div.video",
            "div.post-preview-styling",
            ".videos-list a",
            "a[rel=bookmark]"
        )

        return selectors
            .flatMap { select(it) }
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = when {
            hasClass("video-block") -> selectFirst("a.thumb[href], a.infos[href]")
            tagName() == "a" -> this
            else -> selectFirst("a[href]")
        } ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null

        val title = sequenceOf(
            anchor.attr("title"),
            selectFirst("a.infos h2, a.infos h3, h2 a, h3 a")?.text(),
            selectFirst("h1, h2, h3, h4, .title, .entry-title")?.text(),
            selectFirst("img")?.attr("alt")
        ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null

        val poster = fixUrlNull(
            selectFirst("img.video-img, img.mobile-cat-img, img, video")?.imageAttr()
                ?: anchor.selectFirst("img, video")?.imageAttr()
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun Element.imageAttr(): String {
        return attr("src")
            .ifBlank { attr("data-src") }
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("poster") }
            .ifBlank { attr("data-original") }
            .ifBlank { attr("srcset").substringBefore(" ") }
    }

    private fun extractLinkCandidates(document: Document): Set<String> {
        val candidates = linkedSetOf<String>()

        document.select(
            "video source[src], video[src], source[src], iframe[src], iframe[data-src], " +
                "iframe[data-litespeed-src], a[href], [data-src], [data-href]"
        ).forEach { element ->
            fixUrlNull(
                element.attr("src")
                    .ifBlank { element.attr("data-src") }
                    .ifBlank { element.attr("data-litespeed-src") }
                    .ifBlank { element.attr("href") }
                    .ifBlank { element.attr("data-href") }
            )?.let(candidates::add)
        }

        document.select("script[type=application/ld+json]").forEach { script ->
            val parsed = tryParseJson<LinkedDataVideo>(script.data())
            parsed?.contentUrl?.let(::fixUrlNull)?.let(candidates::add)
            parsed?.embedUrl?.let(::fixUrlNull)?.let(candidates::add)
        }

        return candidates
    }

    private fun looksLikeExtractor(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("embed") ||
            lower.contains("player") ||
            lower.contains("stream") ||
            lower.contains("watch") ||
            lower.contains("video")
    }

    data class LinkedDataVideo(
        val contentUrl: String? = null,
        val embedUrl: String? = null
    )
}
