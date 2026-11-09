package com.truelockmc.streambert

import android.util.Base64
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

// Native port of src/ipc/hianime.js (the desktop resolver is the source of truth).
// Flow: search HTML -> episode list API -> server list API -> ZokoAnime data-hash
// (base64 embed URL) -> embed page `window.__P` blob -> base64 + XOR
// "otaku-embed-v1" -> { src: master.m3u8, subtitles }. Playback is out of scope.
@CapacitorPlugin(name = "HiAnime")
class HiAnimePlugin : Plugin() {
    private val baseUrl = "https://hianime.at"
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val obfuscationKey = "otaku-embed-v1".toByteArray(Charsets.UTF_8)
    private val serverName = "ZokoAnime"
    private val maxTextBytes = 5 * 1024 * 1024

    @PluginMethod
    fun resolve(call: PluginCall) {
        val title = asString(call.getString("title"), 300)
        if (title.isEmpty()) {
            call.resolve(error("Missing title"))
            return
        }
        val isMovie = call.getBoolean("isMovie", false) == true
        val season = asPositiveInt(call.getInt("seasonNumber", 1))
        val episodeNumber = asPositiveInt(call.getInt("episodeNumber", 1))
        val mode = if (call.getString("translationType") == "dub") "dub" else "sub"

        try {
            report(call, "search")

            // S2+ have their own HiAnime entry; AniList also splits episode overflow.
            val seasonInfo = if (isMovie) {
                SeasonInfo(title, null, null, null, null)
            } else {
                anilistSeasonTitle(title, season)
            }
            var searchTitle = seasonInfo.title
            var adjustedEpisode = episodeNumber
            if (!isMovie && seasonInfo.episodes != null &&
                episodeNumber > seasonInfo.episodes && seasonInfo.nextTitle != null
            ) {
                adjustedEpisode = episodeNumber - seasonInfo.episodes
                searchTitle = seasonInfo.nextTitle
            }

            val candidates = LinkedHashSet<String>()
            candidates.add(searchTitle)
            candidates.add(sanitizeTitle(searchTitle))
            if (seasonInfo.romaji != null && searchTitle == seasonInfo.title) {
                candidates.add(seasonInfo.romaji)
            }
            if (seasonInfo.nextRomaji != null && searchTitle == seasonInfo.nextTitle) {
                candidates.add(seasonInfo.nextRomaji)
            }
            candidates.add(title)
            candidates.add(sanitizeTitle(title))
            val usable = candidates.filter { it.isNotBlank() }

            val show = findShow(usable, call)
            if (show == null) {
                call.resolve(error("No results for: $searchTitle"))
                return
            }

            report(call, "episodes")
            val episodes = episodes(show.id)
            if (episodes.isEmpty()) {
                call.resolve(error("No episodes found for: ${show.title}"))
                return
            }
            val wanted = if (isMovie) "1" else adjustedEpisode.toString()
            val episode = episodes.find { it.number == wanted }
                ?: episodes.find { it.number.toFloatOrNull() == wanted.toFloatOrNull() }
                ?: (if (isMovie) episodes.first() else null)
            if (episode == null) {
                call.resolve(error("Episode $wanted not found"))
                return
            }

            report(call, "stream")
            val stream = stream(episode.epId, mode)

            call.resolve(JSObject().apply {
                put("ok", true)
                put("url", stream.masterUrl)
                put("resolution", "auto")
                put("sourceName", serverName)
                put("isDirectMp4", false)
                put("referer", stream.referer)
                put("subtitle", stream.subtitle ?: JSONObject.NULL)
                put("malId", stream.malId ?: JSONObject.NULL)
                put("searchTitle", searchTitle)
                put("matchedTitle", show.title)
            })
        } catch (e: Exception) {
            call.resolve(error((e.message ?: e.toString()).take(200)))
        }
    }

    private fun report(call: PluginCall, stage: String) {
        if (call.isReleased) return
        notifyListeners("progress", JSObject().put("stage", stage))
    }

    private fun error(message: String) = JSObject().apply {
        put("ok", false)
        put("error", message)
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private data class HttpResponse(val status: Int, val body: String)

    private fun assertPublicUrl(urlStr: String): URL {
        val url = try {
            URL(urlStr)
        } catch (_: Exception) {
            throw Exception("Invalid url")
        }
        if (url.protocol != "https" && url.protocol != "http") {
            throw Exception("Blocked protocol")
        }
        val host = url.host.trim('[', ']')
        if (host == "localhost" || host.endsWith(".localhost")) {
            throw Exception("Blocked address")
        }
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (_: Exception) {
            throw Exception("Blocked address")
        }
        if (addresses.isEmpty() || addresses.any { isPrivateAddress(it) }) {
            throw Exception("Blocked address")
        }
        return url
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val b = address.address
        if (b.size == 4) {
            val a0 = b[0].toInt() and 0xff
            val a1 = b[1].toInt() and 0xff
            return a0 == 0 || a0 >= 224 || (a0 == 100 && a1 in 64..127)
        }
        return false
    }

    private fun httpGet(
        urlStr: String,
        referer: String? = null,
        accept: String = "*/*",
    ): HttpResponse {
        var current = assertPublicUrl(urlStr)
        var hops = 0
        while (true) {
            val conn = current.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept", accept)
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                if (referer != null) conn.setRequestProperty("Referer", referer)

                val status = conn.responseCode
                if (status in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location != null && hops < 8) {
                        current = assertPublicUrl(URL(current, location).toString())
                        hops++
                        continue
                    }
                }
                val input = if (status in 200..299) conn.inputStream else conn.errorStream
                return HttpResponse(status, input?.use { readLimited(it) } ?: "")
            } catch (_: SocketTimeoutException) {
                throw Exception("timeout")
            } catch (e: Exception) {
                if (e.message == "response too large" || e.message == "timeout") throw e
                throw Exception(e.message ?: "Network error")
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun readLimited(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxTextBytes) throw Exception("response too large")
            out.write(buffer, 0, read)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hianimeCurl(
        urlStr: String,
        referer: String? = null,
        accept: String = "*/*",
    ): String {
        val res = httpGet(urlStr, referer, accept)
        if (Regex("<title>\\s*Just a moment", RegexOption.IGNORE_CASE).containsMatchIn(res.body)) {
            throw Exception("Blocked by Cloudflare (HiAnime)")
        }
        if (res.status < 200 || res.status >= 300) {
            throw Exception("Request failed: HTTP ${res.status} from $urlStr")
        }
        return res.body
    }

    // ── Scraping ────────────────────────────────────────────────────────────

    private data class SearchResult(val id: String, val title: String, val jname: String?)

    private fun search(query: String): List<SearchResult> {
        val q = URLEncoder.encode(query, "UTF-8")
        val html = hianimeCurl("$baseUrl/search?keyword=$q", accept = "text/html")
        val main = html.split("id=\"main-sidebar\"")[0]
        val results = mutableListOf<SearchResult>()
        val re = Regex("<h3 class=\"film-name\">([\\s\\S]*?)</h3>")
        for (match in re.findAll(main)) {
            val anchor = Regex("<a\\s+([^>]*)>").find(match.groupValues[1]) ?: continue
            val attrs = anchor.groupValues[1]
            val href = attr(attrs, "href") ?: continue
            val resultTitle = attr(attrs, "title") ?: continue
            val id = href.split("?")[0].trimEnd('/').split("/").lastOrNull() ?: continue
            if (id.isEmpty()) continue
            results.add(
                SearchResult(
                    id,
                    decodeEntities(resultTitle),
                    attr(attrs, "data-jname")?.let { decodeEntities(it) },
                ),
            )
        }
        return results
    }

    private fun episodes(showId: String): List<Episode> {
        val numericId = showId.substringAfterLast('-')
        if (!Regex("^\\d+$").matches(numericId)) throw Exception("Invalid show id")
        val body = hianimeCurl("$baseUrl/api/theme/episode/list/$numericId", referer = "$baseUrl/")
        val html = unwrapApiHtml(body)
        val episodes = mutableListOf<Episode>()
        for (chunk in html.split("ep-item").drop(1)) {
            val number = attr(chunk, "data-number") ?: continue
            val epId = attr(chunk, "data-id") ?: continue
            if (!Regex("^\\d+$").matches(epId)) continue
            if (!chunk.contains("/watch/$showId?ep=")) continue
            episodes.add(Episode(epId, number))
        }
        return episodes
    }

    private data class Episode(val epId: String, val number: String)

    private data class Show(val id: String, val title: String)

    private fun findShow(candidates: List<String>, call: PluginCall): Show? {
        val normalized = candidates.map { normalizeTitle(it) }.filter { it.isNotEmpty() }.toSet()
        var fallback: SearchResult? = null
        for (candidate in candidates.distinctBy { normalizeTitle(it) }) {
            if (call.isReleased) return null
            val results = try {
                search(candidate)
            } catch (e: Exception) {
                if ((e.message ?: "").contains("Cloudflare")) throw e
                continue
            }
            if (results.isEmpty()) continue
            val exact = results.find {
                normalized.contains(normalizeTitle(it.title)) ||
                    (it.jname != null && normalized.contains(normalizeTitle(it.jname)))
            }
            if (exact != null) return Show(exact.id, exact.title)
            if (fallback == null) fallback = results[0]
        }
        return fallback?.let { Show(it.id, it.title) }
    }

    private data class Stream(
        val masterUrl: String,
        val referer: String,
        val malId: Int?,
        val subtitle: String?,
    )

    private fun stream(epId: String, mode: String): Stream {
        val body = hianimeCurl("$baseUrl/api/theme/episode/servers?episodeId=$epId", referer = "$baseUrl/")
        val html = unwrapApiHtml(body)

        var hash: String? = null
        for (chunk in html.split("server-item").drop(1)) {
            if (attr(chunk, "data-type") == mode && attr(chunk, "data-server-name") == serverName) {
                hash = attr(chunk, "data-hash")
                if (hash != null) break
            }
        }
        if (hash == null) throw Exception("No $serverName server for $mode")

        val embedUrl = String(Base64.decode(hash, Base64.DEFAULT), Charsets.UTF_8).trim()
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(embedUrl)) {
            throw Exception("Invalid embed url")
        }
        val referer = Regex("^(https?://[^/]*).*$").replace(embedUrl, "\$1/")
        val malId = Regex("/mal/(\\d+)/").find(embedUrl)?.groupValues?.get(1)?.toIntOrNull()

        val embedHtml = hianimeCurl(embedUrl)
        val blob = Regex("window\\.__P=\"([^\"]*)\"").find(embedHtml)?.groupValues?.get(1)
            ?: throw Exception("Embed config not found")

        val jsonText = deobfuscateBlob(blob)
        var masterUrl: String? = null
        var subtitle: String? = null
        try {
            val cfg = JSONObject(jsonText)
            masterUrl = findM3u8(cfg)
            val subs = cfg.optJSONArray("subtitles")
            if (subs != null) {
                for (i in 0 until subs.length()) {
                    val sub = subs.optJSONObject(i) ?: continue
                    if (sub.optBoolean("default", false)) {
                        subtitle = str(sub, "src")
                        if (subtitle != null) break
                    }
                }
            }
        } catch (_: Exception) {
            masterUrl = Regex("\"src\":\"([^\"]*\\.m3u8[^\"]*)\"").find(jsonText)?.groupValues?.get(1)
            val sub = jsonText
                .split(Regex("\\},\\s*\\{"))
                .find { it.contains("\"default\":true") && it.contains("\"src\":\"") }
            if (sub != null) subtitle = Regex("\"src\":\"([^\"]*)\"").find(sub)?.groupValues?.get(1)
        }
        if (masterUrl == null) throw Exception("No m3u8 source in embed config")
        return Stream(
            masterUrl.replace("\\/", "/"),
            referer,
            malId,
            subtitle?.replace("\\/", "/"),
        )
    }

    private fun deobfuscateBlob(blob: String): String {
        val input = Base64.decode(blob, Base64.DEFAULT)
        val output = ByteArray(input.size)
        for (i in input.indices) {
            output[i] = (input[i].toInt() xor obfuscationKey[i % obfuscationKey.size].toInt()).toByte()
        }
        return String(output, Charsets.UTF_8)
    }

    private fun findM3u8(value: Any?): String? {
        when (value) {
            is String -> return if (value.contains(".m3u8", ignoreCase = true)) value else null
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    findM3u8(value.opt(i))?.let { return it }
                }
            }
            is JSONObject -> {
                val src = str(value, "src")
                if (src != null && src.contains(".m3u8", ignoreCase = true)) return src
                val keys = value.keys()
                while (keys.hasNext()) {
                    findM3u8(value.opt(keys.next()))?.let { return it }
                }
            }
        }
        return null
    }

    // The theme API wraps its HTML in JSON. Mirrors unwrapApiHtml().
    private fun unwrapApiHtml(body: String): String {
        var text = body
        try {
            val json = JSONObject(body)
            val parts = mutableListOf<String>()
            fun walk(value: Any?) {
                when (value) {
                    is String -> parts.add(value)
                    is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
                    is JSONObject -> {
                        val keys = value.keys()
                        while (keys.hasNext()) walk(value.opt(keys.next()))
                    }
                }
            }
            walk(json)
            if (parts.isNotEmpty()) text = parts.joinToString("\n")
        } catch (_: Exception) {
        }
        return text.replace("\\\"", "\"").replace("\\/", "/").replace("\\", "")
    }

    // ── AniList ─────────────────────────────────────────────────────────────

    private data class SeasonInfo(
        val title: String,
        val romaji: String?,
        val episodes: Int?,
        val nextTitle: String?,
        val nextRomaji: String?,
    )

    private fun anilistSeasonTitle(baseTitle: String, seasonNumber: Int): SeasonInfo {
        val fallback = SeasonInfo(baseTitle, null, null, null, null)
        return try {
            val query =
                "query(\$search:String){Media(search:\$search,type:ANIME,sort:SEARCH_MATCH){title{english romaji}episodes relations{edges{relationType node{type format title{english romaji}episodes startDate{year}seasonYear}}}}}"
            val payload = JSONObject()
                .put("query", query)
                .put("variables", JSONObject().put("search", baseTitle))
                .toString()
            val conn = URL("https://graphql.anilist.co").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { readLimited(it) } ?: return fallback
                val media = JSONObject(text).optJSONObject("data")?.optJSONObject("Media")
                    ?: return fallback

                val s1Romaji = str(media.optJSONObject("title"), "romaji")
                val s1Episodes = intOrNull(media, "episodes")
                val sequels = mutableListOf<JSONObject>()
                val edges = media.optJSONObject("relations")?.optJSONArray("edges")
                if (edges != null) {
                    for (i in 0 until edges.length()) {
                        val edge = edges.optJSONObject(i) ?: continue
                        if (edge.optString("relationType") != "SEQUEL") continue
                        val node = edge.optJSONObject("node") ?: continue
                        if (node.optString("type") != "ANIME") continue
                        val format = node.optString("format")
                        if (format != "TV" && format != "TV_SHORT") continue
                        sequels.add(node)
                    }
                }
                sequels.sortBy { node ->
                    val startDate = node.optJSONObject("startDate")
                    intOrNull(startDate, "year") ?: intOrNull(node, "seasonYear") ?: 9999
                }

                if (seasonNumber <= 1) {
                    val next = sequels.firstOrNull()
                    return SeasonInfo(
                        str(media.optJSONObject("title"), "english") ?: baseTitle,
                        s1Romaji,
                        s1Episodes,
                        next?.let { titleOf(it) },
                        next?.let { str(it.optJSONObject("title"), "romaji") },
                    )
                }

                val target = sequels.getOrNull(seasonNumber - 2)
                    ?: return fallback.copy(romaji = s1Romaji)
                val next = sequels.getOrNull(seasonNumber - 1)
                SeasonInfo(
                    titleOf(target) ?: baseTitle,
                    str(target.optJSONObject("title"), "romaji") ?: s1Romaji,
                    intOrNull(target, "episodes"),
                    next?.let { titleOf(it) },
                    next?.let { str(it.optJSONObject("title"), "romaji") },
                )
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun titleOf(node: JSONObject): String? {
        val title = node.optJSONObject("title") ?: return null
        return str(title, "english") ?: str(title, "romaji")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun attr(tag: String, name: String): String? =
        Regex(Regex.escape(name) + "=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    private fun str(obj: JSONObject?, key: String): String? {
        if (obj == null || obj.isNull(key)) return null
        val value = obj.optString(key)
        return value.ifEmpty { null }
    }

    private fun intOrNull(obj: JSONObject?, key: String): Int? {
        if (obj == null || obj.isNull(key)) return null
        return obj.optInt(key)
    }

    private fun decodeEntities(value: String): String = value
        .replace(Regex("&#0?39;"), "'")
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun sanitizeTitle(value: String): String = value
        .replace(Regex("[''`´]"), "")
        .replace(Regex("[:!.]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeTitle(value: String): String = value
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun asString(value: String?, max: Int): String =
        (value ?: "").take(max)

    private fun asPositiveInt(value: Int?): Int {
        val n = value ?: 1
        return if (n < 0 || n >= 100000) 1 else n.coerceAtLeast(1)
    }
}
