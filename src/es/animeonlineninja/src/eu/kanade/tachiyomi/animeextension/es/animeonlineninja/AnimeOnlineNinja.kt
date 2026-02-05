package eu.kanade.tachiyomi.extension.es.animeonlineninja

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class AnimeOnlineNinja : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeOnlineNinja"
    override val baseUrl = "https://animeonline.ninja"
    override val lang = "es"
    override val supportsLatest = true

    // Cliente con timeouts más largos y headers anti-Cloudflare
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "es-CL,es;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Referer", baseUrl + "/")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
            chain.proceed(requestBuilder.build())
        }
        .build()

    override val headersBuilder = Headers.Builder()
        .add("Referer", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36")

    // ----- Popular -----
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime?page=$page", headers)

    override fun popularAnimeSelector() = "div.col-lg-3.col-md-4.col-sm-6"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h3").text()
        anime.thumbnail_url = element.select("img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector() = "a.next"

    // ----- Latest -----
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // ----- Search -----
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/?s=$query&page=$page", headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ----- Detalles -----
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1").first()?.text() ?: ""
        anime.author = document.select("span:contains(Autor) + span").text()
        anime.artist = anime.author
        anime.genre = document.select("span:contains(Géneros) ~ a").joinToString { it.text() }
        anime.description = document.select("div.sinopsis").text()
        anime.status = parseStatus(document.select("span:contains(Estado)").next().text())
        anime.thumbnail_url = document.select("div.poster img").attr("src")
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En emisión", ignoreCase = true) -> SAnime.ONGOING
            statusString.contains("Finalizado", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ----- Episodios -----
    override fun episodeListSelector() = "div.episodios a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.select("span").text()
        episode.episode_number = element.select("span").text().replace("Episodio ", "").toFloatOrNull() ?: 0f
        return episode
    }

    // ----- Video Extractor -----
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        // Busca iframes o links de reproductores (común en estas webs)
        document.select("iframe[src*='ok.ru'], iframe[src*='streamtape'], iframe[src*='mixdrop'], iframe[src*='sbembed']").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                val quality = when {
                    src.contains("ok.ru") -> "OkRu"
                    src.contains("streamtape") -> "Streamtape"
                    src.contains("mixdrop") -> "Mixdrop"
                    else -> "Embed"
                }
                videoList.add(Video(src, quality, src))
            }
        }

        // Si no encuentra iframes, busca links directos o con JS
        document.select("a[href*='ok.ru'], a[href*='streamtape']").forEach { a ->
            val url = a.attr("href")
            if (url.isNotBlank()) {
                videoList.add(Video(url, "Direct", url))
            }
        }

        // Retry si parece Cloudflare (status 403/503 o HTML con cf-browser-verification)
        if (videoList.isEmpty() && (response.code == 403 || response.code == 503 || document.html().contains("cf-browser-verification"))) {
            // Puedes agregar un delay y retry aquí si quieres más avanzado
            Thread.sleep(3000) // 3 segundos de espera
            // Reintenta la misma petición (puedes hacer un loop de 2-3 intentos en código real)
        }

        return videoList.ifEmpty { listOf(Video("No videos found", "Error", "")) }
    }

    override fun List<Video>.sort(): List<Video> {
        return this.reversed() // Prioriza los últimos encontrados o ajusta según prefieras
    }

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        // Puedes agregar preferencias si quieres (ej: User-Agent custom)
    }
}
