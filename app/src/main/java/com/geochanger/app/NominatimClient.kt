package com.geochanger.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object NominatimClient {

    data class Place(val displayName: String, val lat: Double, val lon: Double)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/search" +
            "?q=" + URLEncoder.encode(query, "UTF-8") +
            "&format=json&limit=5&accept-language=ru"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GeoChanger/1.0 (Android; mock location app)")
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Nominatim HTTP ${response.code}" }
            val body = response.body?.string().orEmpty()
            val array = JSONArray(body)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Place(
                    displayName = obj.getString("display_name"),
                    lat = obj.getDouble("lat"),
                    lon = obj.getDouble("lon")
                )
            }
        }
    }
}
