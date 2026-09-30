package com.proles.server.services

import com.proles.server.model.ChatRealtimeEvent
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ChatRealtimeHub {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val mutex = Mutex()
    private val sessions = mutableMapOf<UUID, MutableSet<DefaultWebSocketServerSession>>()
    private val onlineUsers = ConcurrentHashMap.newKeySet<UUID>()

    suspend fun register(
        userId: UUID,
        session: DefaultWebSocketServerSession
    ): Boolean {
        return mutex.withLock {
            val wasOffline = sessions[userId].isNullOrEmpty()
            sessions.getOrPut(userId) { mutableSetOf() }.add(session)
            onlineUsers.add(userId)
            wasOffline
        }
    }

    suspend fun unregister(
        userId: UUID,
        session: DefaultWebSocketServerSession
    ): Boolean {
        return mutex.withLock {
            val userSessions = sessions[userId] ?: return@withLock false
            userSessions.remove(session)

            if (userSessions.isEmpty()) {
                sessions.remove(userId)
                onlineUsers.remove(userId)
                true
            } else {
                false
            }
        }
    }

    fun isOnline(userId: UUID): Boolean = onlineUsers.contains(userId)

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
                        onlineUsers.remove(userId)
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

    suspend fun broadcast(event: ChatRealtimeEvent) {
        val recipientIds = mutex.withLock { sessions.keys.toList() }
        sendToUsers(recipientIds, event)
    }
}
