package com.iptv.tv

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Creds(val server: String, val user: String, val pass: String, val tmdb: String)
data class Cat(val id: String, val name: String)
data class Item(val id: String, val name: String, val img: String, val kind: String, val cat: String,
                val ext: String = "", val added: Long = 0, val rating: String = "", val epgId: String = "", val num: Int = 0) {
    val nn: String by lazy(LazyThreadSafetyMode.NONE) { nz(name) }
    val year: String by lazy(LazyThreadSafetyMode.NONE) { Regex("(19|20)\\d{2}").find(name)?.value ?: "" }
    val fkey: String get() = "$kind:$id"
}
data class Ep(val id: String, val title: String, val season: Int, val num: Int, val ext: String)
data class Meta(val backdrop: String?, val overview: String, val rating: String, val year: String, val id: Int = 0)
data class Guide(val cert: String, val tags: List<String>)

data class Prog(val start: Long, val stop: Long, val title: String)
data class Pl(val id: String, val name: String, val server: String, val user: String, val pass: String)

fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k)
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

object Store {
    lateinit var sp: SharedPreferences
    lateinit var dir: File
    val favs = mutableStateListOf<String>()
    var active: Int
        get() = sp.getInt("active", 0)
        set(v) { sp.edit().putInt("active", v).apply() }
    fun init(c: Context) {
        sp = c.getSharedPreferences("cfg", 0)
        dir = c.filesDir
        if (!sp.contains("pls") && !sp.getString("server", "").isNullOrBlank())
            savePls(listOf(Pl("0", "Default", sp.getString("server", "")!!, sp.getString("user", "") ?: "", sp.getString("pass", "") ?: "")))
    }
    fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    fun int(k: String, d: Int) = sp.getInt(k, d)
    fun str(k: String, d: String) = sp.getString(k, d) ?: d
    fun put(k: String, v: Any) {
        val e = sp.edit()
        when (v) { is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v); else -> e.putString(k, v.toString()) }
        e.apply()
    }
    fun pls(): List<Pl> {
        val a = JSONArray(sp.getString("pls", "[]") ?: "[]")
        return List(a.length()) { val o = a.getJSONObject(it); Pl(o.s("id"), o.s("n"), o.s("s"), o.s("u"), o.s("p")) }
    }
    fun savePls(l: List<Pl>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("s", it.server).put("u", it.user).put("p", it.pass)) }
        sp.edit().putString("pls", a.toString()).apply()
    }
    fun pid(): String = pls().getOrNull(active)?.id ?: "0"
    fun loadFavs() {
        favs.clear()
        val id = pid()
        val cur = sp.getStringSet("fav2_$id", null)
        if (cur != null) favs.addAll(cur)
        else sp.getStringSet("favs_$id", emptySet())?.forEach { favs.add("live:$it") }
    }
    fun isFav(i: Item) = favs.contains(i.fkey)
    fun fav(i: Item) {
        if (!favs.remove(i.fkey)) favs.add(i.fkey)
        sp.edit().putStringSet("fav2_" + pid(), favs.toSet()).apply()
    }
    fun clearFavs() { favs.clear(); sp.edit().putStringSet("fav2_" + pid(), emptySet()).apply() }
}

data class Hist(val key: String, val title: String, val img: String, val url: String, val kind: String,
                val pos: Long, val dur: Long, val ts: Long, val sid: String = "") {
    val progress: Float get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
}

object History {
    var version by mutableStateOf(0)
    private fun k() = "hist_" + Store.pid()
    fun all(): List<Hist> = try {
        val a = JSONArray(Store.sp.getString(k(), "[]") ?: "[]")
        List(a.length()) { val o = a.getJSONObject(it)
            Hist(o.s("k"), o.s("t"), o.s("i"), o.s("u"), o.s("kd"), o.optLong("p"), o.optLong("d"), o.optLong("s"), o.s("sid")) }
    } catch (e: Exception) { emptyList() }
    fun get(key: String): Hist? = all().firstOrNull { it.key == key }
    private fun write(l: List<Hist>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("k", it.key).put("t", it.title).put("i", it.img).put("u", it.url)
            .put("kd", it.kind).put("p", it.pos).put("d", it.dur).put("s", it.ts).put("sid", it.sid)) }
        Store.sp.edit().putString(k(), a.toString()).apply(); version++
    }
    fun put(h: Hist) { write((listOf(h) + all().filter { it.key != h.key }).take(40)) }
    fun remove(key: String) { write(all().filter { it.key != key }) }
    fun removeWhere(p: (Hist) -> Boolean) { write(all().filterNot(p)) }
    fun clear() { write(emptyList()) }
}

object Api {
    var c = Creds("", "", "", "")
    private val cache: MutableMap<String, Meta?> = java.util.Collections.synchronizedMap(HashMap<String, Meta?>())
    fun base(): String { val s = c.server.trim().trimEnd('/'); return if (s.startsWith("http")) s else "http://$s" }
    fun get(url: String, rt: Int = 90000): String {
        val cn = URL(url).openConnection() as HttpURLConnection
        cn.connectTimeout = minOf(15000, rt); cn.readTimeout = rt
        cn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)")
        try { return cn.inputStream.bufferedReader().use { it.readText() } } finally { cn.disconnect() }
    }
    private fun auth() = "username=${enc(c.user)}&password=${enc(c.pass)}"
    fun act(a: String, x: String = "") = get("${base()}/player_api.php?${auth()}&action=$a$x")
    /** Login test for credentials that are not (yet) the active ones, e.g. sent from the phone. */
    fun test(server: String, user: String, pass: String): Boolean {
        val b = server.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        return JSONObject(get("$b/player_api.php?username=${enc(user)}&password=${enc(pass)}", 15000)).optJSONObject("user_info")?.s("auth") == "1"
    }
    fun login(): Boolean = JSONObject(get("${base()}/player_api.php?${auth()}")).optJSONObject("user_info")?.s("auth") == "1"
    fun parseCats(txt: String): List<Cat> { val r = JSONArray(txt); return List(r.length()) { Cat(r.getJSONObject(it).s("category_id"), r.getJSONObject(it).s("category_name")) } }
    fun cats(a: String): List<Cat> = parseCats(act(a))
    fun parseItems(txt: String, kind: String, idk: String, imgk: String): List<Item> {
        val r = JSONArray(txt)
        return List(r.length()) { i ->
            val o = r.getJSONObject(i)
            Item(o.s(idk), o.s("name"), o.s(imgk), kind, o.s("category_id"), o.s("container_extension"),
                o.s(if (kind == "series") "last_modified" else "added").toLongOrNull() ?: 0L, o.s("rating"), o.s("epg_channel_id"), o.optInt("num", 0))
        }
    }
    fun episodes(id: String): List<Ep> {
        val e = JSONObject(act("get_series_info", "&series_id=$id")).optJSONObject("episodes") ?: return emptyList()
        val out = mutableListOf<Ep>()
        e.keys().forEach { sk ->
            val a = e.optJSONArray(sk) ?: return@forEach
            for (i in 0 until a.length()) {
                val x = a.getJSONObject(i)
                out += Ep(x.s("id"), x.s("title"), sk.toIntOrNull() ?: 0, x.optInt("episode_num", i + 1), x.s("container_extension").ifBlank { "mp4" })
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.num }))
    }
    fun epg(id: String): List<String> {
        val a = JSONObject(act("get_short_epg", "&stream_id=$id&limit=3")).optJSONArray("epg_listings") ?: return emptyList()
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val t = String(Base64.decode(o.s("title"), Base64.DEFAULT))
            val st = (o.s("start_timestamp").toLongOrNull() ?: 0L) * 1000L
            val en = (o.s("stop_timestamp").toLongOrNull() ?: 0L) * 1000L
            "${f.format(Date(st))} - ${f.format(Date(en))}   $t"
        }
    }
    fun liveUrls(id: String): List<String> {
        val h = "${base()}/live/${c.user}/${c.pass}/$id"
        return if (Store.sp.getString("fmt", "m3u8") == "ts") listOf("$h.ts", "$h.m3u8") else listOf("$h.m3u8", "$h.ts")
    }
    fun vodUrl(id: String, ext: String) = "${base()}/movie/${c.user}/${c.pass}/$id.${ext.ifBlank { "mp4" }}"
    fun epUrl(id: String, ext: String) = "${base()}/series/${c.user}/${c.pass}/$id.$ext"
    fun xmltv(): Map<String, List<Prog>> {
        val ws = System.currentTimeMillis() - 3600000L
        val we = System.currentTimeMillis() + 8 * 3600000L
        val f1 = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val f2 = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        fun pt(x: String): Long = try { f1.parse(x.trim())!!.time } catch (e: Exception) { try { f2.parse(x.trim().take(14))!!.time } catch (e2: Exception) { 0L } }
        val cn = URL("${base()}/xmltv.php?${auth()}").openConnection() as HttpURLConnection
        cn.connectTimeout = 15000; cn.readTimeout = 180000
        cn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)")
        val out = HashMap<String, MutableList<Prog>>()
        cn.inputStream.use { ins ->
            val p = Xml.newPullParser(); p.setInput(ins, null)
            var ch = ""; var st = 0L; var en = 0L; var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    if (p.name == "programme") { ch = p.getAttributeValue(null, "channel") ?: ""; st = pt(p.getAttributeValue(null, "start") ?: ""); en = pt(p.getAttributeValue(null, "stop") ?: "") }
                    else if (p.name == "title") { val t = p.nextText(); if (ch.isNotEmpty() && en > ws && st < we) out.getOrPut(ch) { mutableListOf() }.add(Prog(st, en, t)) }
                }
                ev = p.next()
            }
        }
        return out
    }
    private val imdbCache: MutableMap<String, String?> = java.util.Collections.synchronizedMap(HashMap<String, String?>())
    fun imdb(title: String): String? {
        if (c.tmdb.isBlank()) return null
        val q = title.replace(Regex("[\\[(].*?[\\])]"), "").replace(Regex("^\\s*[A-Za-z]{2,3}\\s*[-|:]\\s*"), "").trim()
        if (imdbCache.containsKey(q)) return imdbCache[q]
        val id = JSONObject(get("https://api.themoviedb.org/3/search/movie?api_key=${enc(c.tmdb)}&query=${enc(q)}"))
            .optJSONArray("results")?.optJSONObject(0)?.optInt("id", 0) ?: 0
        val r = if (id == 0) null else JSONObject(get("https://api.themoviedb.org/3/movie/$id/external_ids?api_key=${enc(c.tmdb)}")).s("imdb_id").ifBlank { null }
        imdbCache[q] = r
        return r
    }
    fun tmdb(title: String, tv: Boolean): Meta? {
        val m = tmdbL(title, tv, if (Lang.ar) "ar" else "en")
        return if (Lang.ar && m != null && m.overview.isBlank()) (tmdbL(title, tv, "en") ?: m) else m
    }
    fun tmdbL(title: String, tv: Boolean, lang: String): Meta? {
        if (c.tmdb.isBlank()) return null
        val q = title.replace(Regex("[\\[(].*?[\\])]"), "").replace(Regex("^\\s*[A-Za-z]{2,3}\\s*[-|:]\\s*"), "").trim()
        val key = "$tv|$q|$lang"
        if (cache.containsKey(key)) return cache[key]
        val y = Regex("(19|20)\\d{2}").find(title)?.value
        val url = "https://api.themoviedb.org/3/search/${if (tv) "tv" else "movie"}?api_key=${enc(c.tmdb)}&query=${enc(q)}&language=$lang" +
            (if (y != null) (if (tv) "&first_air_date_year=$y" else "&year=$y") else "")
        val r = JSONObject(get(url, 10000)).optJSONArray("results")?.optJSONObject(0)
        val m = r?.let { o ->
            Meta(o.s("backdrop_path").takeIf { p -> p.isNotBlank() }?.let { p -> "https://image.tmdb.org/t/p/w1280$p" },
                o.s("overview"), o.optDouble("vote_average", 0.0).let { v -> if (v > 0) "%.1f".format(v) else "" },
                o.s("release_date").ifBlank { o.s("first_air_date") }.take(4), o.optInt("id", 0))
        }
        cache[key] = m
        return m
    }

    // ---- Parental guidance from TMDB: age certification + warnings derived from TMDB keywords
    private val guideCache: MutableMap<String, Guide?> = java.util.Collections.synchronizedMap(HashMap<String, Guide?>())
    private val WARN = listOf(
        "violence" to "Violence", "gore" to "Gore", "blood" to "Blood", "nudity" to "Nudity", "sex" to "Sexual content",
        "drug" to "Drugs", "alcohol" to "Alcohol", "profanity" to "Strong language", "torture" to "Torture",
        "rape" to "Sexual violence", "suicide" to "Suicide", "smoking" to "Smoking"
    )
    private fun certOf(o: JSONObject): String {
        val d = o.optJSONArray("release_dates") ?: return ""
        for (i in 0 until d.length()) { val s = d.getJSONObject(i).s("certification"); if (s.isNotBlank()) return s }
        return ""
    }
    fun guide(title: String, tv: Boolean): Guide? {
        if (c.tmdb.isBlank()) return null
        val key = "$tv|${title.lowercase()}"
        if (guideCache.containsKey(key)) return guideCache[key]
        val id = tmdbL(title, tv, "en")?.id ?: 0
        if (id == 0) return null
        val u = if (tv) "https://api.themoviedb.org/3/tv/$id?api_key=${enc(c.tmdb)}&append_to_response=content_ratings,keywords"
                else "https://api.themoviedb.org/3/movie/$id?api_key=${enc(c.tmdb)}&append_to_response=release_dates,keywords"
        val j = JSONObject(get(u, 10000))
        val pref = listOf(Locale.getDefault().country, "US", "GB").filter { it.isNotBlank() } + ""
        var cert = ""
        if (tv) {
            val a = j.optJSONObject("content_ratings")?.optJSONArray("results")
            if (a != null) {
                val l = List(a.length()) { a.getJSONObject(it) }
                for (r in pref) { val x = l.firstOrNull { (r.isEmpty() || it.s("iso_3166_1") == r) && it.s("rating").isNotBlank() }; if (x != null) { cert = x.s("rating"); break } }
            }
        } else {
            val a = j.optJSONObject("release_dates")?.optJSONArray("results")
            if (a != null) {
                val l = List(a.length()) { a.getJSONObject(it) }
                for (r in pref) { val x = l.firstOrNull { (r.isEmpty() || it.s("iso_3166_1") == r) && certOf(it).isNotBlank() }; if (x != null) { cert = certOf(x); break } }
            }
        }
        val kw = j.optJSONObject("keywords")?.optJSONArray(if (tv) "results" else "keywords")
        val names: List<String> = if (kw == null) emptyList<String>() else List(kw.length()) { kw.getJSONObject(it).s("name").lowercase() }
        val tags = WARN.filter { (k, _) -> names.any { n -> n.contains(k) } }.map { it.second }.distinct().take(3)
        val g = if (cert.isNotBlank() || tags.isNotEmpty()) Guide(cert, tags) else null
        guideCache[key] = g
        return g
    }
}
