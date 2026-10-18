package com.lexiflow.server.store

import com.lexiflow.core.competition.BattleMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Volatile store used when DATABASE_URL is not configured (local development, tests). */
class InMemoryStore : Store {
    override val kind: String = "memory"

    private val mutex = Mutex()
    private val users = LinkedHashMap<String, UserRecord>()
    private val results = ArrayList<BattleResultRecord>()
    private val reports = ArrayList<ReportRecord>()

    override suspend fun createUser(user: UserRecord) = mutex.withLock {
        users[user.id] = user
    }

    override suspend fun findUser(id: String): UserRecord? = mutex.withLock { users[id] }

    override suspend fun saveBattleResults(results: List<BattleResultRecord>) = mutex.withLock {
        results.forEach { r ->
            if (this.results.none { it.roomId == r.roomId && it.playerId == r.playerId }) this.results += r
        }
    }

    override suspend fun leaderboard(mode: BattleMode?, limit: Int): List<LeaderboardEntry> = mutex.withLock {
        val rows = results.filter { mode == null || it.mode == mode }
            .groupBy { it.playerId }
            .map { (playerId, games) ->
                val submitted = games.filter { it.submitted }
                LeaderboardEntry(
                    rank = 0,
                    playerId = playerId,
                    displayName = users[playerId]?.displayName ?: games.maxBy { it.finishedAtMs }.displayName,
                    wins = games.count { it.won },
                    games = games.size,
                    averageScore = if (submitted.isEmpty()) 0.0 else round1(submitted.map { it.overall }.average()),
                )
            }
        rankLeaderboard(rows, limit)
    }

    override suspend fun saveReport(report: ReportRecord) = mutex.withLock {
        reports += report
        Unit
    }

    override suspend fun reports(): List<ReportRecord> = mutex.withLock { reports.toList() }
}
