package com.xinema
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
@CloudstreamPlugin
class OppadramaPlugin : Plugin() {
 override fun load() { registerMainAPI(OppadramaProvider()) }
}