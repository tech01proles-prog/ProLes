import { useState, useEffect, useCallback } from 'react';
import api from '../api/client';
import type { DayOffDto, UserDto } from '../types';
import { formatDate, dayOfWeekRu, isWeekend } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';

export function DayOffsPage() {
  const [user, setUser] = useState<UserDto | null>(null);
  const { can, loading: permLoading } = usePermissions();
  const [dayOffs, setDayOffs] = useState<DayOffDto[]>([]);
  const [allUsers, setAllUsers] = useState<UserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [filterMonth, setFilterMonth] = useState(() => {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
  });

  // 🔐 Право на просмотр всех выходных
  const canViewAll = !permLoading && can('dayoffs_all', 'view');

  const [selectedDate, setSelectedDate] = useState(new Date().toISOString().slice(0, 10));
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) try { setUser(JSON.parse(stored)); } catch {}
  }, []);

  const loadData = useCallback(async () => {
    if (!user || permLoading) return;
    setLoading(true);
    try {
      if (canViewAll) {
        // 🔐 Админ: один запрос через /dayoffs/all
        const [usersRes, dayoffsRes] = await Promise.allSettled([
          api.get<UserDto[]>('/users'),
          api.get<DayOffDto[]>('/dayoffs/all'),
        ]);
        setAllUsers(usersRes.status === 'fulfilled' ? usersRes.value.data : []);
        setDayOffs(dayoffsRes.status === 'fulfilled' ? dayoffsRes.value.data : []);
      } else {
        // 🔐 Обычный сотрудник: только свои выходные
        const dayoffsRes = await api.get<DayOffDto[]>('/dayoffs', { params: { userId: user.id } });
        setDayOffs(dayoffsRes.data);
      }
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [user, canViewAll, permLoading]);

  useEffect(() => { loadData(); }, [loadData]);

  const handleAdd = async () => {
    if (!user) return;
    if (dayOffs.some(d => d.date === selectedDate && d.user_id === user.id)) {
      alert('Этот выходной уже добавлен');
      return;
    }
    setSaving(true);
    try {
      await api.post('/dayoffs', { user_id: user.id, date: selectedDate });
      await loadData();
    } catch { alert('Ошибка добавления выходного'); }
    finally { setSaving(false); }
  };

  const handleDelete = async (date: string) => {
    if (!user || !confirm('Удалить выходной?')) return;
    await api.delete('/dayoffs', { params: { userId: user.id, date } });
    await loadData();
  };

  const filtered = dayOffs.filter(d => d.date.startsWith(filterMonth));
  const weekdayCount = filtered.filter(d => !isWeekend(d.date)).length;
  const weekendCount = filtered.filter(d => isWeekend(d.date)).length;

  // Быстрый выбор: ближайшие 14 дней
  const quickDates = Array.from({ length: 14 }, (_, i) => {
    const d = new Date();
    d.setDate(d.getDate() + i);
    return d.toISOString().slice(0, 10);
  });

  const myDayOffs = dayOffs.filter(d => d.user_id === user?.id);

  if (permLoading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;

  return (
    <div className="space-y-6 max-w-5xl mx-auto">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">🌞 Выходные</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            {canViewAll ? `${dayOffs.length} всего у всех сотрудников` : `${myDayOffs.length} всего`}
            {canViewAll && <span className="ml-2 badge-indigo">Админ-режим</span>}
          </p>
        </div>
      </div>

      {/* Календарная сетка */}
      <div className="card p-5">
        <h3 className="font-bold text-slate-900 dark:text-slate-100 text-sm mb-3 flex items-center gap-2">
          <span className="w-1.5 h-5 bg-yellow-500 rounded-full"></span>
          Быстрое добавление (ближайшие 14 дней)
        </h3>
        <div className="grid grid-cols-7 gap-2">
          {quickDates.map(date => {
            const isWeekendDay = isWeekend(date);
            const myExists = myDayOffs.some(d => d.date === date);
            const isSelected = selectedDate === date;
            const d = new Date(date);
            return (
              <button key={date} onClick={() => setSelectedDate(date)} disabled={myExists}
                className={`p-2 rounded-lg text-center transition-all border ${
                  myExists ? 'bg-emerald-50 dark:bg-emerald-950/20 border-emerald-200 dark:border-emerald-900 cursor-not-allowed' :
                  isSelected ? 'bg-yellow-500 text-white border-yellow-500 shadow-md' :
                  isWeekendDay ? 'bg-orange-50 dark:bg-orange-950/20 border-orange-200 dark:border-orange-900 hover:border-orange-400' :
                  'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700 hover:border-yellow-400'
                }`}
              >
                <div className="text-[10px] font-medium opacity-80">
                  {['Вс', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб'][d.getDay()]}
                </div>
                <div className="text-lg font-bold">{d.getDate()}</div>
                {myExists && <div className="text-[9px] mt-0.5">✓</div>}
              </button>
            );
          })}
        </div>
        <div className="mt-4 flex items-center justify-between pt-4 border-t border-slate-100 dark:border-slate-800">
          <div>
            <div className="text-sm text-slate-600 dark:text-slate-400">
              Выбрано: <span className="font-bold text-slate-900 dark:text-slate-100">{formatDate(selectedDate)}</span>
              <span className="text-slate-400 ml-2">{dayOfWeekRu(selectedDate)}</span>
            </div>
            {isWeekend(selectedDate) && <div className="text-xs text-orange-600 dark:text-orange-400 mt-1">⚠️ Это уже выходной день</div>}
            {myDayOffs.some(d => d.date === selectedDate) && <div className="text-xs text-emerald-600 dark:text-emerald-400 mt-1">✓ Уже добавлен</div>}
          </div>
          <button onClick={handleAdd} disabled={saving || myDayOffs.some(d => d.date === selectedDate)} className="btn-primary px-5 py-2.5">
            {saving ? '⏳...' : '＋ Добавить'}
          </button>
        </div>
      </div>

      {/* Фильтр по месяцу */}
      <div className="card p-4 flex items-center gap-3 flex-wrap">
        <label className="text-sm font-medium text-slate-700 dark:text-slate-300">📅 Месяц:</label>
        <input type="month" value={filterMonth} onChange={(e) => setFilterMonth(e.target.value)} className="input w-auto" />
        <div className="ml-auto text-sm text-slate-600 dark:text-slate-400">
          Найдено: <span className="font-bold text-slate-900 dark:text-slate-100">{filtered.length}</span>
          {' '}• В будни: <span className="font-bold text-yellow-600">{weekdayCount}</span>
          {' '}• В выходные: <span className="font-bold text-orange-600">{weekendCount}</span>
        </div>
      </div>

      {/* Список выходных */}
      {loading ? (
        <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>
      ) : filtered.length === 0 ? (
        <div className="card p-12 text-center border-dashed border-2 border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-900/50">
          <div className="text-5xl mb-4 opacity-50">🌞</div>
          <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Нет выходных в этом месяце</h3>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          {filtered.sort((a, b) => a.date.localeCompare(b.date)).map(d => {
            const isWk = isWeekend(d.date);
            const isOwn = d.user_id === user?.id;
            const userName = canViewAll ? allUsers.find(u => u.id === d.user_id)?.name : null;
            return (
              <div key={`${d.user_id}-${d.date}`} className={`card p-4 flex items-center justify-between group hover:shadow-md transition-all animate-fade-in ${
                isWk ? 'border-orange-200 dark:border-orange-900 bg-orange-50/30 dark:bg-orange-950/10' : 'border-yellow-200 dark:border-yellow-900 bg-yellow-50/30 dark:bg-yellow-950/10'
              }`}>
                <div className="flex items-center gap-3">
                  <div className={`w-12 h-12 rounded-xl flex flex-col items-center justify-center text-white font-bold shadow-sm ${
                    isWk ? 'bg-gradient-to-br from-orange-400 to-red-400' : 'bg-gradient-to-br from-yellow-400 to-amber-400'
                  }`}>
                    <div className="text-[9px] opacity-90">{dayOfWeekRu(d.date).slice(0, 2).toUpperCase()}</div>
                    <div className="text-lg leading-none">{new Date(d.date).getDate()}</div>
                  </div>
                  <div>
                    <div className="font-bold text-slate-900 dark:text-slate-100">{formatDate(d.date)}</div>
                    <div className="text-xs text-slate-500 dark:text-slate-400">{dayOfWeekRu(d.date)}</div>
                    {userName && <div className="text-xs font-medium text-indigo-600 dark:text-indigo-400 mt-0.5">👤 {userName}</div>}
                    {isWk ? (
                      <span className="text-[10px] text-orange-600 dark:text-orange-400 font-medium">Выходной день</span>
                    ) : (
                      <span className="text-[10px] text-amber-700 dark:text-amber-400 font-medium">⚠️ Будний день</span>
                    )}
                  </div>
                </div>
                {isOwn && (
                  <button onClick={() => handleDelete(d.date)} className="opacity-0 group-hover:opacity-100 p-2 text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30 rounded-lg transition-all">
                    🗑
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}