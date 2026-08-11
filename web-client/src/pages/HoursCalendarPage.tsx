import { useState, useEffect, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import api from '../api/client';
import type { TimeEntryDto, UserDto, ProjectDto, DayOffDto } from '../types';
import { monthName } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';

// 🔥 Контрастная палитра из 13 сильно различающихся цветов
const USER_COLORS = [
  '#ef4444', '#f59e0b', '#eab308', '#84cc16', '#22c55e',
  '#14b8a6', '#06b6d4', '#3b82f6', '#6366f1', '#8b5cf6',
  '#a855f7', '#ec4899', '#f97316',
];

function getUserColor(userId: string): string {
  let hash = 0;
  for (let i = 0; i < userId.length; i++) {
    hash = userId.charCodeAt(i) + ((hash << 5) - hash);
  }
  return USER_COLORS[Math.abs(hash) % USER_COLORS.length];
}

function getInitials(name: string): string {
  const parts = name.trim().split(/\s+/);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return `${parts[0]} ${parts[1][0]}.`;
}

export function HoursCalendarPage() {
  const [searchParams] = useSearchParams();
  const filterUserId = searchParams.get('userId');

  const { can, loading: permLoading } = usePermissions();
  const canViewAll = !permLoading && can('projects', 'view');

  const [entries, setEntries] = useState<TimeEntryDto[]>([]);
  const [users, setUsers] = useState<UserDto[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [dayOffs, setDayOffs] = useState<DayOffDto[]>([]);
  const [loading, setLoading] = useState(true);

  const now = new Date();
  const [calYear, setCalYear] = useState(now.getFullYear());
  const [calMonth, setCalMonth] = useState(now.getMonth());
  const [selectedUserIds, setSelectedUserIds] = useState<Set<string>>(new Set());
  const [selectedProjectIds, setSelectedProjectIds] = useState<Set<string>>(new Set());
  const [showFilters, setShowFilters] = useState(false);
  const [viewMode, setViewMode] = useState<'month' | 'day'>('month');
  const [selectedDate, setSelectedDate] = useState<string>(now.toISOString().slice(0, 10));

  useEffect(() => {
    if (!canViewAll) return;
    (async () => {
      setLoading(true);
      const [e, u, p, d] = await Promise.allSettled([
        api.get<TimeEntryDto[]>('/entries/all'),
        api.get<UserDto[]>('/users'),
        api.get<ProjectDto[]>('/projects'),
        api.get<DayOffDto[]>('/dayoffs/all'),
      ]);
      setEntries(e.status === 'fulfilled' ? e.value.data : []);
      setUsers(u.status === 'fulfilled' ? u.value.data : []);
      setProjects(p.status === 'fulfilled' ? p.value.data : []);
      setDayOffs(d.status === 'fulfilled' ? d.value.data : []);
      setLoading(false);
    })();
  }, [canViewAll]);

  useEffect(() => {
    if (filterUserId) {
      setSelectedUserIds(new Set([filterUserId]));
    }
  }, [filterUserId]);

  const calMonthStr = `${calYear}-${String(calMonth + 1).padStart(2, '0')}`;

  const filteredEntries = useMemo(() => {
    return entries.filter(e => {
      if (!e.date.startsWith(calMonthStr)) return false;
      if (selectedUserIds.size > 0 && !selectedUserIds.has(e.userId)) return false;
      if (selectedProjectIds.size > 0 && !selectedProjectIds.has(e.projectId)) return false;
      return true;
    });
  }, [entries, calMonthStr, selectedUserIds, selectedProjectIds]);

  const filteredDayOffs = useMemo(() => {
    return dayOffs.filter(d => {
      if (!d.date.startsWith(calMonthStr)) return false;
      if (selectedUserIds.size > 0 && !selectedUserIds.has(d.user_id)) return false;
      return true;
    });
  }, [dayOffs, calMonthStr, selectedUserIds]);

  const entriesByDate = useMemo(() => {
    const map = new Map<string, TimeEntryDto[]>();
    filteredEntries.forEach(e => {
      const list = map.get(e.date) || [];
      list.push(e);
      map.set(e.date, list);
    });
    return map;
  }, [filteredEntries]);

  const dayOffsByDate = useMemo(() => {
    const map = new Map<string, DayOffDto[]>();
    filteredDayOffs.forEach(d => {
      const list = map.get(d.date) || [];
      list.push(d);
      map.set(d.date, list);
    });
    return map;
  }, [filteredDayOffs]);

//   const uniqueUsers = useMemo(() => {
//     const seen = new Map<string, string>();
//     filteredEntries.forEach(e => {
//       if (!seen.has(e.userId)) {
//         seen.set(e.userId, users.find(u => u.id === e.userId)?.name || 'Неизвестный');
//       }
//     });
//     return [...seen.entries()].map(([id, name]) => ({ id, name, color: getUserColor(id) }));
//   }, [filteredEntries, users]);

  const daysInMonth = new Date(calYear, calMonth + 1, 0).getDate();
  let startDow = new Date(calYear, calMonth, 1).getDay() - 1;
  if (startDow < 0) startDow = 6;
  const weekDays = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];

  const prevMonth = () => {
    if (calMonth === 0) { setCalMonth(11); setCalYear(y => y - 1); }
    else setCalMonth(m => m - 1);
  };
  const nextMonth = () => {
    if (calMonth === 11) { setCalMonth(0); setCalYear(y => y + 1); }
    else setCalMonth(m => m + 1);
  };

  const toggleUser = (userId: string) => {
    setSelectedUserIds(prev => {
      const next = new Set(prev);
      if (next.has(userId)) next.delete(userId);
      else next.add(userId);
      return next;
    });
  };

  const toggleProject = (projectId: string) => {
    setSelectedProjectIds(prev => {
      const next = new Set(prev);
      if (next.has(projectId)) next.delete(projectId);
      else next.add(projectId);
      return next;
    });
  };

  const resetFilter = () => {
    setSelectedUserIds(new Set());
    setSelectedProjectIds(new Set());
  };

  // 📊 Статистика: зависит от режима
  const dayEntries = filteredEntries.filter(e => e.date === selectedDate);
  const dayDayOffs = filteredDayOffs.filter(d => d.date === selectedDate);
  // 🎯 Выходной НЕ считается, если у сотрудника есть часы в этот день
  const actualDayOffs = dayDayOffs.filter(d =>
    !dayEntries.some(e => e.userId === d.user_id && e.hours > 0)
  );

  const totalHours = viewMode === 'day'
    ? dayEntries.reduce((s, e) => s + e.hours, 0)
    : filteredEntries.reduce((s, e) => s + e.hours, 0);
  const workDays = viewMode === 'day'
    ? (dayEntries.length > 0 ? 1 : 0)
    : new Set(filteredEntries.map(e => e.date)).size;
  const dayOffsCount = viewMode === 'day'
    ? actualDayOffs.length
    : new Set(filteredDayOffs.filter(d =>
        !filteredEntries.some(e => e.userId === d.user_id && e.date === d.date && e.hours > 0)
      ).map(d => d.date)).size;

  if (permLoading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;

  if (!canViewAll) {
    return (
      <div className="card p-12 text-center">
        <div className="text-5xl mb-4 opacity-50">🔒</div>
        <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Нет доступа</h3>
        <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">У вас нет прав на просмотр часов всех сотрудников</p>
      </div>
    );
  }

  if (loading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">📅 Часы сотрудников</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            {viewMode === 'day'
              ? `${dayEntries.length} записей за ${new Date(selectedDate).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })}`
              : `${filteredEntries.length} записей`
            }
            {selectedUserIds.size > 0 && ` • Фильтр: ${selectedUserIds.size} сотр.`}
          </p>
        </div>
        <div className="flex gap-2">
          {/* 🔄 Переключатель режимов */}
          <div className="flex bg-white dark:bg-slate-900 rounded-xl p-1 border border-slate-200 dark:border-slate-700">
            <button onClick={() => setViewMode('month')} className={`px-3 py-1.5 rounded-lg text-xs font-bold transition-all ${viewMode === 'month' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'text-slate-500 dark:text-slate-400'}`}>
              📅 Месяц
            </button>
            <button onClick={() => setViewMode('day')} className={`px-3 py-1.5 rounded-lg text-xs font-bold transition-all ${viewMode === 'day' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'text-slate-500 dark:text-slate-400'}`}>
              📋 День
            </button>
          </div>
          <button onClick={() => setShowFilters(!showFilters)} className="btn-outline px-4 py-2 text-sm">
            {showFilters ? '✕ Скрыть' : '🔍 Фильтры'}
          </button>
        </div>
      </div>

      {/* 📊 Статистика — зависит от режима */}
      <div className="grid grid-cols-3 gap-3">
        <div className="card p-4 bg-gradient-to-br from-blue-50 to-indigo-50 dark:from-blue-950/30 dark:to-indigo-950/30 border-blue-100 dark:border-blue-900">
          <div className="text-xs font-bold text-blue-600 dark:text-blue-400 uppercase">
            {viewMode === 'day' ? 'Часов за день' : 'Всего часов'}
          </div>
          <div className="text-2xl font-black text-blue-900 dark:text-blue-100 mt-1">
            {totalHours > 0 ? `${totalHours % 1 === 0 ? totalHours : totalHours.toFixed(1)}ч` : '—'}
          </div>
        </div>
        <div className="card p-4 bg-gradient-to-br from-emerald-50 to-green-50 dark:from-emerald-950/30 dark:to-green-950/30 border-emerald-100 dark:border-emerald-900">
          <div className="text-xs font-bold text-emerald-600 dark:text-emerald-400 uppercase">
            {viewMode === 'day' ? 'Сотрудников' : 'Рабочих дней'}
          </div>
          <div className="text-2xl font-black text-emerald-900 dark:text-emerald-100 mt-1">
            {viewMode === 'day' ? new Set(dayEntries.map(e => e.userId)).size : workDays}
          </div>
        </div>
        <div className="card p-4 bg-gradient-to-br from-rose-50 to-red-50 dark:from-rose-950/30 dark:to-red-950/30 border-rose-100 dark:border-rose-900">
          <div className="text-xs font-bold text-rose-600 dark:text-rose-400 uppercase">
            {viewMode === 'day' ? 'Выходных за день' : 'Выходных'}
          </div>
          <div className="text-2xl font-black text-rose-900 dark:text-rose-100 mt-1">{dayOffsCount}</div>
        </div>
      </div>

      {/* Фильтры по сотрудникам и проектам */}
      {showFilters && (
        <div className="card p-4 animate-fade-in">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-bold text-slate-900 dark:text-slate-100">Фильтры:</h3>
            {(selectedUserIds.size > 0 || selectedProjectIds.size > 0) && (
              <button onClick={resetFilter} className="text-xs text-indigo-600 dark:text-indigo-400 hover:underline">
                Сбросить всё
              </button>
            )}
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {/* Сотрудники */}
            <div>
              <h4 className="text-xs font-bold text-slate-700 dark:text-slate-300 mb-2 flex items-center gap-1.5">
                <span>👤</span> Сотрудники ({selectedUserIds.size})
              </h4>
              <div className="grid grid-cols-2 gap-1.5 max-h-48 overflow-y-auto p-1">
                {users.map(u => (
                  <label key={u.id} className={`flex items-center gap-1.5 p-1.5 rounded-lg cursor-pointer text-xs transition-all ${
                    selectedUserIds.has(u.id)
                      ? 'bg-indigo-50 dark:bg-indigo-950/40 text-indigo-700 dark:text-indigo-300 ring-1 ring-indigo-300 dark:ring-indigo-700'
                      : 'hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-300'
                  }`}>
                    <input type="checkbox" checked={selectedUserIds.has(u.id)} onChange={() => toggleUser(u.id)} className="rounded accent-indigo-600 w-3.5 h-3.5" />
                    <div className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ backgroundColor: getUserColor(u.id) }} />
                    <span className="truncate">{u.name}</span>
                  </label>
                ))}
              </div>
            </div>
            {/* Проекты */}
            <div>
              <h4 className="text-xs font-bold text-slate-700 dark:text-slate-300 mb-2 flex items-center gap-1.5">
                <span>📁</span> Проекты ({selectedProjectIds.size})
              </h4>
              <div className="grid grid-cols-2 gap-1.5 max-h-48 overflow-y-auto p-1">
                {projects.filter(p => p.isActive).map(p => (
                  <label key={p.id} className={`flex items-center gap-1.5 p-1.5 rounded-lg cursor-pointer text-xs transition-all ${
                    selectedProjectIds.has(p.id)
                      ? 'bg-emerald-50 dark:bg-emerald-950/40 text-emerald-700 dark:text-emerald-300 ring-1 ring-emerald-300 dark:ring-emerald-700'
                      : 'hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-700 dark:text-slate-300'
                  }`}>
                    <input type="checkbox" checked={selectedProjectIds.has(p.id)} onChange={() => toggleProject(p.id)} className="rounded accent-emerald-600 w-3.5 h-3.5" />
                    <span className="truncate">{p.name}</span>
                  </label>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 🎯 Блок выбранных фильтров (сотрудники и проекты) */}
      {(selectedUserIds.size > 0 || selectedProjectIds.size > 0) && (
        <div className="card p-4 bg-indigo-50/50 dark:bg-indigo-950/20 border-indigo-200 dark:border-indigo-800 animate-fade-in">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-xs font-bold text-indigo-900 dark:text-indigo-200 uppercase tracking-wide flex items-center gap-2">
              <span className="w-1.5 h-4 bg-indigo-500 rounded-full"></span>
              Активные фильтры ({selectedUserIds.size + selectedProjectIds.size})
            </h3>
            <button onClick={resetFilter} className="text-xs font-medium text-indigo-600 dark:text-indigo-400 hover:underline flex items-center gap-1">
              ✕ Сбросить всё
            </button>
          </div>
          <div className="flex flex-wrap gap-2">
            {Array.from(selectedUserIds).map(userId => {
              const userName = users.find(u => u.id === userId)?.name || 'Неизвестный';
              const userColor = getUserColor(userId);
              return (
                <div key={userId} className="inline-flex items-center gap-2 pl-3 pr-1 py-1 rounded-full bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 shadow-sm">
                  <div className="w-2.5 h-2.5 rounded-full flex-shrink-0" style={{ backgroundColor: userColor }} />
                  <span className="text-xs font-medium text-slate-900 dark:text-slate-100">👤 {userName}</span>
                  <button
                    onClick={() => toggleUser(userId)}
                    className="w-5 h-5 rounded-full flex items-center justify-center text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30 transition-colors text-xs"
                    title="Убрать из фильтра"
                  >
                    ✕
                  </button>
                </div>
              );
            })}
            {Array.from(selectedProjectIds).map(projectId => {
              const projectName = projects.find(p => p.id === projectId)?.name || 'Неизвестный';
              return (
                <div key={projectId} className="inline-flex items-center gap-2 pl-3 pr-1 py-1 rounded-full bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 shadow-sm">
                  <span className="text-xs font-medium text-slate-900 dark:text-slate-100">📁 {projectName}</span>
                  <button
                    onClick={() => toggleProject(projectId)}
                    className="w-5 h-5 rounded-full flex items-center justify-center text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30 transition-colors text-xs"
                    title="Убрать из фильтра"
                  >
                    ✕
                  </button>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* ═══════════ 📋 РЕЖИМ "ДЕНЬ" ═══════════ */}
      {viewMode === 'day' && (
        <div className="card p-5 md:p-6 animate-fade-in">
          {/* Навигация по дням */}
          <div className="flex items-center justify-between mb-6">
            <button
              onClick={() => {
                const d = new Date(selectedDate);
                d.setDate(d.getDate() - 1);
                setSelectedDate(d.toISOString().slice(0, 10));
              }}
              className="btn-ghost px-4 py-2 text-lg"
            >
              ‹
            </button>
            <div className="text-center">
              {/* ✅ Формат: 27 июля (без года) */}
              <div className="text-lg font-bold text-slate-900 dark:text-slate-100 capitalize">
                {new Date(selectedDate).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })}
              </div>
              {/* ✅ Отдельная строка: понедельник */}
              <div className="text-sm font-medium text-slate-500 dark:text-slate-400 capitalize">
                {new Date(selectedDate).toLocaleDateString('ru-RU', { weekday: 'long' })}
              </div>
            </div>
            <button
              onClick={() => {
                const d = new Date(selectedDate);
                d.setDate(d.getDate() + 1);
                setSelectedDate(d.toISOString().slice(0, 10));
              }}
              className="btn-ghost px-4 py-2 text-lg"
            >
              ›
            </button>
          </div>

          {/* 📊 Записи часов за день */}
          <div className="mb-6">
            <h3 className="text-sm font-bold text-slate-900 dark:text-slate-100 flex items-center gap-2 mb-3">
              <span className="w-1.5 h-4 bg-blue-500 rounded-full"></span>
              Записи часов ({dayEntries.length})
            </h3>
            {dayEntries.length === 0 ? (
              <div className="text-center py-8 text-slate-400 dark:text-slate-500">
                <div className="text-4xl mb-2">📭</div>
                <div className="text-sm">Нет записей за этот день</div>
              </div>
            ) : (
              <div className="space-y-2">
                {dayEntries.map(entry => {
                  const userName = users.find(u => u.id === entry.userId)?.name || 'Неизвестный';
                  const userColor = getUserColor(entry.userId);
                  const projectName = entry.projectName || projects.find(p => p.id === entry.projectId)?.name || 'Без проекта';
                  return (
                    <div key={entry.id} className="flex items-center gap-3 p-3 rounded-xl bg-slate-50 dark:bg-slate-800/50 hover:shadow-sm transition-all">
                      {/* ✅ Убраны инициалы, оставлен только цветной кружок-индикатор */}
                      <div className="w-10 h-10 rounded-full flex-shrink-0" style={{ backgroundColor: userColor }}></div>
                      <div className="flex-1 min-w-0">
                        <div className="font-semibold text-slate-900 dark:text-slate-100 text-sm truncate">{projectName}</div>
                        <div className="text-xs text-slate-500 dark:text-slate-400 truncate">{userName}</div>
                        {entry.comment && <div className="text-xs text-slate-400 dark:text-slate-500 truncate mt-0.5 italic">💬 {entry.comment}</div>}
                      </div>
                      <div className="text-xl font-black text-blue-700 dark:text-blue-400 tabular-nums flex-shrink-0 bg-blue-50 dark:bg-blue-950/30 px-3 py-1.5 rounded-lg">
                        {entry.hours % 1 === 0 ? entry.hours : entry.hours.toFixed(1)}ч
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {/* 🌞 Выходные за день */}
          {actualDayOffs.length > 0 && (
            <div>
              <h3 className="text-sm font-bold text-slate-900 dark:text-slate-100 flex items-center gap-2 mb-3">
                <span className="w-1.5 h-4 bg-rose-500 rounded-full"></span>
                Выходные ({actualDayOffs.length})
              </h3>
              <div className="space-y-2">
                {actualDayOffs.map(d => {
                  const userName = users.find(u => u.id === d.user_id)?.name || 'Неизвестный';
                  const userColor = getUserColor(d.user_id);
                  return (
                    <div key={`${d.user_id}-${d.date}`} className="flex items-center gap-3 p-3 rounded-xl bg-rose-50/50 dark:bg-rose-950/20 border border-rose-200 dark:border-rose-900">
                      <div className="w-10 h-10 rounded-full flex items-center justify-center text-white text-sm font-bold flex-shrink-0" style={{ backgroundColor: userColor }}>
                      </div>
                      <div className="flex-1">
                        <div className="font-semibold text-slate-900 dark:text-slate-100 text-sm">{userName}</div>
                        <div className="text-xs text-rose-600 dark:text-rose-400 font-medium">Выходной день</div>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </div>
      )}

      {/* ═══════════ 📅 РЕЖИМ "МЕСЯЦ" ═══════════ */}
      {viewMode === 'month' && (
      <div className="card p-4 md:p-5 overflow-hidden">

      {/* Календарь */}
      <div className="card p-4 md:p-5 overflow-hidden">
        <div className="flex items-center justify-between mb-4">
          <button onClick={prevMonth} className="btn-ghost px-3 py-1 text-lg">‹</button>
          <h3 className="text-lg font-bold text-slate-900 dark:text-slate-100 capitalize w-48 text-center">
            {monthName(calMonth + 1)} {calYear}
          </h3>
          <button onClick={nextMonth} className="btn-ghost px-3 py-1 text-lg">›</button>
        </div>

        {/* Дни недели */}
        <div className="grid grid-cols-7 gap-px bg-slate-200 dark:bg-slate-700 border border-slate-200 dark:border-slate-700 rounded-lg overflow-hidden mb-px">
          {weekDays.map((wd, i) => (
            <div key={wd} className={`p-2 text-center text-xs font-bold uppercase bg-slate-50 dark:bg-slate-800 ${i >= 5 ? 'text-red-500' : 'text-slate-500 dark:text-slate-400'}`}>
              {wd}
            </div>
          ))}
        </div>

        {/* Сетка дней */}
        <div className="grid grid-cols-7 gap-px bg-slate-200 dark:bg-slate-700 border border-slate-200 dark:border-slate-700 rounded-lg overflow-hidden">
          {Array.from({ length: startDow }).map((_, i) => (
            <div key={`empty-${i}`} className="bg-white dark:bg-slate-900 min-h-[85px]" />
          ))}

          {Array.from({ length: daysInMonth }).map((_, i) => {
            const day = i + 1;
            const dateStr = `${calYear}-${String(calMonth + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
            const dayEntriesList = entriesByDate.get(dateStr) || [];
            const dayOffsList = dayOffsByDate.get(dateStr) || [];
            const isToday = dateStr === now.toISOString().slice(0, 10);
            const dow = (startDow + i) % 7;
            const isWknd = dow >= 5;
            // 🎯 Выходной НЕ считается, если у сотрудника есть часы в этот день
            const actualDayOffsCell = dayOffsList.filter(d =>
              !dayEntriesList.some(e => e.userId === d.user_id && e.hours > 0)
            );
            const hasDayOffs = actualDayOffsCell.length > 0;
            const isSelected = dateStr === selectedDate;
            // Фон ячейки
            let bgClass = 'bg-white dark:bg-slate-900';
            if (hasDayOffs) {
              bgClass = 'bg-rose-50 dark:bg-rose-950/20';
            } else if (isWknd) {
              bgClass = 'bg-red-50/50 dark:bg-red-950/10';
            }

            return (
              <div
                key={dateStr}
                onClick={() => { setSelectedDate(dateStr); setViewMode('day'); }}
                className={`${bgClass} min-h-[85px] p-1.5 flex flex-col cursor-pointer hover:shadow-md hover:scale-[1.02] transition-all ${
                  isSelected ? 'ring-2 ring-inset ring-indigo-500 dark:ring-indigo-400' :
                  isToday ? 'ring-2 ring-inset ring-indigo-400 dark:ring-indigo-500' : ''
                }`}
              >
              <div className={`text-[11px] font-bold mb-1 ${isToday ? 'text-indigo-600 dark:text-indigo-400' : isWknd ? 'text-red-500' : 'text-slate-500 dark:text-slate-400'}`}>
                {day}
              </div>

              {/* Выходные */}
              {hasDayOffs && (
                <div className="mb-1">
                  {actualDayOffsCell.slice(0, 3).map((d, idx) => {
                    const userName = users.find(u => u.id === d.user_id)?.name || '?';
                    return (
                      <div key={idx} className="text-[9px] leading-tight text-rose-700 dark:text-rose-400 font-semibold truncate">
                        {getInitials(userName)} вых
                      </div>
                    );
                  })}
                  {actualDayOffsCell.length > 3 && (
                    <div className="text-[8px] text-rose-500 pl-2">+{actualDayOffsCell.length - 3}</div>
                  )}
                </div>
              )}

              {/* Записи часов */}
              <div className="flex-1 flex flex-col gap-px">
                {dayEntriesList.slice(0, 7).map((entry, idx) => {
                  const userName = users.find(u => u.id === entry.userId)?.name || 'Неизвестный';
                  const userColor = getUserColor(entry.userId);
                  return (
                    <div key={`${entry.userId}-${entry.projectId}-${idx}`} className="flex items-center justify-between gap-1 min-w-0" title={`${userName}: ${entry.projectName} — ${entry.hours}ч`}>
                      <span className="text-[9px] leading-[1.1] truncate font-medium" style={{ color: userColor }}>
                        {getInitials(userName)}
                      </span>
                      <span className="text-[9px] leading-[1.1] text-slate-600 dark:text-slate-400 font-bold tabular-nums flex-shrink-0">
                        {entry.hours}ч
                      </span>
                    </div>
                  );
                })}
                {dayEntriesList.length > 7 && (
                  <div className="text-[8px] text-slate-400 pl-1">+{dayEntriesList.length - 7}</div>
                )}
              </div>
            </div>
            );
          })}
        </div>

        {/* Легенда */}
        <div className="mt-4 pt-4 border-t border-slate-200 dark:border-slate-700 flex flex-wrap gap-x-4 gap-y-2 text-xs">
          <div className="flex items-center gap-1.5">
            <div className="w-3 h-3 rounded bg-rose-50 dark:bg-rose-950/20 border border-rose-200 dark:border-rose-900"></div>
            <span className="text-slate-600 dark:text-slate-400">Выходные сотрудников</span>
          </div>
          <div className="flex items-center gap-1.5">
            <div className="w-3 h-3 rounded bg-red-50/50 dark:bg-red-950/10 border border-red-200 dark:border-red-900"></div>
            <span className="text-slate-600 dark:text-slate-400">Выходные дни</span>
          </div>
          <div className="flex items-center gap-1.5">
            <div className="w-3 h-3 rounded bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 ring-2 ring-indigo-400 dark:ring-indigo-500"></div>
            <span className="text-slate-600 dark:text-slate-400">Сегодня</span>
          </div>
        </div>
      </div>
    </div>
  )}
  </div>
);
}