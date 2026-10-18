package com.lexiflow.server

import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.competition.BattleMode
import com.lexiflow.core.competition.ClientMessage
import com.lexiflow.core.competition.CompetitionRules
import com.lexiflow.core.competition.ServerMessage
import com.lexiflow.server.store.LeaderboardEntry
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.close
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleTest {
    private val mode = BattleMode.FIVE_WORD_SENTENCE

    /** Builds a real sentence that uses all drawn words, so CompetitionRules produces non-trivial scores. */
    private fun sentenceWith(words: List<String>, flavour: Int): String = when (flavour) {
        0 -> "Yesterday I was thinking about the words ${words.dropLast(1).joinToString(", ")} and ${words.last()}, because my teacher said they are useful in daily life."
        else -> words.joinToString(" ") + " " + words.first()
    }

    @Test
    fun `two players battle and both receive the deterministic result`() = lexiTest(config = testConfig(battle = fastBattle(roundSeconds = 20))) { store ->
        val ws = wsClient()
        val alice = client.register("Alice")
        val bob = client.register("Bob")

        val a = ws.webSocketSession("/v1/battle") { header(HttpHeaders.Authorization, "Bearer ${alice.token}") }
        val welcomeA = a.expect<ServerMessage.Welcome>()
        assertEquals(alice.playerId, welcomeA.playerId)
        assertEquals("Alice", welcomeA.displayName)
        assertEquals(1, welcomeA.onlinePlayers)

        // Header-based auth works too.
        val b = ws.webSocketSession("/v1/battle") { header(HttpHeaders.Authorization, "Bearer ${bob.token}") }
        val welcomeB = b.expect<ServerMessage.Welcome>()
        assertEquals(2, welcomeB.onlinePlayers)

        a.sendMsg(ClientMessage.JoinQueue(mode))
        assertEquals(1, a.expect<ServerMessage.Queued>().waiting)
        b.sendMsg(ClientMessage.JoinQueue(mode))
        assertEquals(2, b.expect<ServerMessage.Queued>().waiting)

        val matchA = a.expect<ServerMessage.MatchFound>()
        val matchB = b.expect<ServerMessage.MatchFound>()
        assertEquals(matchA.roomId, matchB.roomId)
        assertEquals(mode, matchA.mode)
        assertEquals(mode.wordCount, matchA.words.size)
        assertEquals(matchA.words, matchB.words)
        assertEquals(20, matchA.roundSeconds)
        assertEquals(setOf("Alice", "Bob"), matchA.players.toSet())
        assertTrue(matchA.endsAtEpochMs > System.currentTimeMillis())

        val textA = sentenceWith(matchA.words, 0)
        val textB = sentenceWith(matchA.words, 1)

        a.sendMsg(ClientMessage.Submit(matchA.roomId, textA))
        assertEquals(matchA.roomId, a.expect<ServerMessage.SubmissionAck>().roomId)
        // Double submission is rejected.
        a.sendMsg(ClientMessage.Submit(matchA.roomId, textA))
        assertEquals("already_submitted", a.expect<ServerMessage.Error>().code)

        b.sendMsg(ClientMessage.Submit(matchA.roomId, textB))
        b.expect<ServerMessage.SubmissionAck>()

        val resultA = a.expect<ServerMessage.RoundResult>()
        val resultB = b.expect<ServerMessage.RoundResult>()
        assertEquals(resultA, resultB)
        assertEquals(matchA.words, resultA.words)
        assertEquals(2, resultA.results.size)

        val evalA = CompetitionRules.score(textA, matchA.words, mode)
        val evalB = CompetitionRules.score(textB, matchA.words, mode)
        val byId = resultA.results.associateBy { it.playerId }
        assertTrue(byId.getValue(alice.playerId).submitted)
        assertEquals(evalA.scores, byId.getValue(alice.playerId).scores)
        assertEquals(evalA.scores.overall, byId.getValue(alice.playerId).overall)
        assertEquals(evalB.scores, byId.getValue(bob.playerId).scores)
        assertEquals(evalB.corrected, byId.getValue(bob.playerId).corrected)

        val expectedRanking = CompetitionRules.rank(listOf(alice.playerId to (evalA to 0L), bob.playerId to (evalB to 1L)))
        assertEquals(expectedRanking, resultA.ranking)
        assertEquals("results are listed in ranking order", resultA.ranking, resultA.results.map { it.playerId })

        // Leaderboard reflects the finished game (persisted before the result was broadcast).
        val board = client.get("/v1/leaderboard?mode=${mode.name}") { bearerAuth(alice.token) }
        assertEquals(HttpStatusCode.OK, board.status)
        val entries = LexiJson.decodeFromString(ListSerializer(LeaderboardEntry.serializer()), board.bodyAsText())
        assertEquals(2, entries.size)
        val winner = expectedRanking.first()
        assertEquals(winner, entries[0].playerId)
        assertEquals(1, entries[0].rank)
        assertEquals(1, entries[0].wins)
        assertEquals(0, entries[1].wins)
        assertTrue(entries.all { it.games == 1 })
        val winnerScore = if (winner == alice.playerId) evalA.scores.overall else evalB.scores.overall
        assertEquals(winnerScore.toDouble(), entries[0].averageScore, 0.01)

        // Other modes have no games -> empty list, never fabricated entries.
        val other = client.get("/v1/leaderboard?mode=${BattleMode.TIMED_WRITING.name}") { bearerAuth(alice.token) }
        assertEquals("[]", other.bodyAsText().trim())

        // A player can report an opponent; the report keeps a snapshot for moderators.
        a.sendMsg(ClientMessage.Report(matchA.roomId, bob.playerId, "offensive language"))
        a.sendMsg(ClientMessage.Ping)
        a.expect<ServerMessage.Pong>()
        val reports = store.reports()
        assertEquals(1, reports.size)
        assertEquals(alice.playerId, reports[0].reporterId)
        assertEquals(bob.playerId, reports[0].reportedId)
        assertEquals(textB, reports[0].reportedText)

        a.close()
        b.close()
    }

    @Test
    fun `late submission is rejected and non-submitters score zero`() = lexiTest(config = testConfig(battle = fastBattle(roundSeconds = 1))) { _ ->
        val ws = wsClient()
        val alice = client.register("Fast Alice")
        val bob = client.register("Slow Bob")
        val a = ws.webSocketSession("/v1/battle") { header(HttpHeaders.Authorization, "Bearer ${alice.token}") }
        val b = ws.webSocketSession("/v1/battle") { header(HttpHeaders.Authorization, "Bearer ${bob.token}") }
        a.expect<ServerMessage.Welcome>()
        b.expect<ServerMessage.Welcome>()
        a.sendMsg(ClientMessage.JoinQueue(mode))
        b.sendMsg(ClientMessage.JoinQueue(mode))
        val match = a.expect<ServerMessage.MatchFound>()
        b.expect<ServerMessage.MatchFound>()
        assertEquals(1, match.roundSeconds)

        a.sendMsg(ClientMessage.Submit(match.roomId, sentenceWith(match.words, 0)))
        a.expect<ServerMessage.SubmissionAck>()

        // Bob waits too long: the timer (round + grace) ends the round.
        val result = b.expect<ServerMessage.RoundResult>(ms = 5_000)
        a.expect<ServerMessage.RoundResult>()
        assertEquals(listOf(alice.playerId, bob.playerId), result.ranking)
        val bobResult = result.results.single { it.playerId == bob.playerId }
        assertFalse(bobResult.submitted)
        assertEquals(0, bobResult.overall)
        assertEquals(0, bobResult.scores.grammar)

        b.sendMsg(ClientMessage.Submit(match.roomId, "Too late but I tried to use the words."))
        assertEquals("late", b.expect<ServerMessage.Error>().code)
        a.close()
        b.close()
    }

    @Test
    fun `a lone player is never matched with a fake opponent`() = lexiTest { _ ->
        val ws = wsClient()
        val solo = client.register("Solo")
        val s = ws.webSocketSession("/v1/battle") { header(HttpHeaders.Authorization, "Bearer ${solo.token}") }
        s.expect<ServerMessage.Welcome>()
        s.sendMsg(ClientMessage.JoinQueue(mode))
        assertEquals(1, s.expect<ServerMessage.Queued>().waiting)
        assertNull("no match without a second real player", s.maybe<ServerMessage.MatchFound>(1_000))
        s.close()
    }

    @Test
    fun `disconnect notifies opponents and rejoin restores the round`() = lexiTest(config = testConfig(battle = fastBattle(roundSeconds = 30))) { _ ->
        val ws = wsClient()
        val alice = client.register("Alice R")
        val bob = client.register("Bob R")
        val a = ws.webSocketSession("/v1/battle") { header(HttpHeaders.Authorization, "Bearer ${alice.token}") }
        val b = ws.webSocketSession("/v1/battle") { header(HttpHeaders.Authorization, "Bearer ${bob.token}") }
        a.expect<ServerMessage.Welcome>()
        b.expect<ServerMessage.Welcome>()
        a.sendMsg(ClientMessage.JoinQueue(mode))
        b.sendMsg(ClientMessage.JoinQueue(mode))
        val match = a.expect<ServerMessage.MatchFound>()
        b.expect<ServerMessage.MatchFound>()

        b.close()
        val left = a.expect<ServerMessage.PlayerLeft>()
        assertEquals(match.roomId, left.roomId)
        assertEquals("Bob R", left.displayName)

        val b2 = ws.webSocketSession("/v1/battle") { header(HttpHeaders.Authorization, "Bearer ${bob.token}") }
        b2.expect<ServerMessage.Welcome>()
        // Still in the running round: cannot queue again until it ends.
        b2.sendMsg(ClientMessage.JoinQueue(mode))
        assertEquals("in_room", b2.expect<ServerMessage.Error>().code)
        b2.sendMsg(ClientMessage.Rejoin(match.roomId))
        val again = b2.expect<ServerMessage.MatchFound>()
        assertEquals(match.roomId, again.roomId)
        assertEquals(match.words, again.words)
        assertEquals(match.endsAtEpochMs, again.endsAtEpochMs)
        assertTrue(again.roundSeconds in 1..30)

        b2.sendMsg(ClientMessage.Submit(match.roomId, sentenceWith(match.words, 1)))
        b2.expect<ServerMessage.SubmissionAck>()
        a.sendMsg(ClientMessage.Submit(match.roomId, sentenceWith(match.words, 0)))
        a.expect<ServerMessage.SubmissionAck>()
        assertEquals(2, b2.expect<ServerMessage.RoundResult>().results.count { it.submitted })
        a.close()
        b2.close()
    }

    @Test
    fun `malformed messages get bad_message and flooding is rate limited`() {
        val settings = fastBattle().copy(messageLimit = 5, maxViolations = 2)
        lexiTest(config = testConfig(battle = settings)) { _ ->
            val ws = wsClient()
            val u = client.register("Flooder")
            val s = ws.webSocketSession("/v1/battle") { header(HttpHeaders.Authorization, "Bearer ${u.token}") }
            s.expect<ServerMessage.Welcome>()
            s.sendRaw("this is not json")
            assertEquals("bad_message", s.expect<ServerMessage.Error>().code)
            s.sendRaw("""{"type":"unknown_type"}""")
            assertEquals("bad_message", s.expect<ServerMessage.Error>().code)
            s.sendMsg(ClientMessage.Ping)
            s.expect<ServerMessage.Pong>()
            // 3 messages used so far; 2 more allowed, then rate_limited.
            repeat(3) { s.sendMsg(ClientMessage.Ping) }
            s.expect<ServerMessage.Pong>()
            s.expect<ServerMessage.Pong>()
            assertEquals("rate_limited", s.expect<ServerMessage.Error>().code)
            s.close()
        }
    }

    @Test
    fun `submissions from non-members and unknown rooms are rejected`() = lexiTest { _ ->
        val ws = wsClient()
        val u = client.register("Outsider")
        val s = ws.webSocketSession("/v1/battle") { header(HttpHeaders.Authorization, "Bearer ${u.token}") }
        s.expect<ServerMessage.Welcome>()
        s.sendMsg(ClientMessage.Submit("no-such-room", "hello"))
        assertEquals("unknown_room", s.expect<ServerMessage.Error>().code)
        s.sendMsg(ClientMessage.Rejoin("no-such-room"))
        assertEquals("unknown_room", s.expect<ServerMessage.Error>().code)
        s.close()
    }
}
