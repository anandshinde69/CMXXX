package com.CXXX

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class AllPornStream : MainAPI() {
    override var mainUrl = "https://allpornstream.com"
    override var name = "AllPornStream"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

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
        val doc = app.get("$mainUrl/?studio=${request.data}").document
        val videos = doc.select("div[data-thumb-id]").mapNotNull {
            val title = it.attr("data-title") ?: return@mapNotNull null
            val href = it.attr("data-href") ?: return@mapNotNull null
            val images = it.attr("data-images")
            val poster = images.split(",").firstOrNull()?.trim('"', '[', ']')?.takeIf { img -> img.startsWith("http") }
            newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = poster }
        }
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = videos, isHorizontalImages = true),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get("$mainUrl$url").document
        val title = doc.select("h1").text() ?: "Unknown"
        val poster = doc.select("img[alt]").firstOrNull()?.attr("src")?.takeIf { it.startsWith("http") }
        val description = doc.select("meta[name=description]").attr("content")
        val videos = mutableListOf<String>()
        
        try {
            val downloadDoc = app.get("$mainUrl$url/download").document
            videos.addAll(
                downloadDoc.select("a[href*=.mp4], a[href*=.m3u8]")
                    .map { it.attr("href") }
                    .filter { it.isNotBlank() && it.startsWith("http") }
            )
        } catch (e: Exception) {
            // Download page may not exist
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, videos.toJson()) {
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
        val videos = data.fromJson<List<String>>()
        videos.map { url ->
            launch {
                loadExtractor(url, mainUrl, subtitleCallback, callback)
            }
        }.joinAll()
        true
    }

    private val gson = Gson()
    private inline fun <reified T> String.fromJson(): T = gson.fromJson(this, object : TypeToken<T>() {}.type)
}
