package com.CXXX

import android.annotation.SuppressLint
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import java.util.Base64

open class Vidguardto : ExtractorApi() {
    override val name = "Vidguard"
    override val mainUrl = "https://vidguard.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(url)
        val script = res.document.select("script:containsData(eval)").firstOrNull()?.data() ?: return
        val parsed = AppUtils.parseJson<SvgObject>(runJS(script))
        val watchUrl = sigDecode(parsed.stream)

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = watchUrl,
                type = INFER_TYPE
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }

    @SuppressLint("NewApi")
    private fun sigDecode(url: String): String {
        val sig = url.substringAfter("sig=").substringBefore("&")
        var decodedHex = ""
        for (chunk in sig.chunked(2)) {
            val byteValue = Integer.parseInt(chunk, 16) xor 2
            decodedHex += byteValue.toChar()
        }
        val padding = when (decodedHex.length % 4) {
            2 -> "=="
            3 -> "="
            else -> ""
        }
        val decoded = Base64.getDecoder().decode((decodedHex + padding).toByteArray(Charsets.UTF_8))
        var text = String(decoded).dropLast(5).reversed()
        val chars = text.toCharArray()
        for (i in 0 until chars.size - 1 step 2) {
            val tmp = chars[i]
            chars[i] = chars[i + 1]
            chars[i + 1] = tmp
        }
        text = String(chars).dropLast(5)
        return url.replace(sig, text)
    }

    private fun runJS(script: String): String {
        val rhino = Context.enter()
        rhino.optimizationLevel = -1
        val scope: Scriptable = rhino.initSafeStandardObjects()
        scope.put("window", scope, scope)
        return try {
            rhino.evaluateString(scope, script, "JavaScript", 1, null)
            val svg = scope.get("svg", scope)
            if (svg is NativeObject) {
                NativeJSON.stringify(Context.getCurrentContext(), scope, svg, null, null).toString()
            } else {
                Context.toString(svg)
            }
        } catch (e: Exception) {
            Log.e("SxyPrn", "Error executing Vidguard JS", e)
            ""
        } finally {
            Context.exit()
        }
    }

    data class SvgObject(
        val stream: String,
        val hash: String,
    )
}
