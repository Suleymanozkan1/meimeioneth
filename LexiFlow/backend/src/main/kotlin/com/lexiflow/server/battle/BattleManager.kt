package com.lexiflow.server.battle

import com.lexiflow.core.competition.BattleMode
import com.lexiflow.core.competition.ClientMessage
import com.lexiflow.core.competition.CompetitionRules
import com.lexiflow.core.competition.PlayerResult
import com.lexiflow.core.competition.ServerMessage
import com.lexiflow.core.competition.WordBank
import com.lexiflow.core.language.Evaluation
import com.lexiflow.core.language.ScoreBreakdown
import com.lexiflow.core.language.SentenceEvaluator
import com.lexiflow.server.BattleSettings
import com.lexiflow.server.store.BattleResultRecord
import com.lexiflow.server.store.ReportRecord
import com.lexiflow.server.store.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

data class PlayerInfo(val id: String, val displayName: String)

private data class Submission(val text: String, val atMs: Long)

private class Room(
    val id: String,
    val mode: BattleMode,
    val words: List<String>,
    val roundSeconds: Int,
    val endsAtMs: Long,
    val players: List<PlayerInfo>,
) {
    val submissions = LinkedHashMap<String, Submission>()
    val connected = HashSet<String>()
    val reports = HashSet<Pair<String, String>>()
    var finished = false
    var result: ServerMessage.RoundResult? = null
    var timer: Job? = null

    fun isMember(playerId: String) = players.any { it.id == playerId }
    fun info(playerId: String) = players.first { it.id == playerId }
}

/**
 * Real-time word battle: per-mode matchmaking queues, rooms, timers, deterministic scoring with
 * [CompetitionRules] and persistence of results. Only real connected players are ever matched.
 *
 * All state is guarded by one [Mutex]; messages are delivered through each connection's non-blocking
 * outgoing channel, so no network I/O happens while the lock is held.
 */
class BattleManager(
    private val store: Store,
    private val settings: BattleSettings,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    private val log = LoggerFactory.getLogger(BattleManager::class.java)
    private val lock = Mutex()

    private val connections = ConcurrentHashMap<String, PlayerConnection>()
    private val queues = EnumMap<BattleMode, LinkedHashMap<String, PlayerInfo>>(BattleMode::class.java).apply {
        BattleMode.entries.forEach { put(it, LinkedHashMap()) }
    }
    private val queueTimers = EnumMap<BattleMode, Job>(BattleMode::class.java)
    private val rooms = HashMap<String, Room>()
    private val activeRoomByPlayer = HashMap<String, String>()

    /**
     * Leaderboard win crediting (guarded by [lock]). Accounts are free, so without these limits one person could farm
     * ranked wins by matching their own accounts against each other. Counters are per UTC day and kept in memory.
     */
    private var creditDay = -1L
    private val creditedPerOpponentSet = HashMap<String, Int>()
    private val creditedPerPlayer = HashMap<String, Int>()

    /** Number of currently connected sockets (real players only). */
    fun onlinePlayers(): Int = connections.size

    fun newRateLimiter() = MessageRateLimiter(settings.messageLimit, settings.messageWindowMs, settings.maxViolations, nowMs)

    suspend fun connect(conn: PlayerConnection) {
        lock.withLock {
            val previous = connections.put(conn.playerId, conn)
            if (previous != null && previous !== conn) {
                previous.send(error("replaced", "Signed in from another connection"))
                previous.kill("replaced by a newer connection")
            }
            conn.send(ServerMessage.Welcome(conn.playerId, conn.displayName, connections.size))
        }
    }

    suspend fun disconnect(conn: PlayerConnection) {
        lock.withLock {
            if (connections[conn.playerId] !== conn) return // replaced by a newer socket; keep its state
            connections.remove(conn.playerId)
            removeFromQueues(conn.playerId)
            val room = activeRoomByPlayer[conn.playerId]?.let { rooms[it] }
            if (room != null && !room.finished) {
                room.connected.remove(conn.playerId)
                broadcast(room, ServerMessage.PlayerLeft(room.id, conn.displayName), except = conn.playerId)
            }
        }
    }

    suspend fun handle(conn: PlayerConnection, message: ClientMessage) {
        try {
            when (message) {
                is ClientMessage.JoinQueue -> joinQueue(conn, message.mode)
                is ClientMessage.LeaveQueue -> lock.withLock { removeFromQueues(conn.playerId) }
                is ClientMessage.Submit -> submit(conn, message.roomId, message.text)
                is ClientMessage.Rejoin -> rejoin(conn, message.roomId)
                is ClientMessage.Report -> report(conn, message)
                is ClientMessage.Ping -> conn.send(ServerMessage.Pong)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("battle message handling failed type={}", message::class.simpleName, e)
            conn.send(error("internal", "Unexpected server error"))
        }
    }

    // ---------------------------------------------------------------- matchmaking

    private suspend fun joinQueue(conn: PlayerConnection, mode: BattleMode) = lock.withLock {
        if (activeRoomByPlayer.containsKey(conn.playerId)) {
            conn.send(error("in_room", "You are already in a round"))
            return@withLock
        }
        removeFromQueues(conn.playerId)
        queues.getValue(mode)[conn.playerId] = PlayerInfo(conn.playerId, conn.displayName)
        notifyQueue(mode)
        tryMatch(mode)
    }

    /** Must hold [lock]. */
    private fun removeFromQueues(playerId: String) {
        queues.forEach { (mode, q) ->
            if (q.remove(playerId) != null) {
                notifyQueue(mode)
                if (q.size < 2) queueTimers.remove(mode)?.cancel()
            }
        }
    }

    /** Must hold [lock]. */
    private fun notifyQueue(mode: BattleMode) {
        val q = queues.getValue(mode)
        val msg = ServerMessage.Queued(mode, q.size)
        q.keys.forEach { connections[it]?.send(msg) }
    }

    /** Must hold [lock]. Full rooms start immediately; 2-3 waiting players start after the match window. */
    private fun tryMatch(mode: BattleMode) {
        val q = queues.getValue(mode)
        while (q.size >= settings.maxPlayersPerRoom) {
            startRoom(mode, q.values.take(settings.maxPlayersPerRoom))
        }
        if (q.size >= 2 && queueTimers[mode] == null) {
            queueTimers[mode] = scope.launch {
                delay(settings.matchWindowMs)
                lock.withLock {
                    if (queueTimers[mode] === coroutineContext[Job]) queueTimers.remove(mode)
                    val waiting = queues.getValue(mode)
                    if (waiting.size >= 2) startRoom(mode, waiting.values.take(settings.maxPlayersPerRoom))
                }
            }
        } else if (q.size < 2) {
            queueTimers.remove(mode)?.cancel()
        }
    }

    /** Must hold [lock]. */
    private fun startRoom(mode: BattleMode, players: List<PlayerInfo>) {
        val q = queues.getValue(mode)
        players.forEach { q.remove(it.id) }
        val roundSeconds = settings.roundSecondsOverride ?: mode.roundSeconds
        val now = nowMs()
        val room = Room(
            id = UUID.randomUUID().toString(),
            mode = mode,
            words = WordBank.draw(mode.wordCount, random),
            roundSeconds = roundSeconds,
            endsAtMs = now + roundSeconds * 1000L,
            players = players,
        )
        rooms[room.id] = room
        val match = matchFound(room, roundSeconds)
        players.forEach { p ->
            activeRoomByPlayer[p.id] = room.id
            val c = connections[p.id]
            if (c != null) {
                room.connected += p.id
                c.send(match)
            }
        }
        room.timer = scope.launch {
            delay((room.endsAtMs + settings.graceMs - nowMs()).coerceAtLeast(0))
            finishRoom(room.id)
        }
        log.info("room started room={} mode={} players={}", room.id, mode, players.size)
        if (q.isNotEmpty()) notifyQueue(mode)
    }

    private fun matchFound(room: Room, roundSeconds: Int) = ServerMessage.MatchFound(
        roomId = room.id,
        mode = room.mode,
        words = room.words,
        roundSeconds = roundSeconds,
        endsAtEpochMs = room.endsAtMs,
        players = room.players.map { it.displayName },
    )

    // ---------------------------------------------------------------- round

    private suspend fun submit(conn: PlayerConnection, roomId: String, text: String) {
        val complete = lock.withLock {
            val room = rooms[roomId]
            val now = nowMs()
            when {
                room == null -> conn.send(error("unknown_room", "Unknown room"))
                !room.isMember(conn.playerId) -> conn.send(error("not_member", "You are not a player in this room"))
                now > room.endsAtMs + settings.graceMs -> conn.send(error("late", "The round is over; submission was too late"))
                room.finished -> conn.send(error("round_over", "The round is over"))
                room.submissions.containsKey(conn.playerId) -> conn.send(error("already_submitted", "You already submitted in this round"))
                text.length > CompetitionRules.MAX_CHARS -> conn.send(error("too_long", "Text is longer than ${CompetitionRules.MAX_CHARS} characters"))
                else -> {
                    val clean = SentenceEvaluator.sanitize(text, CompetitionRules.MAX_CHARS)
                    if (clean.isBlank()) {
                        conn.send(error("empty", "Text must not be empty"))
                    } else {
                        room.submissions[conn.playerId] = Submission(clean, now)
                        conn.send(ServerMessage.SubmissionAck(room.id))
                        return@withLock room.submissions.size == room.players.size
                    }
                }
            }
            false
        }
        if (complete) finishRoom(roomId)
    }

    private suspend fun finishRoom(roomId: String) {
        val records: List<BattleResultRecord>
        val result: ServerMessage.RoundResult
        lock.withLock {
            val room = rooms[roomId] ?: return
            if (room.finished) return
            room.finished = true
            val currentJob = coroutineContext[Job]
            room.timer?.let { if (it !== currentJob) it.cancel() }
            room.players.forEach { p -> if (activeRoomByPlayer[p.id] == room.id) activeRoomByPlayer.remove(p.id) }

            val finishedAt = nowMs()
            val evaluations: Map<String, Pair<Evaluation, Long>> = room.submissions.mapValues { (_, s) ->
                CompetitionRules.score(s.text, room.words, room.mode) to s.atMs
            }
            val ranked = CompetitionRules.rank(evaluations.map { (id, e) -> id to e })
            val ranking = ranked + room.players.map { it.id }.filter { it !in evaluations }
            val results = ranking.map { id ->
                val p = room.info(id)
                val e = evaluations[id]?.first
                if (e != null) {
                    PlayerResult(p.id, p.displayName, e.text, e.scores, e.scores.overall, e.corrected, e.keyPoints, e.rationaleTr, submitted = true)
                } else {
                    PlayerResult(p.id, p.displayName, "", ZERO_SCORES, 0, "", emptyList(), listOf("Süre içinde gönderim yapılmadı."), submitted = false)
                }
            }
            result = ServerMessage.RoundResult(room.id, room.words, results, ranking)
            room.result = result
            val creditedWin = results.firstOrNull()?.takeIf { it.submitted }?.let { winner ->
                creditWin(winner.playerId, results.drop(1), finishedAt)
            } ?: false
            records = results.mapIndexed { index, r ->
                BattleResultRecord(room.id, room.mode, r.playerId, r.displayName, r.overall, index + 1, won = index == 0 && r.submitted && creditedWin, submitted = r.submitted, finishedAtMs = finishedAt)
            }
            log.info("room finished room={} mode={} submissions={}/{}", room.id, room.mode, evaluations.size, room.players.size)
        }

        // Persist before announcing so clients that query the leaderboard right after the result see it.
        try {
            store.saveBattleResults(records)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("failed to persist battle results room={}", roomId, e)
        }

        lock.withLock {
            val room = rooms[roomId] ?: return
            room.players.forEach { p -> connections[p.id]?.send(result) }
        }
        scope.launch {
            delay(settings.finishedRoomRetentionMs)
            lock.withLock { rooms.remove(roomId) }
        }
    }

    /** Must be called while holding [lock]. Returns whether this round's win counts for the leaderboard. */
    private fun creditWin(winnerId: String, opponents: List<PlayerResult>, atMs: Long): Boolean {
        // A real contest needs at least one opponent who actually attempted the task.
        if (opponents.none { it.submitted && it.scores.taskCompletion >= settings.minRankedOpponentTaskCompletion }) return false
        val day = Math.floorDiv(atMs, DAY_MS)
        if (day != creditDay) {
            creditDay = day
            creditedPerOpponentSet.clear()
            creditedPerPlayer.clear()
        }
        val setKey = winnerId + "|" + opponents.map { it.playerId }.sorted().joinToString(",")
        val perSet = creditedPerOpponentSet[setKey] ?: 0
        val perPlayer = creditedPerPlayer[winnerId] ?: 0
        if (perSet >= settings.maxCreditedWinsPerOpponentSet || perPlayer >= settings.maxCreditedWinsPerDay) return false
        creditedPerOpponentSet[setKey] = perSet + 1
        creditedPerPlayer[winnerId] = perPlayer + 1
        return true
    }

    private suspend fun rejoin(conn: PlayerConnection, roomId: String) = lock.withLock {
        val room = rooms[roomId]
        when {
            room == null -> conn.send(error("unknown_room", "Unknown room"))
            !room.isMember(conn.playerId) -> conn.send(error("not_member", "You are not a player in this room"))
            room.finished -> {
                val r = room.result
                if (r != null) conn.send(r) else conn.send(error("round_over", "The round is over"))
            }
            else -> {
                if (activeRoomByPlayer[conn.playerId] != room.id) {
                    removeFromQueues(conn.playerId)
                    activeRoomByPlayer[conn.playerId] = room.id
                }
                room.connected += conn.playerId
                val remainingMs = (room.endsAtMs - nowMs()).coerceAtLeast(0)
                val remainingSeconds = ((remainingMs + 999) / 1000).toInt()
                conn.send(matchFound(room, remainingSeconds))
                if (room.submissions.containsKey(conn.playerId)) conn.send(ServerMessage.SubmissionAck(room.id))
            }
        }
    }

    private suspend fun report(conn: PlayerConnection, message: ClientMessage.Report) {
        val reason = SentenceEvaluator.sanitize(message.reason, MAX_REPORT_REASON)
        val record = lock.withLock {
            val room = rooms[message.roomId]
            when {
                room == null -> { conn.send(error("unknown_room", "Unknown room")); null }
                !room.isMember(conn.playerId) || !room.isMember(message.playerId) || message.playerId == conn.playerId ->
                    { conn.send(error("invalid_report", "You can only report another player of your room")); null }
                reason.isBlank() -> { conn.send(error("invalid_report", "Please give a reason")); null }
                !room.reports.add(conn.playerId to message.playerId) -> null // duplicate report: ignore silently
                else -> ReportRecord(
                    id = UUID.randomUUID().toString(),
                    roomId = room.id,
                    reporterId = conn.playerId,
                    reportedId = message.playerId,
                    reason = reason,
                    reportedText = room.submissions[message.playerId]?.text,
                    createdAtMs = nowMs(),
                )
            }
        } ?: return
        try {
            store.saveReport(record)
            log.info("moderation report stored room={} report={}", record.roomId, record.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("failed to store report room={}", record.roomId, e)
            conn.send(error("internal", "Report could not be saved, please try again"))
        }
    }

    /** Must hold [lock]. */
    private fun broadcast(room: Room, message: ServerMessage, except: String? = null) {
        room.players.forEach { p -> if (p.id != except && p.id in room.connected) connections[p.id]?.send(message) }
    }

    companion object {
        const val MAX_REPORT_REASON = 500
        private val ZERO_SCORES = ScoreBreakdown(0, 0, 0, 0, 0, 0)
        private const val DAY_MS = 24 * 60 * 60_000L
        fun error(code: String, message: String) = ServerMessage.Error(code, message)
    }
}
