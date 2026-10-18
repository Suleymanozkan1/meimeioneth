package com.lexiflow.server.store

import com.lexiflow.core.competition.BattleMode
import com.lexiflow.server.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Timestamp

/** PostgreSQL store: plain JDBC over a HikariCP pool. Tables are created at startup from db/schema.sql. */
class PostgresStore(private val dataSource: HikariDataSource) : Store {
    override val kind: String = "postgres"

    init {
        migrate()
    }

    private fun migrate() {
        val sql = javaClass.getResourceAsStream("/db/schema.sql")?.bufferedReader()?.use { it.readText() }
            ?: error("db/schema.sql not found on classpath")
        val statements = sql.lines()
            .filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        dataSource.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { st -> statements.forEach { st.execute(it) } }
            c.commit()
        }
    }

    private suspend fun <T> db(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use(block)
    }

    override suspend fun createUser(user: UserRecord) = db { c ->
        c.prepareStatement("INSERT INTO users (id, display_name, created_at) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING").use { ps ->
            ps.setString(1, user.id)
            ps.setString(2, user.displayName)
            ps.setTimestamp(3, Timestamp(user.createdAtMs))
            ps.executeUpdate()
        }
        Unit
    }

    override suspend fun findUser(id: String): UserRecord? = db { c ->
        c.prepareStatement("SELECT id, display_name, created_at FROM users WHERE id = ?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs ->
                if (rs.next()) UserRecord(rs.getString(1), rs.getString(2), rs.getTimestamp(3).time) else null
            }
        }
    }

    override suspend fun saveBattleResults(results: List<BattleResultRecord>) {
        if (results.isEmpty()) return
        db { c ->
            c.autoCommit = false
            try {
                c.prepareStatement(
                    """INSERT INTO battle_results (room_id, mode, player_id, display_name, overall, placement, won, submitted, finished_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (room_id, player_id) DO NOTHING""",
                ).use { ps ->
                    results.forEach { r ->
                        ps.setString(1, r.roomId)
                        ps.setString(2, r.mode.name)
                        ps.setString(3, r.playerId)
                        ps.setString(4, r.displayName)
                        ps.setInt(5, r.overall)
                        ps.setInt(6, r.placement)
                        ps.setBoolean(7, r.won)
                        ps.setBoolean(8, r.submitted)
                        ps.setTimestamp(9, Timestamp(r.finishedAtMs))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            } finally {
                c.autoCommit = true
            }
        }
    }

    override suspend fun leaderboard(mode: BattleMode?, limit: Int): List<LeaderboardEntry> = db { c ->
        val where = if (mode != null) "WHERE r.mode = ?" else ""
        val sql = """
            SELECT r.player_id,
                   u.display_name,
                   COUNT(*) AS games,
                   SUM(CASE WHEN r.won THEN 1 ELSE 0 END) AS wins,
                   COALESCE(AVG(r.overall) FILTER (WHERE r.submitted), 0) AS avg_score
            FROM battle_results r
            JOIN users u ON u.id = r.player_id
            $where
            GROUP BY r.player_id, u.display_name
            ORDER BY wins DESC, avg_score DESC, games DESC
            LIMIT ?
        """.trimIndent()
        c.prepareStatement(sql).use { ps ->
            var i = 1
            if (mode != null) ps.setString(i++, mode.name)
            ps.setInt(i, limit)
            ps.executeQuery().use { rs ->
                val rows = ArrayList<LeaderboardEntry>()
                while (rs.next()) {
                    rows += LeaderboardEntry(
                        rank = 0,
                        playerId = rs.getString("player_id"),
                        displayName = rs.getString("display_name"),
                        wins = rs.getInt("wins"),
                        games = rs.getInt("games"),
                        averageScore = round1(rs.getDouble("avg_score")),
                    )
                }
                rankLeaderboard(rows, limit)
            }
        }
    }

    override suspend fun saveReport(report: ReportRecord) = db { c ->
        c.prepareStatement(
            "INSERT INTO reports (id, room_id, reporter_id, reported_id, reason, reported_text, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, report.id)
            ps.setString(2, report.roomId)
            ps.setString(3, report.reporterId)
            ps.setString(4, report.reportedId)
            ps.setString(5, report.reason)
            ps.setString(6, report.reportedText)
            ps.setTimestamp(7, Timestamp(report.createdAtMs))
            ps.executeUpdate()
        }
        Unit
    }

    override suspend fun reports(): List<ReportRecord> = db { c ->
        c.prepareStatement("SELECT id, room_id, reporter_id, reported_id, reason, reported_text, created_at FROM reports ORDER BY created_at").use { ps ->
            ps.executeQuery().use { rs ->
                val out = ArrayList<ReportRecord>()
                while (rs.next()) {
                    out += ReportRecord(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getTimestamp(7).time,
                    )
                }
                out
            }
        }
    }

    override fun close() {
        dataSource.close()
    }

    companion object {
        fun create(config: DatabaseConfig): PostgresStore {
            val hikari = HikariConfig().apply {
                jdbcUrl = toJdbcUrl(config.url)
                config.user?.let { username = it }
                config.password?.let { password = it }
                maximumPoolSize = 10
                minimumIdle = 1
                poolName = "lexiflow-db"
                isAutoCommit = true
                connectionTimeout = 10_000
                // No single query may hold a pooled connection for long; the pool is shared with authentication.
                connectionInitSql = "SET statement_timeout = 5000"
            }
            return PostgresStore(HikariDataSource(hikari))
        }

        /** Accepts both `jdbc:postgresql://host/db` and `postgres://host:5432/db` style URLs (credentials go in DB_USER/DB_PASSWORD). */
        fun toJdbcUrl(url: String): String = when {
            url.startsWith("jdbc:") -> url
            url.startsWith("postgres://") -> "jdbc:postgresql://" + url.removePrefix("postgres://")
            url.startsWith("postgresql://") -> "jdbc:postgresql://" + url.removePrefix("postgresql://")
            else -> url
        }
    }
}
