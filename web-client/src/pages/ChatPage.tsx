import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import api from '../api/client';
import type { ChatConversationDto, ChatMessageDto, ChatMessagesPageDto, ChatUserDto, UserDto } from '../types';

type ChatRealtimeEvent = {
  type:
    | 'CONNECTED'
    | 'MESSAGE_CREATED'
    | 'MESSAGE_UPDATED'
    | 'MESSAGE_DELETED'
    | 'MESSAGE_DELIVERY_UPDATED'
    | 'CONVERSATION_READ'
    | 'PRESENCE_CHANGED';
  conversationId?: string;
  message?: ChatMessageDto;
  userId?: string;
  messageId?: string;
  unreadCount?: number;
  occurredAt: number;
  online?: boolean;
};

const buildChatWebSocketUrl = (token: string) => {
  const configuredWsUrl = import.meta.env.VITE_WS_URL as string | undefined;
  const configuredApiUrl = import.meta.env.VITE_API_URL as string | undefined;

  if (configuredWsUrl) {
    const wsUrl = new URL(configuredWsUrl, window.location.origin);
    wsUrl.searchParams.set('token', token);
    return wsUrl.toString();
  }

  const apiUrl = new URL(
    configuredApiUrl || '/api/v1',
    window.location.origin
  );

  apiUrl.protocol = apiUrl.protocol === 'https:' ? 'wss:' : 'ws:';
  apiUrl.pathname = `${apiUrl.pathname.replace(/\/+$/, '')}/chat/ws`;
  apiUrl.search = '';
  apiUrl.searchParams.set('token', token);

  return apiUrl.toString();
};

const createClientMessageId = () => {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return `web-${Date.now()}-${globalThis.crypto.randomUUID()}`;
  }

  const randomPart = Array.from({ length: 16 }, () =>
    Math.floor(Math.random() * 256)
      .toString(16)
      .padStart(2, '0')
  ).join('');

  return `web-${Date.now()}-${randomPart}`;
};

const formatSize = (bytes: number) => bytes < 1024 * 1024 ? `${Math.ceil(bytes / 1024)} КБ` : `${(bytes / 1024 / 1024).toFixed(1)} МБ`;

export function ChatPage() {
  const currentUser = useMemo<UserDto | null>(() => { try { return JSON.parse(localStorage.getItem('proles_user') || 'null'); } catch { return null; } }, []);
  const [conversations, setConversations] = useState<ChatConversationDto[]>([]);
  const [users, setUsers] = useState<ChatUserDto[]>([]);
  const [activeId, setActiveId] = useState('');
  const [messages, setMessages] = useState<ChatMessageDto[]>([]);
  const messagesContainerRef = useRef<HTMLDivElement>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [text, setText] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [editingMessageId, setEditingMessageId] = useState('');
  const [messageMenuId, setMessageMenuId] = useState('');
  const [editingText, setEditingText] = useState('');
  const [savingEdit, setSavingEdit] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const composerRef = useRef<HTMLTextAreaElement>(null);
  const activeIdRef = useRef('');

  const loadSidebar = useCallback(async () => {
    const [conversationResponse, userResponse] = await Promise.all([api.get<ChatConversationDto[]>('/chat/conversations'), api.get<ChatUserDto[]>('/chat/users')]);
    setConversations(conversationResponse.data);
    setUsers(userResponse.data);
    setActiveId((current) => current || conversationResponse.data[0]?.id || '');
  }, []);

  const loadMessages = useCallback(async (conversationId: string, nextCursor?: string | null) => {
    const { data } = await api.get<ChatMessagesPageDto>(`/chat/conversations/${conversationId}/messages`, { params: { limit: 50, cursor: nextCursor || undefined } });
    const orderedItems = data.items.slice().reverse();
    setMessages((current) => nextCursor ? [...orderedItems, ...current] : orderedItems);
    setCursor(data.nextCursor);
    if (!nextCursor) await api.post(`/chat/conversations/${conversationId}/read`, { messageId: data.items[0]?.id || null });
  }, []);

  useEffect(() => {
    activeIdRef.current = activeId;
  }, [activeId]);

   useEffect(() => {
    loadSidebar()
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [loadSidebar]);

  useEffect(() => { if (activeId) void loadMessages(activeId); else setMessages([]); }, [activeId, loadMessages]);

  useEffect(() => {
    if (!activeId || cursor !== null) return;
    requestAnimationFrame(() => {
      const container = messagesContainerRef.current;
      if (container) container.scrollTop = container.scrollHeight;
    });
  }, [activeId, cursor]);

  useEffect(() => {
    const token = localStorage.getItem('proles_token');
    if (!token) return;

    let socket: WebSocket | null = null;
    let reconnectTimer: number | null = null;
    let closedByComponent = false;
    let reconnectAttempt = 0;

    const connect = () => {
      socket = new WebSocket(buildChatWebSocketUrl(token));

      socket.onopen = () => {
        reconnectAttempt = 0;
        void loadSidebar();

        if (activeIdRef.current) {
          void loadMessages(activeIdRef.current);
        }
      };

      socket.onmessage = (event) => {
        let realtimeEvent: ChatRealtimeEvent;

        try {
          realtimeEvent = JSON.parse(event.data) as ChatRealtimeEvent;
        } catch {
          return;
        }

        if (
          realtimeEvent.type === 'MESSAGE_CREATED' &&
          realtimeEvent.conversationId &&
          realtimeEvent.message
        ) {
          const incoming = realtimeEvent.message;

          if (realtimeEvent.conversationId === activeIdRef.current) {
            setMessages((current) => {
              if (
                current.some(
                  (message) =>
                    message.id === incoming.id ||
                    message.clientMessageId === incoming.clientMessageId
                )
              ) {
                return current;
              }

              return [...current, incoming];
            });

            void api.post(
              `/chat/conversations/${realtimeEvent.conversationId}/read`,
              { messageId: incoming.id }
            );
          }

          void loadSidebar();
          return;
        }

        if (
          realtimeEvent.type === 'MESSAGE_UPDATED' ||
          realtimeEvent.type === 'MESSAGE_DELETED'
        ) {
          const incoming = realtimeEvent.message;
          if (incoming) {
            setMessages((current) =>
              current.map((message) =>
                message.id === incoming.id ? incoming : message
              )
            );
          }
          void loadSidebar();
          return;
        }

        if (
          realtimeEvent.type === 'MESSAGE_DELIVERY_UPDATED' &&
          realtimeEvent.messageId
        ) {
          setMessages((current) =>
            current.map((message) =>
              message.id === realtimeEvent.messageId &&
              message.senderId === currentUser?.id &&
              message.deliveryStatus === 'SENT'
                ? { ...message, deliveryStatus: 'DELIVERED' }
                : message
            )
          );
          return;
        }

        if (
          realtimeEvent.type === 'PRESENCE_CHANGED' &&
          realtimeEvent.userId
        ) {
          const online = realtimeEvent.online ?? false;
          setUsers((current) =>
            current.map((user) =>
              user.id === realtimeEvent.userId ? { ...user, online } : user
            )
          );
          setConversations((current) =>
            current.map((conversation) => ({
              ...conversation,
              members: conversation.members.map((member) =>
                member.id === realtimeEvent.userId
                  ? { ...member, online }
                  : member
              ),
            }))
          );
          return;
        }

        if (
          realtimeEvent.type === 'CONVERSATION_READ' &&
          realtimeEvent.conversationId === activeIdRef.current &&
          realtimeEvent.userId &&
          realtimeEvent.userId !== currentUser?.id
        ) {
          void loadMessages(activeIdRef.current);
          void loadSidebar();
        }
      };

      socket.onerror = () => {
        socket?.close();
      };

      socket.onclose = () => {
        if (closedByComponent) return;

        reconnectAttempt += 1;
        const delay = Math.min(30_000, 1_000 * 2 ** Math.min(reconnectAttempt, 5));

        reconnectTimer = window.setTimeout(connect, delay);
      };
    };

    connect();

    return () => {
      closedByComponent = true;

      if (reconnectTimer !== null) {
        window.clearTimeout(reconnectTimer);
      }

      if (socket?.readyState === WebSocket.OPEN) {
        socket.close(1000, 'Component unmounted');
      } else if (socket?.readyState === WebSocket.CONNECTING) {
        socket.onopen = () => {
          socket?.close(1000, 'Component unmounted');
        };
      }
    };
  }, [loadMessages, loadSidebar]);

  const startConversation = async (userId: string) => {
    const { data } = await api.post<ChatConversationDto>('/chat/conversations/direct', { userId });
    await loadSidebar();
    setActiveId(data.id);
  };

  const send = async () => {
    if (!activeId || (!text.trim() && !file) || sending) return;
    setSending(true);
    const clientMessageId = createClientMessageId();
    try {
      if (file) {
        const formData = new FormData();
        formData.append('text', text.trim());
        formData.append('clientMessageId', clientMessageId);
        formData.append('file', file);
        await api.post(`/chat/conversations/${activeId}/attachments`, formData);
      } else {
        await api.post(`/chat/conversations/${activeId}/messages`, { text: text.trim(), clientMessageId, replyToMessageId: null });
      }
      setText('');
      setFile(null);
      if (fileInput.current) fileInput.current.value = '';
      if (composerRef.current) composerRef.current.style.height = '44px';
      await loadSidebar();
    } finally {
      setSending(false);
    }
  };

  const beginEdit = (message: ChatMessageDto) => {
    setEditingMessageId(message.id);
    setMessageMenuId('');
    setEditingText(message.text);
    requestAnimationFrame(() => {
      const input = document.getElementById('chat-edit-input') as HTMLTextAreaElement | null;
      input?.focus();
      if (input) input.setSelectionRange(input.value.length, input.value.length);
    });
  };

  const saveEdit = async () => {
    const value = editingText.trim();
    if (!activeId || !editingMessageId || !value || savingEdit) return;
    setSavingEdit(true);
    try {
      const { data } = await api.put<ChatMessageDto>(
        `/chat/conversations/${activeId}/messages/${editingMessageId}`,
        { text: value }
      );
      setMessages((current) =>
        current.map((message) =>
          message.id === data.id ? data : message
        )
      );
      setEditingMessageId('');
      setEditingText('');
    } finally {
      setSavingEdit(false);
    }
  };

  const deleteMessage = async (message: ChatMessageDto) => {
    if (!activeId || message.deletedAt) return;
    if (!window.confirm('Удалить это сообщение?')) return;

    const { data } = await api.delete<ChatMessageDto>(
      `/chat/conversations/${activeId}/messages/${message.id}`
    );
    setMessages((current) =>
      current.map((item) => item.id === data.id ? data : item)
    );
    setMessageMenuId('');
  };

  const download = async (
    message: ChatMessageDto,
    attachmentId: string,
    fileName: string
  ) => {
    const attachment = message.attachments.find((item) => item.id === attachmentId);
    if (!attachment) return;
    const path = attachment.downloadUrl.replace(/^\/api\/v1/, '');
    const { data } = await api.get(path, { responseType: 'blob' });
    const url = URL.createObjectURL(data);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
  };

  const active = conversations.find((item) => item.id === activeId);
  const activePeer = active?.members.find((member) => member.id !== currentUser?.id);
  const activeOnline = activePeer?.online ?? false;

  if (loading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">◌</div></div>;

  return (
    <div className="flex h-[calc(100dvh-9rem)] min-h-[560px] overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-lg md:grid md:grid-cols-[340px_minmax(0,1fr)]">
      <aside className={`min-h-0 flex-col border-r border-slate-200 bg-slate-50 ${activeId ? 'hidden md:flex' : 'flex'}`}>
        <div className="border-b border-slate-200 bg-white p-4 sm:p-5">
          <div className="text-[10px] font-black uppercase tracking-[0.18em] text-indigo-500">Мессенджер</div>
          <div className="mt-1 flex items-center justify-between gap-3"><h1 className="text-xl font-black tracking-tight text-slate-900">PRO-Chat</h1><span className="rounded-full bg-emerald-100 px-2.5 py-1 text-[10px] font-bold text-emerald-700">онлайн</span></div>
          <select defaultValue="" onChange={(event) => { if (event.target.value) void startConversation(event.target.value); event.target.value = ''; }} className="input mt-4 w-full border-indigo-100 bg-indigo-50/70 font-semibold">
            <option value="">＋ Новый диалог</option>{users.map((user) => <option key={user.id} value={user.id}>{user.online ? '● ' : '○ '}{user.name}{user.position ? ` — ${user.position}` : ''}</option>)}
          </select>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {conversations.map((conversation) => (
            <button key={conversation.id} type="button" onClick={() => setActiveId(conversation.id)} className={`w-full border-b border-slate-200/80 p-4 text-left ${activeId === conversation.id ? 'bg-indigo-50' : 'bg-white hover:bg-slate-100'}`}>
              <div className="flex items-center gap-3"><div className="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-gradient-to-br from-indigo-500 to-fuchsia-500 text-sm font-black text-white">{conversation.title.trim().charAt(0).toUpperCase() || '?'}</div><div className="min-w-0 flex-1"><div className="flex items-center justify-between gap-2">
  <span className="flex min-w-0 items-center gap-2 truncate font-bold text-slate-900">
    <span className={`h-2.5 w-2.5 shrink-0 rounded-full ${conversation.members.find((member) => member.id !== currentUser?.id)?.online ? 'bg-emerald-500' : 'bg-slate-300'}`} />
    <span className="truncate">{conversation.title}</span>
  </span>
  {conversation.unreadCount > 0 && <span className="rounded-full bg-rose-500 px-2 py-0.5 text-xs font-bold text-white">{conversation.unreadCount}</span>}
</div>
<div className="mt-1 truncate text-xs text-slate-500">
  {conversation.lastMessage?.deletedAt ? 'Сообщение удалено' : conversation.lastMessage?.text || 'Нет сообщений'}
</div></div></div>
            </button>
          ))}
          {conversations.length === 0 && <div className="p-8 text-center text-sm text-slate-500">Выберите сотрудника для начала диалога.</div>}
        </div>
      </aside>
      <section className={`min-h-0 min-w-0 flex-1 flex-col bg-white ${activeId ? 'flex' : 'hidden md:flex'}`}>
        {!active ? <div className="flex flex-1 items-center justify-center bg-gradient-to-br from-indigo-50 via-white to-fuchsia-50 p-8 text-center text-slate-500"><div><div className="mx-auto grid h-16 w-16 place-items-center rounded-full bg-indigo-100 text-2xl text-indigo-600">◆</div><div className="mt-4 font-bold text-slate-800">Выберите диалог</div><div className="mt-1 text-sm">Ваши сообщения и файлы появятся здесь.</div></div></div> : (
          <>
            <header className="flex shrink-0 items-center gap-3 border-b border-slate-200 bg-white px-3 py-3 sm:px-5 sm:py-4">
              <button type="button" onClick={() => setActiveId('')} className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-indigo-50 text-lg font-bold text-indigo-600 md:hidden">←</button>
              <div className="grid h-10 w-10 shrink-0 place-items-center rounded-full bg-gradient-to-br from-indigo-500 to-fuchsia-500 text-sm font-black text-white">{active.title.trim().charAt(0).toUpperCase() || '?'}</div>
              <div className="min-w-0">
  <div className="flex items-center gap-2">
    <div className="truncate font-black text-slate-900">{active.title}</div>
    <span className={`h-2.5 w-2.5 shrink-0 rounded-full ${activeOnline ? 'bg-emerald-500' : 'bg-slate-300'}`} />
  </div>
  <div className={activeOnline ? 'text-xs text-emerald-600' : 'text-xs text-slate-400'}>
    {activeOnline ? 'онлайн' : 'не в сети'}
  </div>
</div>
            </header>
            <div ref={messagesContainerRef} className="flex min-h-0 flex-1 flex-col overflow-y-auto bg-slate-50/70 px-3 py-4 sm:p-5">
              <div className="space-y-2.5">
                {messages.map((message) => {
                  const mine = message.senderId === currentUser?.id;
                  const deleted = Boolean(message.deletedAt);
                  const statusLabel =
                    message.deliveryStatus === 'READ'
                      ? '✓✓ просмотрено'
                      : message.deliveryStatus === 'DELIVERED'
                        ? '✓✓ доставлено'
                        : '✓ отправлено';

                  return (
                    <div key={message.id} className={`group relative flex ${mine ? 'justify-end' : 'justify-start'}`}>
                      <div className={`max-w-[92%] rounded-[22px] px-4 py-3 shadow-sm sm:max-w-[78%] ${mine ? 'rounded-br-md bg-indigo-600 text-white' : 'rounded-bl-md border border-slate-200 bg-white text-slate-900'}`}>
                        {!mine && <div className="mb-1 text-xs font-bold text-indigo-600">{message.senderName}</div>}

                        {editingMessageId === message.id && !deleted ? (
                          <div className="min-w-[240px] sm:min-w-[320px]">
                            <textarea
                              id="chat-edit-input"
                              value={editingText}
                              onChange={(event) => setEditingText(event.target.value)}
                              rows={3}
                              maxLength={20000}
                              className="w-full resize-none rounded-2xl border border-white/25 bg-white/15 px-3 py-2 text-sm text-current outline-none"
                            />
                            <div className="mt-2 flex justify-end gap-2">
                              <button type="button" onClick={() => { setEditingMessageId(''); setEditingText(''); }} className="rounded-full bg-white/10 px-3 py-1.5 text-xs font-bold">Отмена</button>
                              <button type="button" onClick={() => void saveEdit()} disabled={!editingText.trim() || savingEdit} className="rounded-full bg-white px-3 py-1.5 text-xs font-bold text-indigo-700 disabled:opacity-50">{savingEdit ? '…' : 'Сохранить'}</button>
                            </div>
                          </div>
                        ) : (
                          <>
                            {deleted ? (
                              <div className="text-sm italic opacity-70">Сообщение удалено</div>
                            ) : (
                              <>
                                {message.text && <div className="whitespace-pre-wrap break-words text-[15px] leading-6">{message.text}</div>}
                                {message.attachments.map((attachment) => (
                                  <button key={attachment.id} type="button" onClick={() => void download(message, attachment.id, attachment.originalName)} className="mt-2 block w-full rounded-2xl border border-current/15 bg-white/10 px-3 py-2.5 text-left text-sm">
                                    <span className="block truncate font-bold">{attachment.originalName}</span>
                                    <span className="text-xs opacity-70">{formatSize(attachment.sizeBytes)}</span>
                                  </button>
                                ))}
                              </>
                            )}

                            <div className="mt-1 flex items-center justify-end gap-2 text-[10px] opacity-65">
                              {mine && <span>{statusLabel}</span>}
                              {message.editedAt && !deleted && <span>отредактировано</span>}
                              <span>{new Date(message.createdAt).toLocaleString('ru-RU')}</span>
                            </div>
                          </>
                        )}
                      </div>

                      {!deleted && (
                        <div className="absolute -top-2 right-1 z-10">
                          <button
                            type="button"
                            onClick={() => setMessageMenuId((current) => current === message.id ? '' : message.id)}
                            className="grid h-8 w-8 place-items-center rounded-full border border-slate-200 bg-white text-slate-500 shadow-sm transition hover:text-slate-900 sm:opacity-0 sm:group-hover:opacity-100"
                            aria-label="Действия сообщения"
                          >⋯</button>
                          {messageMenuId === message.id && (
                            <div className="absolute right-0 top-9 min-w-36 overflow-hidden rounded-xl border border-slate-200 bg-white p-1 text-slate-700 shadow-xl">
                              <button type="button" onClick={() => beginEdit(message)} className="block w-full rounded-lg px-3 py-2 text-left text-xs font-semibold hover:bg-indigo-50">Редактировать</button>
                              <button type="button" onClick={() => void deleteMessage(message)} className="block w-full rounded-lg px-3 py-2 text-left text-xs font-semibold text-rose-600 hover:bg-rose-50">Удалить</button>
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
                {cursor && <button type="button" onClick={() => void loadMessages(activeId, cursor)} className="mx-auto block rounded-full bg-indigo-50 px-4 py-2 text-xs font-bold text-indigo-600">Загрузить предыдущие</button>}
              </div>
            </div>
            <footer className="shrink-0 border-t border-slate-200 bg-white px-3 py-2.5 pb-[max(0.65rem,var(--safe-area-inset-bottom))] sm:p-3">
              {file && <div className="mb-2 flex items-center justify-between gap-2 rounded-2xl bg-amber-50 px-3 py-2 text-sm text-amber-900"><span className="truncate">{file.name} · {formatSize(file.size)}</span><button type="button" onClick={() => setFile(null)} className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-white font-bold text-amber-700 shadow-sm">×</button></div>}
              <div className="flex items-end gap-2">
                <input ref={fileInput} type="file" className="hidden" onChange={(event) => { const next = event.target.files?.[0] || null; if (next && next.size > 100 * 1024 * 1024) { alert('Максимальный размер файла — 100 MiB'); event.target.value = ''; return; } setFile(next); }} />
                <button type="button" onClick={() => fileInput.current?.click()} className="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-emerald-100 text-xl text-emerald-700 hover:bg-emerald-200">＋</button>
                <textarea ref={composerRef} value={text} onChange={(event) => setText(event.target.value)} onInput={(event) => { const input = event.currentTarget; input.style.height = 'auto'; input.style.height = `${Math.min(input.scrollHeight, 120)}px`; }} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void send(); } }} rows={1} maxLength={20000} className="input min-h-[44px] max-h-[120px] flex-1 resize-none overflow-y-auto rounded-[22px] border-slate-200 bg-slate-50 px-4 py-2.5 text-[15px] leading-6" placeholder="Сообщение..." />
                <button type="button" onClick={() => void send()} disabled={sending || (!text.trim() && !file)} className="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-indigo-600 text-lg font-black text-white shadow-md disabled:opacity-40">{sending ? '…' : '➤'}</button>
              </div>
            </footer>
          </>
        )}
      </section>
    </div>
  );
}