package com.daview.server.scraper

import com.daview.server.config.ScraperConfig
import com.daview.server.db.Database
import com.daview.server.db.JdbcSqlDatabase
import com.daview.server.db.Repository
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Where each source finds a season's synopsis. bangumi.tv has no seasons, only
 * subjects linked to one another, so most of this is about following the right
 * link. The subjects are real ones, trimmed to the fields that decide the walk.
 */
class ScraperSeasonsTest {

    private val dir = createTempDirectory("daview-scraper-seasons")
    private val database = Database(JdbcSqlDatabase(dir))
    private val repository = Repository(database)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private val bangumi = BangumiScraper(repository, "http://127.0.0.1:${server.address.port}")
    private val config = ScraperConfig()

    private val routes = HashMap<String, String>()
    private val asked = mutableListOf<String>()

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            synchronized(asked) { asked += path }
            val body = routes[path]?.toByteArray()
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
            }
            exchange.close()
        }
    }

    @AfterTest
    fun close() {
        server.stop(0)
        database.close()
        dir.toFile().deleteRecursively()
    }

    private class Link(val id: Int, val type: Int, val relation: String, val nameCn: String)

    private fun subject(id: Int, nameCn: String, platform: String, date: String?, summary: String) {
        routes["/v0/subjects/$id"] = buildJsonObject {
            put("id", id)
            put("name_cn", nameCn)
            put("platform", platform)
            date?.let { put("date", it) }
            put("summary", summary)
        }.toString()
    }

    private fun related(id: Int, vararg links: Link) {
        routes["/v0/subjects/$id/subjects"] = buildJsonArray {
            links.forEach { link ->
                add(buildJsonObject {
                    put("id", link.id)
                    put("type", link.type)
                    put("relation", link.relation)
                    put("name_cn", link.nameCn)
                })
            }
        }.toString()
    }

    @Test
    fun `a second season filed as the same world is found by its title`() {
        subject(309311, "赛博朋克：边缘行者", "WEB", "2022-09-13", "第一季的简介")
        related(
            309311,
            Link(65759, 4, "游戏", "赛博朋克 2077"),
            Link(529161, 1, "前传", "赛博朋克：边缘行者 MADNESS"),
            // Same world, an anime, earlier — but not this show.
            Link(900001, 2, "相同世界观", "夜之城故事"),
            Link(513878, 2, "相同世界观", "赛博朋克：边缘行者 2")
        )
        subject(900001, "夜之城故事", "TV", "2021-01-01", "别的作品")
        subject(513878, "赛博朋克：边缘行者 2", "WEB", "2026-10-20", "第二季的简介\r\n\r")

        assertEquals(listOf(ScrapedSeason(2, "第二季的简介")), bangumi.seasons("309311", 2, config))
    }

    @Test
    fun `a sequel wins over a spin-off that carries the show's title`() {
        subject(212003, "赛马娘 Pretty Derby", "TV", "2018-04-01", "第一季的简介")
        related(
            212003,
            Link(315574, 2, "续集", "赛马娘 Pretty Derby 第二季"),
            Link(380448, 2, "相同世界观", "赛马娘 Pretty Derby 巅峰之路"),
            Link(472620, 2, "相同世界观", "赛马娘 Pretty Derby 新时代之门")
        )
        subject(315574, "赛马娘 Pretty Derby 第二季", "TV", "2021-01-04", "第二季的简介")
        related(
            315574,
            Link(212003, 2, "前传", "赛马娘 Pretty Derby"),
            Link(407332, 2, "续集", "赛马娘 Pretty Derby 第三季"),
            // A web series that aired half a year before season three.
            Link(380448, 2, "相同世界观", "赛马娘 Pretty Derby 巅峰之路")
        )
        subject(407332, "赛马娘 Pretty Derby 第三季", "TV", "2023-10-04", "第三季的简介")
        subject(380448, "赛马娘 Pretty Derby 巅峰之路", "WEB", "2023-04-16", "巅峰之路的简介")

        assertEquals(
            listOf(ScrapedSeason(2, "第二季的简介"), ScrapedSeason(3, "第三季的简介")),
            bangumi.seasons("212003", 3, config)
        )
        assertFalse("/v0/subjects/380448" in asked, "the spin-off is only a candidate when there is no sequel")
    }

    @Test
    fun `a film in the chain is not a season, and the walk stops where no series follows`() {
        subject(115908, "吹响吧！上低音号", "TV", "2015-04-08", "第一季的简介")
        related(
            115908,
            Link(152090, 2, "续集", "剧场版 吹响吧！上低音号 ～欢迎加入北宇治高中吹奏乐部～"),
            Link(152091, 2, "续集", "吹响吧！上低音号 第二季")
        )
        subject(152090, "剧场版 吹响吧！上低音号 ～欢迎加入北宇治高中吹奏乐部～", "剧场版", "2016-04-23", "电影的简介")
        subject(152091, "吹响吧！上低音号 第二季", "TV", "2016-10-05", "第二季的简介")
        related(
            152091,
            Link(216371, 2, "续集", "莉兹与青鸟"),
            Link(216372, 2, "续集", "剧场版 吹响吧！上低音号～誓言的终章～")
        )
        subject(216371, "莉兹与青鸟", "剧场版", "2018-04-21", "电影的简介")
        subject(216372, "剧场版 吹响吧！上低音号～誓言的终章～", "剧场版", "2019-04-19", "电影的简介")

        assertEquals(listOf(ScrapedSeason(2, "第二季的简介")), bangumi.seasons("115908", 3, config))
    }

    @Test
    fun `a stale link list is asked again only when fresh data is wanted`() {
        subject(100, "某番", "TV", "2020-01-01", "第一季的简介")
        // Cached before the second season was listed.
        repository.cachePut("bgm:related:100", "[]")
        related(100, Link(101, 2, "续集", "某番 第二季"))
        subject(101, "某番 第二季", "TV", "2022-01-01", "第二季的简介")

        assertEquals(emptyList(), bangumi.seasons("100", 2, config))
        assertFalse("/v0/subjects/100/subjects" in asked, "a scrape reuses the week-old answer")

        assertEquals(listOf(ScrapedSeason(2, "第二季的简介")), bangumi.seasons("100", 2, config, maxAgeMs = 0))
    }

    @Test
    fun `TMDB seasons are read off the show's own entry`() {
        repository.cachePut(
            "tmdb:details:tv:42:zh-CN",
            """{"id":42,"name":"Show","seasons":[
                {"season_number":0,"overview":""},
                {"season_number":1,"overview":"第一季的简介"},
                {"season_number":2,"overview":"第二季的简介"}]}"""
        )

        assertEquals(
            listOf(ScrapedSeason(0, null), ScrapedSeason(1, "第一季的简介"), ScrapedSeason(2, "第二季的简介")),
            TmdbScraper(repository).seasons("42", 2, ScraperConfig(tmdbApiKey = "test-key"))
        )
    }
}
