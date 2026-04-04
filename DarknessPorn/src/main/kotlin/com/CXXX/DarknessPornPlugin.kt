package com.CXXX

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DarknessPornPlugin : Plugin() {
    override fun load() {
        registerMainAPI(DarknessPorn())
    }
}
