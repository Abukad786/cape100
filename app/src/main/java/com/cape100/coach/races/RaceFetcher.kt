package com.cape100.coach.races

import com.cape100.coach.data.Race
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pulls upcoming races from RunningCalendar.co.za (Southern Africa's race calendar).
 * The calendar is server-rendered HTML; each month block lists races with date, town and distance chips.
 * The old Runners' Guide (runnersguide.co.za) no longer publishes a calendar.
 */
class RaceFetcher {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val monthFmt = DateTimeFormatter.ofPattern("yyyy MMM d", Locale.ENGLISH)

    companion object {
        const val BASE = "https://runningcalendar.co.za"
        private val CATEGORIES = listOf("marathon", "ultra", "half-marathon")
        private const val MAX_PAGES = 25
    }

    suspend fun fetchAll(): List<Race> = withContext(Dispatchers.IO) {
        val bySlug = LinkedHashMap<String, Race>()
        for (cat in CATEGORIES) {
            for (page in 1..MAX_PAGES) {
                val html = get("$BASE/calendar?distances=$cat&page=$page") ?: break
                val races = parse(html)
                if (races.isEmpty()) break
                races.forEach { r -> bySlug["${r.slug}|${r.date}"] = bySlug["${r.slug}|${r.date}"]?.let { it.copy(distances = (it.distances + r.distances).distinct()) } ?: r }
            }
        }
        bySlug.values.sortedBy { it.date }
    }

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", "Cape100Coach/1.0 (personal training app)").build()
        return http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }

    fun parse(raw: String): List<Race> {
        val html = raw.replace(Regex("<!--[\\s\\S]*?-->"), "").replace(Regex("\\s+"), " ")
        val out = mutableListOf<Race>()
        val blocks = html.split("class=\"m-list-block\"").drop(1)
        val anchor = Regex("""<a href="https://runningcalendar\.co\.za/events/([^"/]+)" class="flex gap-3\.5[\s\S]*?</a>""")
        for (block in blocks) {
            val heading = Regex("""<h3>\s*([A-Za-z]+) (\d{4})""").find(block) ?: continue
            val year = heading.groupValues[2]
            for (m in anchor.findAll(block)) {
                val a = m.value
                val day = Regex("""text-xl font-bold leading-tight text-ink">(\d+)<""").find(a)?.groupValues?.get(1) ?: continue
                val month = Regex("""tracking-widest text-muted">([A-Za-z]+)<""").findAll(a).map { it.groupValues[1] }.toList().getOrNull(1) ?: continue
                val name = Regex("""text-base font-semibold text-ink">([^<]+)<""").find(a)?.groupValues?.get(1)?.trim() ?: continue
                val loc = Regex("""</svg> <span>([^<]+)</span>""").find(a)?.groupValues?.get(1)?.trim().orEmpty()
                val chips = Regex("""rounded-full text-xs font-semibold mono gap-1"[^>]*> ([^<]+?) <""").findAll(a).map { decode(it.groupValues[1].trim()) }.toList()
                val date = runCatching { LocalDate.parse("$year $month $day", monthFmt) }.getOrNull() ?: continue
                out += Race(m.groupValues[1], decode(name), date.toString(), decode(loc), chips, "$BASE/events/${m.groupValues[1]}")
            }
        }
        return out
    }

    private fun decode(s: String) = s.replace("&#039;", "'").replace("&amp;", "&").replace("&quot;", "\"").replace("&#8211;", "–")
}
