package com.CXXX

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.jsoup.nodes.Document

class AllPornStream : MainAPI() {
    override var mainUrl = "https://allpornstream.com"
    override var name = "AllPornStream"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)
    private val cfInterceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "ElegantAngel" to "Elegant Angel",
        "Blacked" to "Blacked",
        "DadCrush" to "Dad Crush",
        "Shoplyfter" to "Shoplyfter",
        "Tushy" to "Tushy",
        "EvilAngel" to "Evil Angel",
        "SexMex" to "Sex Mex",
        "BrazzersExxtra" to "Brazzers Exxtra",
        "PornWorld" to "Porn World",
        "ATKGirlfriends" to "ATK Girlfriends",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val response = app.get(
            "$mainUrl/?studio=${request.data}",
            referer = mainUrl,
            interceptor = cfInterceptor,
        )
        val doc = response.document
        val videos = parseHomeCards(doc).ifEmpty { parseHomeCardsFromHtml(response.text) }
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = videos, isHorizontalImages = true),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val targetUrl = fixUrl(url)
        val doc = app.get(targetUrl, referer = mainUrl, interceptor = cfInterceptor).document
        val title = doc.select("h1").text().ifBlank { doc.select("meta[property=og:title]").attr("content").ifBlank { "Unknown" } }
        val poster = doc.select("meta[property=og:image]").attr("content")
            .ifBlank { doc.select("img[alt], img[src]").firstOrNull()?.attr("src").orEmpty() }
            .takeIf { it.isNotBlank() }
        val description = doc.select("meta[name=description]").attr("content")
        val videos = extractCandidateUrls(targetUrl, doc)

        return newMovieLoadResponse(title, targetUrl, TvType.NSFW, SourcePayload(targetUrl, videos).toJson()) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val payload = runCatching { data.fromJson<SourcePayload>() }.getOrNull()
        val pageUrl = payload?.pageUrl
        val videos = (payload?.urls ?: runCatching { data.fromJson<List<String>>() }.getOrDefault(emptyList()))
            .ifEmpty { pageUrl?.let { extractCandidateUrls(it) } ?: emptyList() }
            .distinct()

        if (videos.isEmpty()) {
            pageUrl?.let {
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Fallback",
                        url = it,
                        type = INFER_TYPE
                    ) {
                        referer = mainUrl
                        quality = Qualities.Unknown.value
                    }
                )
                return@coroutineScope true
            }
            return@coroutineScope false
        }

        videos.map { url ->
            launch {
                val lower = url.lowercase()
                if (lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".mkv")) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = url,
                            type = INFER_TYPE
                        ) {
                            referer = pageUrl ?: mainUrl
                            quality = Qualities.Unknown.value
                        }
                    )
                } else {
                    loadExtractor(url, pageUrl ?: mainUrl, subtitleCallback, callback)
                }
            }
        }.joinAll()
        true
    }

    private suspend fun extractCandidateUrls(pageUrl: String, existingDoc: Document? = null): List<String> {
        val urls = linkedSetOf<String>()
        val pages = mutableListOf<Pair<Document, String>>()
        val doc = existingDoc ?: app.get(pageUrl, referer = mainUrl, interceptor = cfInterceptor).document
        pages += doc to pageUrl

        extractUrlsFromNextData(doc.html()).forEach(urls::add)

        runCatching {
            val downloadUrl = if (pageUrl.endsWith("/download")) pageUrl else "$pageUrl/download"
            val downloadDoc = app.get(downloadUrl, referer = pageUrl, interceptor = cfInterceptor).document
            pages += downloadDoc to downloadUrl
            extractUrlsFromNextData(downloadDoc.html()).forEach(urls::add)
        }

        pages.forEach { (page, referer) ->
            collectUrlsFromDocument(page, referer, urls)
        }
        return urls.toList()
    }

    private fun parseHomeCards(doc: Document): List<SearchResponse> {
        return doc.select("div[data-thumb-id]").mapNotNull { element ->
            val title = element.attr("data-title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val href = element.attr("data-href").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val poster = extractPosterFromImages(element.attr("data-images"))
            newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }

    private fun parseHomeCardsFromHtml(html: String): List<SearchResponse> {
        val cards = linkedMapOf<String, SearchResponse>()
        val cardRegex = Regex(
            """data-thumb-id\\":\\"[^"]+\\".*?data-href\\":\\"([^"]+)\\".*?data-title\\":\\"([^"]+)\\".*?data-images\\":\\"(\[[^"]*])""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        cardRegex.findAll(html).forEach { match ->
            val href = normalizeEscapedUrl(match.groupValues[1]) ?: return@forEach
            val title = match.groupValues[2]
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("\\\\", "")
                .takeIf(String::isNotBlank)
                ?: return@forEach
            val poster = extractPosterFromImages(
                match.groupValues[3]
                    .replace("\\\"", "\"")
                    .replace("\\\\", "")
            )
            cards[href] = newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
                this.posterUrl = poster
            }
        }
        return cards.values.toList()
    }

    private fun extractPosterFromImages(images: String): String? {
        return Regex("""https?:\/\/[^"'\\\s\]]+""").find(images)?.value
    }

    private fun collectUrlsFromDocument(doc: Document, referer: String, output: MutableSet<String>) {
        val attrSelectors = listOf(
            "a[href]" to "href",
            "iframe[src]" to "src",
            "iframe[data-src]" to "data-src",
            "iframe[data-litespeed-src]" to "data-litespeed-src",
            "iframe[data-lp-src]" to "data-lp-src",
            "source[src]" to "src",
            "video[src]" to "src",
            "[data-url]" to "data-url",
        )

        attrSelectors.forEach { (selector, attr) ->
            doc.select(selector).mapNotNullTo(output) { element ->
                normalizeCandidateUrl(element.attr(attr), referer)
            }
        }

        val text = doc.html()
        Regex("""https?:\/\/[^"'\\\s<]+""").findAll(text).forEach { match ->
            normalizeCandidateUrl(match.value, referer)?.let(output::add)
        }
    }

    private fun extractUrlsFromNextData(html: String): List<String> {
        val urls = linkedSetOf<String>()
        val patterns = listOf(
            Regex("""https?:\\?/\\?/[^"\\]+"""),
            Regex(""""embed_url\\":\\"(https?:\\\\/\\\\/[^"]+)""""),
            Regex(""""link\\":\\[(.*?)\]"""),
        )

        patterns.forEach { regex ->
            regex.findAll(html).forEach { match ->
                match.groupValues.drop(1).ifEmpty { listOf(match.value) }.forEach { value ->
                    Regex("""https?:\\?/\\?/[^"'\\\s<\]]+""").findAll(value).forEach { nested ->
                        normalizeEscapedUrl(nested.value)?.let(urls::add)
                    }
                }
            }
        }

        return urls.toList()
    }

    private fun normalizeEscapedUrl(raw: String): String? {
        val cleaned = raw
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\\", "")
            .trim('"')
        return normalizeCandidateUrl(cleaned, "")
    }

    private fun normalizeCandidateUrl(url: String, referer: String): String? {
        val normalized = when {
            url.isBlank() -> return null
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> fixUrl(url)
            else -> url
        }

        if (!normalized.startsWith("http")) return null
        val lower = normalized.lowercase()
        val looksPlayable = lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".mkv") ||
            lower.contains("streamtape") ||
            lower.contains("bigwarp") ||
            lower.contains("dood") ||
            lower.contains("vidguard") ||
            lower.contains("embed") ||
            lower.contains("player") ||
            lower.contains("watch") ||
            lower.contains("file=") ||
            lower.contains("download")

        if (!looksPlayable) return null
        if (normalized == referer) return null
        return normalized
    }

    private data class SourcePayload(
        val pageUrl: String,
        val urls: List<String>,
    )

    private val gson = Gson()
    private inline fun <reified T> String.fromJson(): T = gson.fromJson(this, object : TypeToken<T>() {}.type)
}
