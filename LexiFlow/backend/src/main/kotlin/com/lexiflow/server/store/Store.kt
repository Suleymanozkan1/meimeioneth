package com.lexiflow.server.store

import com.lexiflow.core.competition.BattleMode
import kotlinx.serialization.Serializable

/** Anonymous account: no e-mail, phone or real name is ever stored (data minimisation). */
data class UserRecord(val id: String, val displayName: String, val createdAtMs: Long)

/** One player's outcome in one finished room. The submitted text itself is not persisted. */
data class BattleResultRecord(
    val roomId: String,
    val mode: BattleMode,
    val playerId: String,
    val displayName: String,
    val overall: Int,
    /** 1-based position in the final ranking. */
    val placement: Int,
    val won: Boolean,
    val submitted: Boolean,
    val finishedAtMs: Long,
)

data class ReportRecord(
    val id: String,
    val roomId: String,
    val reporterId: String,
    val reportedId: String,
    val reason: String,
    /** Snapshot of the reported player's submission (if any) so moderators can review it. */
    val reportedText: String?,
    val createdAtMs: Long,
)

@Serializable
data class LeaderboardEntry(
    val rank: Int,
    val playerId: String,
    val displayName: String,
    val wins: Int,
    val games: Int,
    /** Average overall score over the games in which the player submitted (0 when none). */
    val averageScore: Double,
)

interface Store {
    /** "memory" or "postgres" (reported by /health). */
    val kind: String

    suspend fun createUser(user: UserRecord)
    suspend fun findUser(id: String): UserRecord?
    suspend fun saveBattleResults(results: List<BattleResultRecord>)
    /** Top [limit] players by wins, then average score. [mode] null = all modes. */
    suspend fun leaderboard(mode: BattleMode?, limit: Int = 50): List<LeaderboardEntry>
    suspend fun saveReport(report: ReportRecord)
    suspend fun reports(): List<ReportRecord>
    fun close() {}
}

/** Shared ranking logic so both stores order the leaderboard identically. */
internal fun rankLeaderboard(rows: List<LeaderboardEntry>, limit: Int): List<LeaderboardEntry> =
    rows.sortedWith(
        compareByDescending<LeaderboardEntry> { it.wins }
            .thenByDescending { it.averageScore }
            .thenByDescending { it.games }
            .thenBy { it.displayName.lowercase() },
    ).take(limit).mapIndexed { i, e -> e.copy(rank = i + 1) }

internal fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
