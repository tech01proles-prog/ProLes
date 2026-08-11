import { useState, useEffect, useCallback, useRef } from 'react';
import { createPortal } from 'react-dom';
import api from '../api/client';
import type { TicketDto, ProjectDto, UserDto } from '../types';
import { formatDateTime, formatFileSize, formatMoney, readFileAsBase64 } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';

export function TicketsPage() {
  const [user, setUser] = useState<UserDto | null>(null);
  const { can, loading: permLoading } = usePermissions();
  const [tab, setTab] = useState<'my' | 'all'>('my');
  const [myTickets, setMyTickets] = useState<TicketDto[]>([]);
  const [allTickets, setAllTickets] = useState<TicketDto[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [users, setUsers] = useState<UserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);

  // Форма
  const [form, setForm] = useState({
    projectId: '',
    description: '',
    sendToAccountant: false,
    accountantEmail: '',
    recipientIds: [] as string[],
    amount: '',
    currency: 'RUB',
  });
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [selectedReceiptFile, setSelectedReceiptFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [dragActive, setDragActive] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const receiptInputRef = useRef<HTMLInputElement>(null);

  const isAdmin = !permLoading && can('tickets', 'view');

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) try { setUser(JSON.parse(stored)); } catch {}
  }, []);

  const loadData = useCallback(async () => {
    if (permLoading) return;
    setLoading(true);
    try {
      const [myRes, allRes, projRes, usersRes] = await Promise.allSettled([
        api.get<TicketDto[]>('/tickets/my'),
        isAdmin ? api.get<TicketDto[]>('/tickets/all') : Promise.resolve({ data: [] }),
        api.get<ProjectDto[]>('/projects'),
        api.get<UserDto[]>('/users'),
      ]);
      setMyTickets(myRes.status === 'fulfilled' ? myRes.value.data : []);
      setAllTickets(allRes.status === 'fulfilled' ? allRes.value.data : []);
      setProjects(projRes.status === 'fulfilled' ? projRes.value.data : []);
      setUsers(usersRes.status === 'fulfilled' ? usersRes.value.data : []);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [isAdmin, permLoading]);

  useEffect(() => { loadData(); }, [loadData]);

  const tickets = tab === 'my' ? myTickets : allTickets;

  // 🔧 Исправленное скачивание через fetch + Blob
  const handleDownload = async (ticket: TicketDto) => {
    try {
      // 🎯 Формируем правильный URL к серверу
      const apiBase = (import.meta.env.VITE_API_URL as string) || '/api/v1';
      // Убираем /api/v1 → получаем корень сервера
      const serverRoot = apiBase.replace(/\/api\/v1\/?$/, '');
      const fullUrl = serverRoot
        ? `${serverRoot}${ticket.downloadUrl}`
        : ticket.downloadUrl; // production: тот же хост

      const response = await fetch(fullUrl);
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      // 🎯 Определяем MIME-тип по расширению файла
      const ext = (ticket.fileName.split('.').pop() || '').toLowerCase();
      const mimeMap: Record<string, string> = {
        pdf: 'application/pdf',
        jpg: 'image/jpeg',
        jpeg: 'image/jpeg',
        png: 'image/png',
        gif: 'image/gif',
        doc: 'application/msword',
        docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        xls: 'application/vnd.ms-excel',
        xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      };
      const mimeType = mimeMap[ext] || response.headers.get('Content-Type') || 'application/octet-stream';

      // 🎯 Создаём Blob с ПРАВИЛЬНЫМ типом
      const rawBlob = await response.blob();
      const typedBlob = new Blob([rawBlob], { type: mimeType });

      const url = window.URL.createObjectURL(typedBlob);
      const a = document.createElement('a');
      a.href = url;
      a.download = ticket.fileName;
      a.style.display = 'none';
      document.body.appendChild(a);
      a.click();

      // Очистка
      setTimeout(() => {
        document.body.removeChild(a);
        window.URL.revokeObjectURL(url);
      }, 100);
    } catch (err: any) {
      console.error('Download error:', err);
      alert(`Ошибка скачивания: ${err.message}`);
    }
  };

  const handleFileChange = (file: File | null, isReceipt = false) => {
    if (!file) return;
    if (file.size > 10 * 1024 * 1024) { alert('Файл не должен превышать 10 МБ'); return; }
    if (isReceipt) {
      setSelectedReceiptFile(file);
    } else {
      setSelectedFile(file);
    }
  };

  const handleDrop = (e: React.DragEvent, isReceipt = false) => {
    e.preventDefault();
    setDragActive(false);
    if (e.dataTransfer.files?.[0]) handleFileChange(e.dataTransfer.files[0], isReceipt);
  };

  const handleUpload = async () => {
    if (!user || !selectedFile || !form.projectId) { alert('Заполните все поля и выберите файл билета'); return; }
    setUploading(true);
    try {
      const base64 = await readFileAsBase64(selectedFile);
      let receiptBase64: string | null = null;
      if (selectedReceiptFile) {
        receiptBase64 = await readFileAsBase64(selectedReceiptFile);
      }
      await api.post('/tickets/upload', {
        projectId: form.projectId,
        description: form.description,
        sendToAccountant: form.sendToAccountant,
        accountantEmail: form.accountantEmail,
        recipientIds: form.recipientIds,
        fileBase64: base64,
        fileName: selectedFile.name,
        fileType: selectedFile.type || 'application/octet-stream',
        receiptBase64: receiptBase64,
        receiptFileName: selectedReceiptFile?.name || null,
        receiptFileType: selectedReceiptFile?.type || null,
        amount: form.amount ? parseFloat(form.amount) : 0,
        currency: form.currency,
      });
      setShowForm(false);
      setSelectedFile(null);
      setSelectedReceiptFile(null);
      setForm({ projectId: '', description: '', sendToAccountant: false, accountantEmail: '', recipientIds: [], amount: '', currency: 'RUB' });
      await loadData();
    } catch (err) {
      alert('Ошибка загрузки билета');
    } finally {
      setUploading(false);
    }
  };

  const handleMarkViewed = async (ticketId: string) => {
    await api.post(`/tickets/${ticketId}/view`);
    await loadData();
  };

  const handleDelete = async (ticketId: string) => {
    if (!confirm('Удалить билет?')) return;
    await api.delete(`/tickets/${ticketId}`);
    await loadData();
  };

  const toggleRecipient = (uid: string) => {
    setForm(f => ({
      ...f,
      recipientIds: f.recipientIds.includes(uid)
        ? f.recipientIds.filter(id => id !== uid)
        : [...f.recipientIds, uid],
    }));
  };

  const getFileIcon = (type: string) => {
    if (type.startsWith('image/')) return '🖼';
    if (type.includes('pdf')) return '📄';
    if (type.includes('word')) return '📝';
    if (type.includes('excel') || type.includes('sheet')) return '📊';
    return '📎';
  };

  if (permLoading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;

  return (
    <div className="space-y-6 max-w-6xl mx-auto">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">🎫 Билеты</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            Мои: {myTickets.length}
            {isAdmin && <> • Все: <span className="font-bold text-slate-900 dark:text-slate-100">{allTickets.length}</span></>}
          </p>
        </div>
        <button onClick={() => setShowForm(!showForm)} className="btn-primary px-5 py-2.5 shadow-indigo-200 shadow-md">
          {showForm ? '✕ Закрыть' : '＋ Загрузить билет'}
        </button>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 bg-white dark:bg-slate-900 rounded-xl p-1 border border-slate-200 dark:border-slate-700 w-fit">
        <button onClick={() => setTab('my')} className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${tab === 'my' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'text-slate-600 dark:text-slate-400'}`}>
          📥 Мои ({myTickets.length})
        </button>
        {isAdmin && (
          <button onClick={() => setTab('all')} className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${tab === 'all' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'text-slate-600 dark:text-slate-400'}`}>
            📂 Все ({allTickets.length})
          </button>
        )}
      </div>

      {/* 🌲 PROLES MODAL: Загрузка билета */}
      {showForm && createPortal(
        <div className="proles-modal-backdrop" onClick={() => !uploading && setShowForm(false)}>
          <div className="proles-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '36rem' }}>
            <div className="proles-modal-header">
              <div className="proles-modal-title">
                <div className="proles-modal-icon">🎫</div>
                <div>
                  <div>Загрузка билета</div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                    Проездной документ
                  </div>
                </div>
              </div>
              <button onClick={() => setShowForm(false)} className="proles-modal-close">✕</button>
            </div>
            <div className="proles-modal-body">
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Файл билета *</div>
                <div
                  onDragEnter={(e) => { e.preventDefault(); setDragActive(true); }}
                  onDragOver={(e) => { e.preventDefault(); setDragActive(true); }}
                  onDragLeave={() => setDragActive(false)}
                  onDrop={handleDrop}
                  onClick={() => fileInputRef.current?.click()}
                  style={{
                    border: '2px dashed',
                    borderColor: dragActive ? '#10b981' : '#cbd5e1',
                    borderRadius: '0.75rem',
                    padding: '1.5rem',
                    textAlign: 'center',
                    cursor: 'pointer',
                    transition: 'all 0.2s',
                    background: dragActive ? 'rgba(16, 185, 129, 0.05)' : 'transparent'
                  }}
                >
                  <input ref={fileInputRef} type="file" className="hidden" onChange={(e) => handleFileChange(e.target.files?.[0] || null)} />
                  {selectedFile ? (
                    <div>
                      <div style={{ fontSize: '2rem', marginBottom: '0.5rem' }}>{getFileIcon(selectedFile.type)}</div>
                      <div style={{ fontWeight: 700, color: '#0f172a' }}>{selectedFile.name}</div>
                      <div style={{ fontSize: '0.75rem', color: '#64748b', marginTop: 4 }}>{formatFileSize(selectedFile.size)}</div>
                      <button onClick={(e) => { e.stopPropagation(); setSelectedFile(null); }} style={{ marginTop: 8, fontSize: 12, color: '#ef4444', background: 'none', border: 'none', cursor: 'pointer' }}>✕ Убрать</button>
                    </div>
                  ) : (
                    <div>
                      <div style={{ fontSize: '2rem', marginBottom: '0.5rem' }}>📤</div>
                      <div style={{ fontWeight: 600, color: '#475569' }}>Перетащите файл билета</div>
                      <div style={{ fontSize: '0.75rem', color: '#94a3b8', marginTop: 4 }}>или кликните (до 10 МБ)</div>
                    </div>
                  )}
                </div>
              </div>
              {/* 🆕 Поле для загрузки чека */}
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Чек (прилагается к билету)</div>
                <div
                  onDragEnter={(e) => { e.preventDefault(); }}
                  onDragOver={(e) => { e.preventDefault(); }}
                  onDragLeave={() => { }}
                  onDrop={(e) => handleDrop(e, true)}
                  onClick={() => receiptInputRef.current?.click()}
                  style={{
                    border: '2px dashed #cbd5e1',
                    borderRadius: '0.75rem',
                    padding: '1.5rem',
                    textAlign: 'center',
                    cursor: 'pointer',
                    transition: 'all 0.2s',
                    background: 'transparent'
                  }}
                >
                  <input ref={receiptInputRef} type="file" className="hidden" onChange={(e) => handleFileChange(e.target.files?.[0] || null, true)} />
                  {selectedReceiptFile ? (
                    <div>
                      <div style={{ fontSize: '2rem', marginBottom: '0.5rem' }}>{getFileIcon(selectedReceiptFile.type)}</div>
                      <div style={{ fontWeight: 700, color: '#0f172a' }}>{selectedReceiptFile.name}</div>
                      <div style={{ fontSize: '0.75rem', color: '#64748b', marginTop: 4 }}>{formatFileSize(selectedReceiptFile.size)}</div>
                      <button onClick={(e) => { e.stopPropagation(); setSelectedReceiptFile(null); }} style={{ marginTop: 8, fontSize: 12, color: '#ef4444', background: 'none', border: 'none', cursor: 'pointer' }}>✕ Убрать</button>
                    </div>
                  ) : (
                    <div>
                      <div style={{ fontSize: '2rem', marginBottom: '0.5rem' }}>🧾</div>
                      <div style={{ fontWeight: 600, color: '#475569' }}>Перетащите файл чека</div>
                      <div style={{ fontSize: '0.75rem', color: '#94a3b8', marginTop: 4 }}>или кликните (до 10 МБ)</div>
                    </div>
                  )}
                </div>
              </div>
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Детали</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group">
                    <label>Проект *</label>
                    <select value={form.projectId} onChange={(e) => setForm({ ...form, projectId: e.target.value })} className="input bg-white dark:bg-slate-900">
                      <option value="">Выберите проект</option>
                      {projects.filter(p => p.isActive).map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
                    </select>
                  </div>
                  <div className="proles-input-group">
                    <label>Описание</label>
                    <input type="text" placeholder="Москва-Питер 15.07" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} className="input" />
                  </div>
                  <div className="proles-input-group">
                    <label>Сумма (опц.)</label>
                    <input type="number" placeholder="0" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} className="input" />
                  </div>
                  <div className="proles-input-group">
                    <label>Валюта</label>
                    <select value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })} className="input bg-white dark:bg-slate-900">
                      <option>RUB</option><option>USD</option><option>EUR</option>
                    </select>
                  </div>
                </div>
              </div>
              {/* Получатели */}
              {users.length > 0 && (
                <div className="proles-modal-section">
                  <div className="proles-modal-section-title">Получатели ({form.recipientIds.length})</div>
                  <div className="grid grid-cols-2 md:grid-cols-3 gap-2 max-h-40 overflow-y-auto p-2 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg">
                    {users.map(u => (
                      <label key={u.id} className={`flex items-center gap-2 p-2 rounded cursor-pointer text-xs transition-all ${
                        form.recipientIds.includes(u.id)
                          ? 'bg-emerald-50 dark:bg-emerald-950/40 text-emerald-700 dark:text-emerald-400 ring-1 ring-emerald-300'
                          : 'hover:bg-white dark:hover:bg-slate-700'
                      }`}>
                        <input type="checkbox" checked={form.recipientIds.includes(u.id)} onChange={() => toggleRecipient(u.id)} className="rounded accent-emerald-600" />
                        <span className="truncate">{u.name}{u.id === user?.id ? ' (вы)' : ''}</span>
                      </label>
                    ))}
                  </div>
                </div>
              )}
              {/* Бухгалтер */}
              <div style={{
                padding: '1rem',
                background: 'linear-gradient(135deg, rgba(251, 191, 36, 0.08) 0%, rgba(245, 158, 11, 0.08) 100%)',
                border: '1px solid rgba(251, 191, 36, 0.2)',
                borderRadius: '0.75rem'
              }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
                  <input type="checkbox" checked={form.sendToAccountant} onChange={(e) => setForm({ ...form, sendToAccountant: e.target.checked })} className="rounded" />
                  <span style={{ fontSize: '0.875rem', fontWeight: 600, color: '#92400e' }}>📧 Отправить бухгалтеру</span>
                </label>
                {form.sendToAccountant && (
                  <input type="email" placeholder="email@accountant.com" value={form.accountantEmail} onChange={(e) => setForm({ ...form, accountantEmail: e.target.value })} className="input mt-3" />
                )}
              </div>
            </div>
            <div className="proles-modal-footer">
              <button onClick={() => setShowForm(false)} className="proles-btn-cancel">Отмена</button>
              <button onClick={handleUpload} disabled={uploading || !selectedFile || !form.projectId} className="proles-btn-save">
                {uploading ? '⏳ Загрузка...' : '📤 Загрузить'}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* Список билетов */}
      {loading ? (
        <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>
      ) : tickets.length === 0 ? (
        <div className="card p-12 text-center border-dashed border-2 border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-900/50">
          <div className="text-5xl mb-4 opacity-50">🎫</div>
          <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Нет билетов</h3>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          {tickets.map(t => {
            const isUnread = tab === 'my' && !t.viewedAt;
            return (
              <div key={t.id} className={`card p-5 group hover:shadow-md transition-all animate-fade-in ${isUnread ? 'border-l-4 border-l-indigo-500 bg-indigo-50/30 dark:bg-indigo-950/20' : ''}`}>
                <div className="flex items-start gap-3 mb-3">
                  <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-indigo-100 to-purple-100 dark:from-indigo-900/40 dark:to-purple-900/40 flex items-center justify-center text-2xl flex-shrink-0">
                    {getFileIcon(t.fileType)}
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="font-bold text-slate-900 dark:text-slate-100 truncate">{t.fileName}</div>
                    <div className="text-xs text-slate-500 dark:text-slate-400 truncate">{t.projectName}</div>
                    {isUnread && <span className="text-[10px] text-indigo-700 dark:text-indigo-400 font-bold">● Непрочитано</span>}
                  </div>
                </div>

                {t.description && <div className="text-sm text-slate-600 dark:text-slate-400 mb-2">{t.description}</div>}

                <div className="flex items-center gap-3 text-xs text-slate-400 mb-3">
                  <span>{formatFileSize(t.fileSize)}</span>
                  <span>•</span>
                  <span>{formatDateTime(t.uploadedAt)}</span>
                  {t.amount > 0 && (<><span>•</span><span className="font-bold text-emerald-700 dark:text-emerald-400">{formatMoney(t.amount, t.currency)}</span></>)}
                </div>

                {t.recipients.length > 0 && (
                  <div className="text-xs text-slate-500 dark:text-slate-400 mb-2">
                    👥 {t.recipients.map(r => r.userName).join(', ')}
                  </div>
                )}

                <div className="flex items-center gap-2 pt-3 border-t border-slate-100 dark:border-slate-800">
                  <button onClick={() => handleDownload(t)} className="btn-outline px-3 py-1.5 text-xs flex-1">
                    📥 Скачать
                  </button>
                  {isUnread && (
                    <button onClick={() => handleMarkViewed(t.id)} className="btn-ghost px-3 py-1.5 text-xs text-indigo-600 dark:text-indigo-400">
                      ✓ Прочитано
                    </button>
                  )}
                  {isAdmin && (
                    <button onClick={() => handleDelete(t.id)} className="btn-ghost px-3 py-1.5 text-xs text-red-500">🗑</button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}