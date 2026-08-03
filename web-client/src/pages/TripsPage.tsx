import { useState, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import api from '../api/client';
import type { BusinessTripDto, ProjectDto, UserDto } from '../types';
import { generateUUID, formatDate } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';

const TRIP_TYPES = {
  DEPARTURE: { label: '🚆 Отъезд', color: 'bg-blue-100 text-blue-700 border-blue-200 dark:bg-blue-950/40 dark:text-blue-400 dark:border-blue-900' },
  TRANSFER: { label: '🔄 Переезд', color: 'bg-orange-100 text-orange-700 border-orange-200 dark:bg-orange-950/40 dark:text-orange-400 dark:border-orange-900' },
  COMPLETION: { label: '✅ Завершение', color: 'bg-green-100 text-green-700 border-green-200 dark:bg-green-950/40 dark:text-green-400 dark:border-green-900' },
};

export function TripsPage() {
  const [user, setUser] = useState<UserDto | null>(null);
  const { can, loading: permLoading } = usePermissions();

  const [trips, setTrips] = useState<BusinessTripDto[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [allUsers, setAllUsers] = useState<UserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);

  // Сортировка
  const [sortField, setSortField] = useState<'date' | 'type'>('date');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');

  // Фильтры (только админ)
  const [filterUser, setFilterUser] = useState('all');
  const [filterProject, setFilterProject] = useState('all');

  // Форма
  const [form, setForm] = useState({
    projectId: '',
    type: 'DEPARTURE' as keyof typeof TRIP_TYPES,
    date: new Date().toISOString().slice(0, 10),
    city: '',
    participants: [] as string[],
    transport: '',
    notes: '',
  });
  const [saving, setSaving] = useState(false);

  const isAdmin = !permLoading && can('business_trips_all', 'view');

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) try { setUser(JSON.parse(stored)); } catch {}
  }, []);

  const loadData = useCallback(async () => {
    if (!user || permLoading) return;
    setLoading(true);
    try {
      // 🔐 Выбор эндпоинта по правам
      const tripsUrl = isAdmin
        ? '/business-trips?all=true'
        : `/business-trips?userId=${user.id}`;

      const [tripsRes, projRes, usersRes] = await Promise.allSettled([
        api.get<BusinessTripDto[]>(tripsUrl),
        api.get<ProjectDto[]>('/projects'),
        isAdmin ? api.get<UserDto[]>('/users') : Promise.resolve({ data: [] }),
      ]);

      setTrips(tripsRes.status === 'fulfilled' ? tripsRes.value.data : []);
      setProjects(projRes.status === 'fulfilled' ? projRes.value.data.filter(p => p.isActive) : []);
      setAllUsers(usersRes.status === 'fulfilled' ? usersRes.value.data : []);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [user, isAdmin, permLoading]);

  useEffect(() => { loadData(); }, [loadData]);

  // Фильтрация + сортировка
  const filtered = trips
    .filter(t => !isAdmin || filterUser === 'all' || t.userId === filterUser)
    .filter(t => !isAdmin || filterProject === 'all' || t.projectId === filterProject)
    .sort((a, b) => {
      const dir = sortDir === 'asc' ? 1 : -1;
      if (sortField === 'date') return a.date.localeCompare(b.date) * dir;
      return a.type.localeCompare(b.type) * dir;
    });

  const handleCreate = async () => {
    if (!user || !form.projectId) return;
    setSaving(true);
    try {
      await api.post('/business-trips', {
        id: generateUUID(),
        userId: user.id,
        projectId: form.projectId,
        projectName: projects.find(p => p.id === form.projectId)?.name || '',
        type: form.type,
        date: form.date,
        city: form.city,
        participants: form.participants,
        transport: form.transport,
        notes: form.notes,
        createdAt: Date.now(),
      });
      setShowForm(false);
      setForm({ ...form, projectId: '', city: '', participants: [], transport: '', notes: '' });
      await loadData();
    } catch (err) {
      alert('Ошибка создания командировки');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Удалить командировку?')) return;
    await api.delete('/business-trips', { params: { tripId: id } });
    await loadData();
  };

  const toggleParticipant = (uid: string) => {
    setForm(f => ({
      ...f,
      participants: f.participants.includes(uid)
        ? f.participants.filter(id => id !== uid)
        : [...f.participants, uid],
    }));
  };

  const toggleSort = (field: 'date' | 'type') => {
    if (sortField === field) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortField(field); setSortDir('desc'); }
  };

  if (permLoading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;

  return (
    <div className="space-y-6 max-w-5xl mx-auto">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">✈️ Командировки</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            {filtered.length} поездок
            {isAdmin && <span className="ml-2 badge-indigo">Админ-режим</span>}
          </p>
        </div>
        <button
          onClick={() => setShowForm(!showForm)}
          className={`px-5 py-2.5 rounded-xl font-semibold text-sm transition-all shadow-md ${
            showForm
              ? 'bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300 border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700'
              : 'bg-gradient-to-r from-violet-500 to-purple-600 hover:from-violet-600 hover:to-purple-700 text-white shadow-violet-200 dark:shadow-violet-950/50 hover:shadow-lg hover:-translate-y-0.5'
          }`}
        >
          {showForm ? '✕ Закрыть' : '✈️ Новая командировка'}
        </button>
      </div>

      {/* 🌲 PROLES MODAL: Новая командировка */}
      {showForm && createPortal(
        <div className="proles-modal-backdrop" onClick={() => !saving && setShowForm(false)}>
          <div className="proles-modal" onClick={(e) => e.stopPropagation()}>
            <div className="proles-modal-header">
              <div className="proles-modal-title">
                <div className="proles-modal-icon">✈️</div>
                <div>
                  <div>Новая командировка</div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                    Оформление поездки сотрудника
                  </div>
                </div>
              </div>
              <button onClick={() => setShowForm(false)} className="proles-modal-close">✕</button>
            </div>
            <div className="proles-modal-body">
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Основная информация</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group" style={{ gridColumn: 'span 2' }}>
                    <label>Проект *</label>
                    <select value={form.projectId} onChange={(e) => setForm({ ...form, projectId: e.target.value })} className="input bg-white dark:bg-slate-900">
                      <option value="">Выберите проект</option>
                      {projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
                    </select>
                  </div>
                  <div className="proles-input-group">
                    <label>Тип поездки</label>
                    <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as any })} className="input bg-white dark:bg-slate-900">
                      {Object.entries(TRIP_TYPES).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
                    </select>
                  </div>
                  <div className="proles-input-group">
                    <label>Дата</label>
                    <input type="date" value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} className="input" />
                  </div>
                </div>
              </div>
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Детали поездки</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group">
                    <label>Город</label>
                    <input type="text" placeholder="Например: Москва" value={form.city} onChange={(e) => setForm({ ...form, city: e.target.value })} className="input" />
                  </div>
                  <div className="proles-input-group">
                    <label>Транспорт</label>
                    <input type="text" placeholder="Поезд №123" value={form.transport} onChange={(e) => setForm({ ...form, transport: e.target.value })} className="input" />
                  </div>
                </div>
              </div>
              {/* Участники (только для админа) */}
              {isAdmin && allUsers.length > 0 && (
                <div className="proles-modal-section">
                  <div className="proles-modal-section-title">Участники ({form.participants.length})</div>
                  <div className="grid grid-cols-2 md:grid-cols-3 gap-2 max-h-40 overflow-y-auto p-2 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg">
                    {allUsers.filter(u => u.id !== user?.id).map(u => (
                      <label key={u.id} className={`flex items-center gap-2 p-1.5 rounded cursor-pointer text-xs transition-all ${
                        form.participants.includes(u.id)
                          ? 'bg-emerald-50 dark:bg-emerald-950/40 text-emerald-700 dark:text-emerald-400 ring-1 ring-emerald-300'
                          : 'hover:bg-white dark:hover:bg-slate-700'
                      }`}>
                        <input type="checkbox" checked={form.participants.includes(u.id)} onChange={() => toggleParticipant(u.id)} className="rounded accent-emerald-600" />
                        <span className="truncate">{u.name}</span>
                      </label>
                    ))}
                  </div>
                </div>
              )}
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Дополнительно</div>
                <div className="proles-input-group">
                  <label>Заметки (опционально)</label>
                  <textarea placeholder="Дополнительная информация о поездке..." value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} className="input min-h-[80px]" />
                </div>
              </div>
            </div>
            <div className="proles-modal-footer">
              <button onClick={() => setShowForm(false)} className="proles-btn-cancel">Отмена</button>
              <button onClick={handleCreate} disabled={saving || !form.projectId} className="proles-btn-save">
                {saving ? '⏳ Сохранение...' : '💾 Создать'}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* 🔐 Фильтры (только админ) */}
      {isAdmin && (
        <div className="card p-4">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <select value={filterUser} onChange={(e) => setFilterUser(e.target.value)} className="input bg-white dark:bg-slate-900">
              <option value="all">Все сотрудники</option>
              {allUsers.map(u => <option key={u.id} value={u.id}>{u.name}</option>)}
            </select>
            <select value={filterProject} onChange={(e) => setFilterProject(e.target.value)} className="input bg-white dark:bg-slate-900">
              <option value="all">Все проекты</option>
              {projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </div>
        </div>
      )}

      {/* Сортировка (для всех) */}
      <div className="flex gap-2">
        <button onClick={() => toggleSort('date')} className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${sortField === 'date' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}>
          📅 Дата {sortField === 'date' ? (sortDir === 'desc' ? '↓' : '↑') : ''}
        </button>
        <button onClick={() => toggleSort('type')} className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${sortField === 'type' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}>
          🏷 Тип {sortField === 'type' ? (sortDir === 'desc' ? '↓' : '↑') : ''}
        </button>
      </div>

      {/* Список */}
      {loading ? (
        <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>
      ) : filtered.length === 0 ? (
        <div className="card p-12 text-center border-dashed border-2 border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-900/50">
          <div className="text-5xl mb-4 opacity-50">✈️</div>
          <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Нет командировок</h3>
        </div>
      ) : (
        <div className="space-y-3">
          {filtered.map(trip => {
            const cfg = TRIP_TYPES[trip.type as keyof typeof TRIP_TYPES] || TRIP_TYPES.DEPARTURE;
            const userName = isAdmin ? allUsers.find(u => u.id === trip.userId)?.name : null;
            const participants = trip.participants.map(pid => allUsers.find(u => u.id === pid)?.name).filter(Boolean);
            return (
              <div key={trip.id} className="card p-5 group hover:shadow-md transition-all animate-fade-in">
                <div className="flex items-start justify-between gap-4 mb-2">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 flex-wrap mb-1">
                      <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold border ${cfg.color}`}>{cfg.label}</span>
                      <span className="text-xs text-slate-400">{formatDate(trip.date)}</span>
                      {userName && <span className="text-xs text-slate-500 dark:text-slate-400">👤 {userName}</span>}
                    </div>
                    <div className="font-bold text-slate-900 dark:text-slate-100 truncate">{trip.projectName}</div>
                    {trip.city && <div className="text-sm text-slate-600 dark:text-slate-400">📍 {trip.city}</div>}
                  </div>
                </div>
                {(trip.transport || participants.length > 0 || trip.notes) && (
                  <div className="space-y-1 text-xs text-slate-500 dark:text-slate-400 pt-2 border-t border-slate-100 dark:border-slate-800">
                    {trip.transport && <div>🚗 {trip.transport}</div>}
                    {participants.length > 0 && <div>👥 {participants.join(', ')}</div>}
                    {trip.notes && <div className="italic">📝 {trip.notes}</div>}
                  </div>
                )}
                <div className="flex justify-end pt-2 mt-2 border-t border-slate-100 dark:border-slate-800">
                  <button onClick={() => handleDelete(trip.id)} className="text-xs font-medium text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30 px-2 py-1 rounded-lg opacity-0 group-hover:opacity-100 transition-all">🗑 Удалить</button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}