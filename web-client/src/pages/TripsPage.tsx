import { useState, useEffect, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { createPortal } from 'react-dom';
import api from '../api/client';
import type { BusinessTripDto, ProjectDto, UserDto, WaypointDto } from '../types';
import { generateUUID, formatDate } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';

const TRIP_TYPES = {
  DEPARTURE: { label: '🚆 Отъезд', color: 'bg-blue-100 text-blue-700 border-blue-200 dark:bg-blue-950/40 dark:text-blue-400 dark:border-blue-900' },
  TRANSFER: { label: '🔄 Переезд', color: 'bg-orange-100 text-orange-700 border-orange-200 dark:bg-orange-950/40 dark:text-orange-400 dark:border-orange-900' },
  COMPLETION: { label: '✅ Завершение', color: 'bg-green-100 text-green-700 border-green-200 dark:bg-green-950/40 dark:text-green-400 dark:border-green-900' },
};

export function TripsPage() {
  const [searchParams] = useSearchParams();
  const [user, setUser] = useState<UserDto | null>(null);
  const { can, loading: permLoading } = usePermissions();

  const isAdmin = !permLoading && can('business_trips_all', 'view');

  const [trips, setTrips] = useState<BusinessTripDto[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [allUsers, setAllUsers] = useState<UserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);

  // Сортировка
  const [sortField, setSortField] = useState<'date' | 'type'>('date');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');

  // Автоматически устанавливаем filterUser если в URL есть userId или scope=all
  const urlUserId = searchParams.get('userId');
  
  const [filterUser, setFilterUser] = useState(urlUserId || 'all');
  const [filterProject, setFilterProject] = useState('all');

  useEffect(() => {
    const userIdFromUrl = searchParams.get('userId');
    const scopeFromUrl = searchParams.get('scope');
    // Если передан userId и есть права админа, или явно указан scope=all
    if ((userIdFromUrl || scopeFromUrl === 'all') && isAdmin) {
      setFilterUser(userIdFromUrl || 'all');
    }
  }, [searchParams, isAdmin]);

  // Форма
  const [form, setForm] = useState({
    projectId: '',
    type: 'DEPARTURE' as keyof typeof TRIP_TYPES,
    date: new Date().toISOString().slice(0, 10),
    city: '',
    waypoints: [] as WaypointDto[],
    participants: [] as string[],
    transport: '',
    notes: '',
  });
  const [saving, setSaving] = useState(false);

  // 🆕 Состояние для модального окна просмотра командировки
  const [selectedTrip, setSelectedTrip] = useState<BusinessTripDto | null>(null);
  const [showTripDetail, setShowTripDetail] = useState(false);
  const [editingPerDiemRate, setEditingPerDiemRate] = useState<number>(750);
  const [savingPerDiem, setSavingPerDiem] = useState(false);
  
  // 🆕 Состояние для модального окна перерасчета суточных
  const [showPerDiemRecalc, setShowPerDiemRecalc] = useState(false);
  const [recalcParams, setRecalcParams] = useState({
    dateFrom: new Date().toISOString().slice(0, 10),
    dateTo: new Date().toISOString().slice(0, 10),
    rate: 750,
  });
  const [recalculating, setRecalculating] = useState(false);

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
        waypoints: form.waypoints,
        participants: form.participants,
        transport: form.transport,
        notes: form.notes,
        createdAt: Date.now(),
      });
      setShowForm(false);
      setForm({ ...form, projectId: '', city: '', waypoints: [], participants: [], transport: '', notes: '' });
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

  // 🆕 Открыть модальное окно просмотра командировки
  const handleOpenTripDetail = (trip: BusinessTripDto) => {
    setSelectedTrip(trip);
    setEditingPerDiemRate(trip.perDiemRate || 750);
    setShowTripDetail(true);
  };

  // 🆕 Сохранить измененный размер суточных
  const handleSavePerDiemRate = async () => {
    if (!selectedTrip) return;
    setSavingPerDiem(true);
    try {
      // Отправляем полную запись командировки с обновленным perDiemRate
      const updatedTrip: BusinessTripDto = {
        ...selectedTrip,
        perDiemRate: editingPerDiemRate,
      };
      await api.put('/business-trips', updatedTrip);
      await loadData();
      setShowTripDetail(false);
      setSelectedTrip(null);
    } catch (err) {
      alert('Ошибка сохранения размера суточных');
      console.error(err);
    } finally {
      setSavingPerDiem(false);
    }
  };

  // 🆕 Открыть модальное окно перерасчета суточных
  const handleOpenPerDiemRecalc = (trip: BusinessTripDto) => {
    setSelectedTrip(trip);
    setRecalcParams({
      dateFrom: trip.date,
      dateTo: new Date().toISOString().slice(0, 10),
      rate: trip.perDiemRate || 750,
    });
    setShowPerDiemRecalc(true);
  };

  // 🆕 Выполнить перерасчет суточных
  const handlePerDiemRecalc = async () => {
    if (!selectedTrip || !user) return;
    
    if (recalcParams.dateFrom > recalcParams.dateTo) {
      alert('Дата начала должна быть раньше даты окончания');
      return;
    }
    
    setRecalculating(true);
    try {
      // Получаем все расходы пользователя за указанный период
      const expensesRes = await api.get('/expenses', {
        params: {
          userId: selectedTrip.userId,
          dateFrom: recalcParams.dateFrom,
          dateTo: recalcParams.dateTo,
        }
      });
      
      const existingExpenses = expensesRes.data || [];
      
      // Создаем карту существующих суточных по датам
      const existingPerDiemDates = new Set<string>();
      existingExpenses.forEach((exp: any) => {
        if (exp.type === 'per_diem' || exp.type === 'perdiem') {
          existingPerDiemDates.add(exp.date);
        }
      });
      
      // Генерируем список дат в интервале
      const datesInRange: string[] = [];
      const currentDate = new Date(recalcParams.dateFrom);
      const endDate = new Date(recalcParams.dateTo);
      
      while (currentDate <= endDate) {
        datesInRange.push(currentDate.toISOString().slice(0, 10));
        currentDate.setDate(currentDate.getDate() + 1);
      }
      
      // Удаляем существующие суточные в этом интервале
      const deletePromises = existingExpenses
        .filter((exp: any) => exp.type === 'per_diem' || exp.type === 'perdiem')
        .map((exp: any) => api.delete('/expenses', { params: { expenseId: exp.id } }));
      
      await Promise.all(deletePromises);
      
      // Создаем новые суточные для каждого дня в интервале
      const newExpenses = datesInRange.map(date => ({
        userId: selectedTrip.userId,
        projectId: selectedTrip.projectId,
        projectName: selectedTrip.projectName || '',
        date: date,
        type: 'per_diem',
        name: 'Суточные',
        amount: recalcParams.rate,
        currency: 'RUB',
        comment: `Суточные (${recalcParams.rate} ₽)`,
        receiptSubmitted: true,
      }));
      
      // Отправляем новые суточные пачкой
      for (const expense of newExpenses) {
        try {
          await api.post('/expenses', expense);
        } catch (err: any) {
          console.error(`Ошибка создания суточных на ${expense.date}:`, err.response?.data || err.message);
        }
      }
      
      alert(`✅ Перерасчет выполнен!\n\nПериод: ${recalcParams.dateFrom} — ${recalcParams.dateTo}\nСтавка: ${recalcParams.rate} ₽\nДобавлено дней: ${datesInRange.length}`);
      
      setShowPerDiemRecalc(false);
      await loadData();
    } catch (err) {
      alert('Ошибка при перерасчете суточных');
      console.error(err);
    } finally {
      setRecalculating(false);
    }
  };

  // 🆕 Вычислить количество дней в командировке
  const calculateTripDays = (trip: BusinessTripDto): number => {
    const now = new Date();
    const tripDate = new Date(trip.date);
    
    // Если командировка завершена (COMPLETION), считаем дни от даты начала до даты завершения
    if (trip.type === 'COMPLETION') {
      const diffTime = Math.abs(tripDate.getTime() - new Date(trip.createdAt).getTime());
      const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
      return Math.max(1, diffDays);
    }
    
    // Для активных командировок (DEPARTURE, TRANSFER) считаем дни от даты начала до сегодня
    const diffTime = Math.abs(now.getTime() - tripDate.getTime());
    const diffDays = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
    return Math.max(1, diffDays);
  };

  const toggleParticipant = (uid: string) => {
    setForm(f => ({
      ...f,
      participants: f.participants.includes(uid)
        ? f.participants.filter(id => id !== uid)
        : [...f.participants, uid],
    }));
  };

  const addWaypoint = () => {
    const newOrder = form.waypoints.length > 0 
      ? Math.max(...form.waypoints.map(w => w.order)) + 1 
      : 0;
    setForm(f => ({
      ...f,
      waypoints: [...f.waypoints, { order: newOrder, city: '', address: '' }],
    }));
  };

  const updateWaypoint = (index: number, field: 'city' | 'address', value: string) => {
    setForm(f => ({
      ...f,
      waypoints: f.waypoints.map((w, i) => 
        i === index ? { ...w, [field]: value } : w
      ),
    }));
  };

  const removeWaypoint = (index: number) => {
    setForm(f => ({
      ...f,
      waypoints: f.waypoints.filter((_, i) => i !== index),
    }));
  };

  const moveWaypoint = (index: number, direction: 'up' | 'down') => {
    if ((direction === 'up' && index === 0) || 
        (direction === 'down' && index === form.waypoints.length - 1)) {
      return;
    }
    setForm(f => {
      const newWaypoints = [...f.waypoints];
      const targetIndex = direction === 'up' ? index - 1 : index + 1;
      [newWaypoints[index], newWaypoints[targetIndex]] = [newWaypoints[targetIndex], newWaypoints[index]];
      return { ...f, waypoints: newWaypoints };
    });
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
              {/* 🛣 Пункты следования (waypoints) */}
              <div className="proles-modal-section">
                <div className="proles-modal-section-title flex items-center justify-between">
                  <span>🛣 Пункты следования ({form.waypoints.length})</span>
                  <button
                    onClick={addWaypoint}
                    className="text-xs font-semibold px-2 py-1 rounded-lg bg-emerald-500 hover:bg-emerald-600 text-white transition-all"
                  >
                    ➕ Добавить
                  </button>
                </div>
                {form.waypoints.length === 0 ? (
                  <div className="text-sm text-slate-400 dark:text-slate-500 italic p-3 bg-slate-50 dark:bg-slate-800 rounded-lg border border-dashed border-slate-200 dark:border-slate-700">
                    Нет промежуточных пунктов. Нажмите «➕ Добавить», чтобы добавить пункты следования.
                  </div>
                ) : (
                  <div className="space-y-2 mt-2">
                    {form.waypoints.map((wp, index) => (
                      <div key={index} className="flex items-center gap-2 p-2 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg">
                        <div className="flex flex-col gap-1">
                          <button
                            onClick={() => moveWaypoint(index, 'up')}
                            disabled={index === 0}
                            className="p-1 rounded hover:bg-slate-200 dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed transition-all"
                            title="Переместить вверх"
                          >
                            ⬆️
                          </button>
                          <button
                            onClick={() => moveWaypoint(index, 'down')}
                            disabled={index === form.waypoints.length - 1}
                            className="p-1 rounded hover:bg-slate-200 dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed transition-all"
                            title="Переместить вниз"
                          >
                            ⬇️
                          </button>
                        </div>
                        <div className="flex-1 grid grid-cols-2 gap-2">
                          <input
                            type="text"
                            placeholder="Город"
                            value={wp.city}
                            onChange={(e) => updateWaypoint(index, 'city', e.target.value)}
                            className="input text-xs py-1.5"
                          />
                          <input
                            type="text"
                            placeholder="Адрес (опционально)"
                            value={wp.address}
                            onChange={(e) => updateWaypoint(index, 'address', e.target.value)}
                            className="input text-xs py-1.5"
                          />
                        </div>
                        <button
                          onClick={() => removeWaypoint(index)}
                          className="p-1.5 rounded text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30 transition-all"
                          title="Удалить пункт"
                        >
                          🗑
                        </button>
                      </div>
                    ))}
                  </div>
                )}
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
                    {/* 🛣 Отображение пунктов следования */}
                    {trip.waypoints && trip.waypoints.length > 0 && (
                      <div className="mt-2 space-y-1">
                        <div className="text-xs font-semibold text-slate-500 dark:text-slate-400">🛣 Пункты следования:</div>
                        {trip.waypoints.map((wp, idx) => (
                          <div key={idx} className="text-xs text-slate-600 dark:text-slate-400 flex items-center gap-2">
                            <span className="text-slate-400">{idx + 1}.</span>
                            <span>{wp.city}{wp.address && ` — ${wp.address}`}</span>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
                {(trip.transport || participants.length > 0 || trip.notes) && (
                  <div className="space-y-1 text-xs text-slate-500 dark:text-slate-400 pt-2 border-t border-slate-100 dark:border-slate-800">
                    {trip.transport && <div>🚗 {trip.transport}</div>}
                    {participants.length > 0 && <div>👥 {participants.join(', ')}</div>}
                    {trip.notes && <div className="italic">📝 {trip.notes}</div>}
                  </div>
                )}
                <div className="flex justify-end pt-2 mt-2 border-t border-slate-100 dark:border-slate-800 gap-2">
                  <button 
                    onClick={() => handleOpenTripDetail(trip)} 
                    className="text-xs font-medium text-indigo-500 hover:bg-indigo-50 dark:hover:bg-indigo-950/30 px-2 py-1 rounded-lg transition-all"
                  >
                    📋 Открыть
                  </button>
                  <button 
                    onClick={() => handleDelete(trip.id)} 
                    className="text-xs font-medium text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30 px-2 py-1 rounded-lg opacity-0 group-hover:opacity-100 transition-all"
                  >
                    🗑 Удалить
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* 🆕 Модальное окно просмотра/редактирования командировки */}
      {showTripDetail && selectedTrip && createPortal(
        <div className="proles-modal-backdrop" onClick={() => !savingPerDiem && setShowTripDetail(false)}>
          <div className="proles-modal" onClick={(e) => e.stopPropagation()}>
            <div className="proles-modal-header">
              <div className="proles-modal-title">
                <div className="proles-modal-icon">📋</div>
                <div>
                  <div>Командировка</div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                    {TRIP_TYPES[selectedTrip.type as keyof typeof TRIP_TYPES]?.label || 'Просмотр'}
                  </div>
                </div>
              </div>
              <button onClick={() => setShowTripDetail(false)} className="proles-modal-close">✕</button>
            </div>
            <div className="proles-modal-body">
              {/* Основная информация */}
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Основная информация</div>
                <div className="space-y-3">
                  <div className="flex justify-between items-center py-2 border-b border-slate-100 dark:border-slate-800">
                    <span className="text-sm text-slate-500 dark:text-slate-400">Проект:</span>
                    <span className="font-semibold text-slate-900 dark:text-slate-100">{selectedTrip.projectName}</span>
                  </div>
                  <div className="flex justify-between items-center py-2 border-b border-slate-100 dark:border-slate-800">
                    <span className="text-sm text-slate-500 dark:text-slate-400">Сотрудник:</span>
                    <span className="font-semibold text-slate-900 dark:text-slate-100">
                      {allUsers.find(u => u.id === selectedTrip.userId)?.name || 'Неизвестный'}
                    </span>
                  </div>
                  <div className="flex justify-between items-center py-2 border-b border-slate-100 dark:border-slate-800">
                    <span className="text-sm text-slate-500 dark:text-slate-400">Дата:</span>
                    <span className="font-semibold text-slate-900 dark:text-slate-100">{formatDate(selectedTrip.date)}</span>
                  </div>
                  <div className="flex justify-between items-center py-2 border-b border-slate-100 dark:border-slate-800">
                    <span className="text-sm text-slate-500 dark:text-slate-400">Город:</span>
                    <span className="font-semibold text-slate-900 dark:text-slate-100">{selectedTrip.city || '—'}</span>
                  </div>
                  <div className="flex justify-between items-center py-2 border-b border-slate-100 dark:border-slate-800">
                    <span className="text-sm text-slate-500 dark:text-slate-400">Дней в командировке:</span>
                    <span className="font-bold text-emerald-600 dark:text-emerald-400">{calculateTripDays(selectedTrip)} дн.</span>
                  </div>
                </div>
              </div>

              {/* Суточные - редактирование для админов */}
              {(isAdmin || (user?.role === 'superadmin')) && (
                <div className="proles-modal-section">
                  <div className="proles-modal-section-title flex items-center justify-between">
                    <span>💰 Суточные</span>
                    <button
                      onClick={() => handleOpenPerDiemRecalc(selectedTrip)}
                      className="text-xs font-semibold px-3 py-1.5 rounded-lg bg-violet-500 hover:bg-violet-600 text-white transition-all flex items-center gap-1"
                    >
                      🔄 Перерасчет
                    </button>
                  </div>
                  <div className="p-4 bg-gradient-to-r from-emerald-50 to-teal-50 dark:from-emerald-950/30 dark:to-teal-950/30 rounded-xl border border-emerald-200 dark:border-emerald-800">
                    <div className="flex items-center justify-between mb-3">
                      <span className="text-sm font-medium text-emerald-800 dark:text-emerald-300">Размер суточных:</span>
                      <input
                        type="number"
                        value={editingPerDiemRate}
                        onChange={(e) => setEditingPerDiemRate(Number(e.target.value))}
                        className="w-32 px-3 py-1.5 text-right font-bold text-lg border-2 border-emerald-300 dark:border-emerald-700 rounded-lg bg-white dark:bg-slate-900 text-emerald-700 dark:text-emerald-400 focus:outline-none focus:ring-2 focus:ring-emerald-500"
                        step="1"
                        min="0"
                      />
                    </div>
                    <div className="flex items-center gap-2 text-xs text-emerald-600 dark:text-emerald-400 mb-3">
                      <span>ℹ️</span>
                      <span>Изменение размера суточных применится только к будущим начислениям</span>
                    </div>
                    <button
                      onClick={handleSavePerDiemRate}
                      disabled={savingPerDiem || editingPerDiemRate === selectedTrip.perDiemRate}
                      className="w-full py-2.5 px-4 bg-emerald-600 hover:bg-emerald-700 disabled:bg-emerald-400 text-white font-semibold rounded-lg transition-all flex items-center justify-center gap-2"
                    >
                      {savingPerDiem ? (
                        <>⏳ Сохранение...</>
                      ) : (
                        <>💾 Сохранить суточные</>
                      )}
                    </button>
                  </div>
                </div>
              )}

              {/* Маршрут */}
              {selectedTrip.waypoints && selectedTrip.waypoints.length > 0 && (
                <div className="proles-modal-section">
                  <div className="proles-modal-section-title">🛣 Пункты следования</div>
                  <div className="space-y-2">
                    {selectedTrip.waypoints.map((wp, idx) => (
                      <div key={idx} className="flex items-center gap-3 p-3 bg-slate-50 dark:bg-slate-800 rounded-lg">
                        <span className="flex-shrink-0 w-6 h-6 flex items-center justify-center bg-slate-200 dark:bg-slate-700 rounded-full text-xs font-bold text-slate-600 dark:text-slate-400">
                          {idx + 1}
                        </span>
                        <div className="flex-1">
                          <div className="font-medium text-slate-900 dark:text-slate-100">{wp.city}</div>
                          {wp.address && <div className="text-xs text-slate-500 dark:text-slate-400">{wp.address}</div>}
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Транспорт и участники */}
              {(selectedTrip.transport || selectedTrip.participants.length > 0) && (
                <div className="proles-modal-section">
                  <div className="proles-modal-section-title">Детали поездки</div>
                  <div className="space-y-2">
                    {selectedTrip.transport && (
                      <div className="flex items-center gap-2 text-sm">
                        <span className="text-slate-500 dark:text-slate-400">🚗</span>
                        <span className="text-slate-700 dark:text-slate-300">{selectedTrip.transport}</span>
                      </div>
                    )}
                    {selectedTrip.participants.length > 0 && (
                      <div>
                        <div className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-2">👥 Участники:</div>
                        <div className="flex flex-wrap gap-2">
                          {selectedTrip.participants.map(pid => {
                            const participant = allUsers.find(u => u.id === pid);
                            return (
                              <span key={pid} className="px-2.5 py-1 bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 rounded-full text-xs font-medium">
                                {participant?.name || 'Участник'}
                              </span>
                            );
                          })}
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              )}

              {/* Заметки */}
              {selectedTrip.notes && (
                <div className="proles-modal-section">
                  <div className="proles-modal-section-title">📝 Заметки</div>
                  <div className="p-3 bg-slate-50 dark:bg-slate-800 rounded-lg text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap">
                    {selectedTrip.notes}
                  </div>
                </div>
              )}
            </div>
            <div className="proles-modal-footer">
              <button onClick={() => setShowTripDetail(false)} className="proles-btn-cancel">Закрыть</button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* 🆕 Модальное окно перерасчета суточных */}
      {showPerDiemRecalc && selectedTrip && createPortal(
        <div className="proles-modal-backdrop" onClick={() => !recalculating && setShowPerDiemRecalc(false)}>
          <div className="proles-modal" onClick={(e) => e.stopPropagation()}>
            <div className="proles-modal-header">
              <div className="proles-modal-title">
                <div className="proles-modal-icon">🔄</div>
                <div>
                  <div>Перерасчет суточных</div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                    Массовое начисление суточных за период
                  </div>
                </div>
              </div>
              <button onClick={() => setShowPerDiemRecalc(false)} className="proles-modal-close">✕</button>
            </div>
            <div className="proles-modal-body">
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Параметры перерасчета</div>
                <div className="space-y-4">
                  <div className="proles-modal-grid">
                    <div className="proles-input-group">
                      <label>Дата с *</label>
                      <input
                        type="date"
                        value={recalcParams.dateFrom}
                        onChange={(e) => setRecalcParams({ ...recalcParams, dateFrom: e.target.value })}
                        className="input bg-white dark:bg-slate-900"
                      />
                    </div>
                    <div className="proles-input-group">
                      <label>Дата по *</label>
                      <input
                        type="date"
                        value={recalcParams.dateTo}
                        onChange={(e) => setRecalcParams({ ...recalcParams, dateTo: e.target.value })}
                        className="input bg-white dark:bg-slate-900"
                      />
                    </div>
                  </div>
                  <div className="proles-input-group">
                    <label>Ставка суточных (₽) *</label>
                    <input
                      type="number"
                      value={recalcParams.rate}
                      onChange={(e) => setRecalcParams({ ...recalcParams, rate: Number(e.target.value) })}
                      className="input bg-white dark:bg-slate-900"
                      step="1"
                      min="0"
                    />
                  </div>
                  <div className="p-4 bg-amber-50 dark:bg-amber-950/30 rounded-lg border border-amber-200 dark:border-amber-800">
                    <div className="flex items-start gap-2 text-sm text-amber-800 dark:text-amber-300">
                      <span className="text-lg">⚠️</span>
                      <div>
                        <div className="font-semibold mb-1">Внимание!</div>
                        <ul className="list-disc list-inside space-y-1 text-xs">
                          <li>Все существующие суточные в указанном периоде будут удалены</li>
                          <li>Суточные будут добавлены за каждый день периода</li>
                          <li>Операция необратима</li>
                        </ul>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <div className="proles-modal-footer">
              <button 
                onClick={() => setShowPerDiemRecalc(false)} 
                className="proles-btn-cancel"
                disabled={recalculating}
              >
                Отмена
              </button>
              <button
                onClick={handlePerDiemRecalc}
                disabled={recalculating || recalcParams.rate <= 0}
                className="px-6 py-2.5 bg-violet-600 hover:bg-violet-700 disabled:bg-violet-400 text-white font-semibold rounded-lg transition-all flex items-center gap-2"
              >
                {recalculating ? (
                  <>⏳ Перерасчет...</>
                ) : (
                  <>✅ Выполнить перерасчет</>
                )}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </div>
  );
}