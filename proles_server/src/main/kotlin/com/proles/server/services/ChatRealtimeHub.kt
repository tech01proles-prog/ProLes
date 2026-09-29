package com.proles.server.services

import com.proles.server.model.ChatRealtimeEvent
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

object ChatRealtimeHub {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val mutex = Mutex()
    private val sessions = mutableMapOf<UUID, MutableSet<DefaultWebSocketServerSession>>()

    suspend fun register(
        userId: UUID,
        session: DefaultWebSocketServerSession
    ) {
        mutex.withLock {
            sessions.getOrPut(userId) { mutableSetOf() }.add(session)
        }
    }

    suspend fun unregister(
        userId: UUID,
        session: DefaultWebSocketServerSession
    ) {
        mutex.withLock {
            sessions[userId]?.let { userSessions ->
                userSessions.remove(session)

                if (userSessions.isEmpty()) {
                    sessions.remove(userId)
                }
            }
        }
    }

    suspend fun send(
        userId: UUID,
        event: ChatRealtimeEvent
    ) {
        val payload = json.encodeToString(event)
        val recipients = mutex.withLock {
            sessions[userId]?.toList().orEmpty()
        }

        val failed = mutableListOf<DefaultWebSocketServerSession>()

        recipients.forEach { recipient ->
            runCatching {
                recipient.send(Frame.Text(payload))
            }.onFailure {
                failed += recipient
            }
        }

        if (failed.isNotEmpty()) {
            mutex.withLock {
                sessions[userId]?.let { userSessions ->
                    userSessions.removeAll(failed.toSet())

                    if (userSessions.isEmpty()) {
                        sessions.remove(userId)
                    }
                }
            }
        }
    }

    suspend fun sendToUsers(
        userIds: Collection<UUID>,
        event: ChatRealtimeEvent
    ) {
        userIds.distinct().forEach { userId ->
            send(userId, event)
        }
    }
}