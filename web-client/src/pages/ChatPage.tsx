import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import api from '../api/client';
import type { ChatConversationDto, ChatMessageDto, ChatMessagesPageDto, ChatUserDto, UserDto } from '../types';

type ChatRealtimeEvent = {
  type: 'CONNECTED' | 'MESSAGE_CREATED' | 'CONVERSATION_READ';
  conversationId?: string;
  message?: ChatMessageDto;
  userId?: string;
  messageId?: string;
  unreadCount?: number;
  occurredAt: number;
};

const buildChatWebSocketUrl = (token: string) => {
  const configuredApi = import.meta.env.VITE_API_URL as string | undefined;
  const apiUrl = new URL(
    configuredApi || '/api/v1',
    window.location.origin
  );

  const protocol = apiUrl.protocol === 'https:' ? 'wss:' : 'ws:';
  const apiPath = apiUrl.pathname.replace(/\/+$/, '');

  return `${protocol}//${apiUrl.host}${apiPath}/chat/ws?token=${encodeURIComponent(token)}`;
};

const formatSize = (bytes: number) => bytes < 1024 * 1024 ? `${Math.ceil(bytes / 1024)} КБ` : `${(bytes / 1024 / 1024).toFixed(1)} МБ`;

export function ChatPage() {
  const currentUser = useMemo<UserDto | null>(() => { try { return JSON.parse(localStorage.getItem('proles_user') || 'null'); } catch { return null; } }, []);
  const [conversations, setConversations] = useState<ChatConversationDto[]>([]);
  const [users, setUsers] = useState<ChatUserDto[]>([]);
  const [activeId, setActiveId] = useState('');
  const [messages, setMessages] = useState<ChatMessageDto[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [text, setText] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const activeIdRef = useRef('');

  const loadSidebar = useCallback(async () => {
    const [conversationResponse, userResponse] = await Promise.all([api.get<ChatConversationDto[]>('/chat/conversations'), api.get<ChatUserDto[]>('/chat/users')]);
    setConversations(conversationResponse.data);
    setUsers(userResponse.data);
    setActiveId((current) => current || conversationResponse.data[0]?.id || '');
  }, []);

  const loadMessages = useCallback(async (conversationId: string, nextCursor?: string | null) => {
    const { data } = await api.get<ChatMessagesPageDto>(`/chat/conversations/${conversationId}/messages`, { params: { limit: 50, cursor: nextCursor || undefined } });
    setMessages((current) => nextCursor ? [...current, ...data.items] : data.items);
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

              return [incoming, ...current];
            });

            void api.post(
              `/chat/conversations/${realtimeEvent.conversationId}/read`,
              { messageId: incoming.id }
            );
          }

          void loadSidebar();
          return;
        }

        if (realtimeEvent.type === 'CONVERSATION_READ') {
          void loadSidebar();
        }
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

      socket?.close(1000, 'Component unmounted');
    };
  }, [loadSidebar]);

  const startConversation = async (userId: string) => {
    const { data } = await api.post<ChatConversationDto>('/chat/conversations/direct', { userId });
    await loadSidebar();
    setActiveId(data.id);
  };

  const send = async () => {
    if (!activeId || (!text.trim() && !file) || sending) return;
    setSending(true);
    const clientMessageId = `web-${Date.now()}-${crypto.randomUUID()}`;
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
      await loadSidebar();
    } finally {
      setSending(false);
    }
  };

  const download = async (message: ChatMessageDto, attachmentId: string, fileName: string) => {
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

  if (loading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">◌</div></div>;

  return (
    <div className="grid h-[calc(100vh-9rem)] min-h-[580px] grid-cols-1 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-700 dark:bg-slate-900 md:grid-cols-[320px_1fr]">
      <aside className="flex min-h-0 flex-col border-b border-slate-200 dark:border-slate-700 md:border-b-0 md:border-r">
        <div className="border-b border-slate-200 p-4 dark:border-slate-700">
          <h1 className="text-xl font-bold">PRO-Chat</h1>
          <select defaultValue="" onChange={(event) => { if (event.target.value) void startConversation(event.target.value); event.target.value = ''; }} className="input mt-3 w-full bg-white dark:bg-slate-900">
            <option value="">Новый диалог...</option>
            {users.map((user) => <option key={user.id} value={user.id}>{user.name}{user.position ? ` — ${user.position}` : ''}</option>)}
          </select>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {conversations.map((conversation) => (
            <button key={conversation.id} type="button" onClick={() => setActiveId(conversation.id)} className={`w-full border-b border-slate-100 p-4 text-left dark:border-slate-800 ${activeId === conversation.id ? 'bg-indigo-50 dark:bg-indigo-950/30' : 'hover:bg-slate-50 dark:hover:bg-slate-800'}`}>
              <div className="flex items-center justify-between gap-2"><span className="truncate font-semibold">{conversation.title}</span>{conversation.unreadCount > 0 && <span className="rounded-full bg-indigo-600 px-2 py-0.5 text-xs text-white">{conversation.unreadCount}</span>}</div>
              <div className="mt-1 truncate text-xs text-slate-500">{conversation.lastMessage?.text || 'Нет сообщений'}</div>
            </button>
          ))}
          {conversations.length === 0 && <div className="p-6 text-center text-sm text-slate-500">Выберите сотрудника для начала диалога</div>}
        </div>
      </aside>

      <section className="flex min-h-0 flex-col">
        {!active ? <div className="flex flex-1 items-center justify-center text-slate-500">Диалог не выбран</div> : (
          <>
            <header className="border-b border-slate-200 px-5 py-4 font-bold dark:border-slate-700">{active.title}</header>
            <div className="flex min-h-0 flex-1 flex-col-reverse overflow-y-auto p-4">
              <div className="space-y-3">
                {messages.map((message) => {
                  const mine = message.senderId === currentUser?.id;
                  return (
                    <div key={message.id} className={`flex ${mine ? 'justify-end' : 'justify-start'}`}>
                      <div className={`max-w-[82%] rounded-2xl px-4 py-3 ${mine ? 'bg-indigo-600 text-white' : 'bg-slate-100 text-slate-900 dark:bg-slate-800 dark:text-slate-100'}`}>
                        {!mine && <div className="mb-1 text-xs font-semibold opacity-70">{message.senderName}</div>}
                        {message.text && <div className="whitespace-pre-wrap break-words">{message.text}</div>}
                        {message.attachments.map((attachment) => <button key={attachment.id} type="button" onClick={() => void download(message, attachment.id, attachment.originalName)} className="mt-2 block w-full rounded-xl border border-current/20 px-3 py-2 text-left text-sm"><span className="block truncate font-semibold">{attachment.originalName}</span><span className="text-xs opacity-70">{formatSize(attachment.sizeBytes)}</span></button>)}
                        <div className="mt-1 text-right text-[10px] opacity-60">{new Date(message.createdAt).toLocaleString('ru-RU')}</div>
                      </div>
                    </div>
                  );
                })}
                {cursor && <button type="button" onClick={() => void loadMessages(activeId, cursor)} className="mx-auto block text-sm font-semibold text-indigo-600">Загрузить предыдущие</button>}
              </div>
            </div>
            <footer className="border-t border-slate-200 p-3 dark:border-slate-700">
              {file && <div className="mb-2 flex items-center justify-between rounded-lg bg-slate-100 px-3 py-2 text-sm dark:bg-slate-800"><span className="truncate">{file.name} · {formatSize(file.size)}</span><button type="button" onClick={() => setFile(null)} className="ml-3 font-bold">×</button></div>}
              <div className="flex items-end gap-2">
                <input ref={fileInput} type="file" className="hidden" onChange={(event) => { const next = event.target.files?.[0] || null; if (next && next.size > 100 * 1024 * 1024) { alert('Максимальный размер файла — 100 MiB'); event.target.value = ''; return; } setFile(next); }} />
                <button type="button" onClick={() => fileInput.current?.click()} className="btn-outline px-3 py-2" title="Прикрепить файл">＋</button>
                <textarea value={text} onChange={(event) => setText(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void send(); } }} rows={1} maxLength={20000} className="input min-h-[42px] flex-1 resize-none" placeholder="Сообщение..." />
                <button type="button" onClick={() => void send()} disabled={sending || (!text.trim() && !file)} className="rounded-xl bg-indigo-600 px-4 py-2 font-semibold text-white disabled:opacity-50">{sending ? '...' : 'Отправить'}</button>
              </div>
            </footer>
          </>
        )}
      </section>
    </div>
  );
}