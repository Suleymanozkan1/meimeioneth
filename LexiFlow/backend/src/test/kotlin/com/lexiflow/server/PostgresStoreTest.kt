package com.lexiflow.server

import com.lexiflow.core.competition.BattleMode
import com.lexiflow.server.store.BattleResultRecord
import com.lexiflow.server.store.PostgresStore
import com.lexiflow.server.store.ReportRecord
import com.lexiflow.server.store.UserRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * Integration test against a real PostgreSQL. Skipped unless LEXIFLOW_TEST_DATABASE_URL is set, e.g.
 * `LEXIFLOW_TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/lexiflow_test LEXIFLOW_TEST_DB_USER=... ./gradlew :backend:test`
 * (uses throwaway ids; run it against a disposable database).
 */
class PostgresStoreTest {
    @Test
    fun `postgres store persists users results reports and aggregates the leaderboard`() = runBlocking {
        val url = System.getenv("LEXIFLOW_TEST_DATABASE_URL")
        assumeTrue("LEXIFLOW_TEST_DATABASE_URL not set", !url.isNullOrBlank())
        val store = PostgresStore.create(DatabaseConfig(url!!, System.getenv("LEXIFLOW_TEST_DB_USER"), System.getenv("LEXIFLOW_TEST_DB_PASSWORD")))
        // Running the migration twice must be harmless (CREATE ... IF NOT EXISTS).
        PostgresStore.create(DatabaseConfig(url, System.getenv("LEXIFLOW_TEST_DB_USER"), System.getenv("LEXIFLOW_TEST_DB_PASSWORD"))).close()
        try {
            val a = UserRecord(UUID.randomUUID().toString(), "PgAlice", System.currentTimeMillis())
            val b = UserRecord(UUID.randomUUID().toString(), "PgBob", System.currentTimeMillis())
            store.createUser(a)
            store.createUser(b)
            assertEquals("PgAlice", store.findUser(a.id)?.displayName)

            val room = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val records = listOf(
                BattleResultRecord(room, BattleMode.SAME_TOPIC, a.id, a.displayName, 80, 1, won = true, submitted = true, finishedAtMs = now),
                BattleResultRecord(room, BattleMode.SAME_TOPIC, b.id, b.displayName, 0, 2, won = false, submitted = false, finishedAtMs = now),
            )
            store.saveBattleResults(records)
            store.saveBattleResults(records) // idempotent per (room, player)

            val board = store.leaderboard(BattleMode.SAME_TOPIC, 50).filter { it.playerId == a.id || it.playerId == b.id }
            assertEquals(listOf(a.id, b.id), board.map { it.playerId })
            assertEquals(1, board[0].wins)
            assertEquals(1, board[0].games)
            assertEquals(80.0, board[0].averageScore, 0.01)
            assertEquals(0.0, board[1].averageScore, 0.01)

            store.saveReport(ReportRecord(UUID.randomUUID().toString(), room, a.id, b.id, "spam", "text", now))
            assertEquals(1, store.reports().count { it.roomId == room })
        } finally {
            store.close()
        }
    }
}
