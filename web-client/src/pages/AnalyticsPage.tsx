import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/client';
import type { TimeEntryDto, ExpenseDto, ProjectDto, UserDto } from '../types';
import { formatMoney } from '../lib/utils';

export function AnalyticsPage() {
  const navigate = useNavigate();
  const [entries, setEntries] = useState<TimeEntryDto[]>([]);
  const [expenses, setExpenses] = useState<ExpenseDto[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [users, setUsers] = useState<UserDto[]>([]);
  const [loading, setLoading] = useState(true);

  const [period, setPeriod] = useState<'week' | 'month' | 'year'>('month');

  useEffect(() => {
    (async () => {
      setLoading(true);
      const [e, ex, p, u] = await Promise.allSettled([
        api.get<TimeEntryDto[]>('/entries/all'),
        api.get<ExpenseDto[]>('/expenses/all'),
        api.get<ProjectDto[]>('/projects'),
        api.get<UserDto[]>('/users'),
      ]);
      setEntries(e.status === 'fulfilled' ? e.value.data : []);
      setExpenses(ex.status === 'fulfilled' ? ex.value.data : []);
      setProjects(p.status === 'fulfilled' ? p.value.data : []);
      setUsers(u.status === 'fulfilled' ? u.value.data : []);
      setLoading(false);
    })();
  }, []);

  // Фильтрация по периоду
  const cutoffDate = useMemo(() => {
    const d = new Date();
    if (period === 'week') d.setDate(d.getDate() - 7);
    else if (period === 'month') d.setDate(1);
    else if (period === 'year') { d.setMonth(0); d.setDate(1); }
    return d.toISOString().slice(0, 10);
  }, [period]);

  const periodEntries = useMemo(() => entries.filter(e => e.date >= cutoffDate), [entries, cutoffDate]);
  const periodExpenses = useMemo(() => expenses.filter(e => e.date >= cutoffDate), [expenses, cutoffDate]);

  // KPI
  const totalHours = periodEntries.reduce((s, e) => s + e.hours, 0);
  const totalExpensesAmount = periodExpenses.reduce((s, e) => s + e.amount, 0);
  const activeProjectsCount = projects.filter(p => p.isActive).length;
  const employeesCount = users.length;

  // Топ проектов по часам
  const projectHours = useMemo(() => {
    const map = new Map<string, { name: string; hours: number }>();
    periodEntries.forEach(e => {
      const name = e.projectName || projects.find(p => p.id === e.projectId)?.name || 'Без проекта';
      const cur = map.get(e.projectId) || { name, hours: 0 };
      cur.hours += e.hours;
      map.set(e.projectId, cur);
    });
    return [...map.values()].sort((a, b) => b.hours - a.hours).slice(0, 10);
  }, [periodEntries, projects]);

  // Топ сотрудников по часам
  const userHours = useMemo(() => {
    const map = new Map<string, { name: string; hours: number }>();
    periodEntries.forEach(e => {
      const name = users.find(u => u.id === e.userId)?.name || 'Неизвестный';
      const cur = map.get(e.userId) || { name, hours: 0 };
      cur.hours += e.hours;
      map.set(e.userId, cur);
    });
    return [...map.values()].sort((a, b) => b.hours - a.hours).slice(0, 10);
  }, [periodEntries, users]);

  // Расходы по типам
  const expensesByType = useMemo(() => {
    const map = new Map<string, number>();
    periodExpenses.forEach(e => {
      map.set(e.type, (map.get(e.type) || 0) + e.amount);
    });
    return [...map.entries()].sort((a, b) => b[1] - a[1]);
  }, [periodExpenses]);

  // Часы по дням недели
  const hoursByWeekday = useMemo(() => {
    const days = [0, 0, 0, 0, 0, 0, 0]; // Пн..Вс
    periodEntries.forEach(e => {
      const d = new Date(e.date).getDay();
      const idx = d === 0 ? 6 : d - 1;
      days[idx] += e.hours;
    });
    return days;
  }, [periodEntries]);

  const formatHours = (h: number) => {
    const hrs = Math.floor(h); const mins = Math.round((h - hrs) * 60);
    return mins > 0 ? `${hrs}ч ${mins}м` : `${hrs}ч`;
  };

  const EXPENSE_TYPE_LABELS: Record<string, string> = {
    CONTRACTORS: '👷 Подрядчики', MATERIALS: '🧱 Материалы', EQUIPMENT: '🔧 Оборудование',
    TRANSPORT: '🚚 Транспорт Доп.', ROAD: '🚗 Транспорт', MANAGER_COMMISSION: '💼 Комиссия',
    FINES: '⚠️ Штрафы', CREDIT: '🏦 Кредит', OTHER: '📦 Другое',
  };

  const weekdayNames = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];
  const maxWeekdayHours = Math.max(...hoursByWeekday, 1);
  const maxProjectHours = projectHours[0]?.hours || 1;
  const maxUserHours = userHours[0]?.hours || 1;
  const maxExpenseType = expensesByType[0]?.[1] || 1;

  if (loading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">📊 Аналитика</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">Подробный обзор эффективности команды</p>
        </div>
        <div className="flex bg-white dark:bg-slate-900 rounded-xl p-1 border border-slate-200 dark:border-slate-700">
          {(['week', 'month', 'year'] as const).map(p => (
            <button key={p} onClick={() => setPeriod(p)} className={`px-3 py-1.5 rounded-md text-xs font-bold transition-all ${period === p ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'text-slate-500 dark:text-slate-400'}`}>
              {p === 'week' ? 'Неделя' : p === 'month' ? 'Месяц' : 'Год'}
            </button>
          ))}
        </div>
      </div>

      {/* KPI Карточки */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div onClick={() => navigate('/hours-calendar')} className="card p-5 cursor-pointer hover:shadow-md hover:border-blue-300 dark:hover:border-blue-700 transition-all group">
          <div className="text-xs font-bold text-blue-600 dark:text-blue-400 uppercase mb-2">⏱ Всего часов</div>
          <div className="text-3xl font-black text-slate-900 dark:text-slate-100 group-hover:text-blue-600 dark:group-hover:text-blue-400 transition-colors">{formatHours(totalHours)}</div>
          <div className="text-[10px] text-slate-400 mt-1">за период → Календарь</div>
        </div>
        <div onClick={() => navigate('/expenses')} className="card p-5 cursor-pointer hover:shadow-md hover:border-orange-300 dark:hover:border-orange-700 transition-all group">
          <div className="text-xs font-bold text-orange-600 dark:text-orange-400 uppercase mb-2">💸 Расходы</div>
          <div className="text-3xl font-black text-slate-900 dark:text-slate-100 group-hover:text-orange-600 dark:group-hover:text-orange-400 transition-colors">
            {totalExpensesAmount > 0 ? `${(totalExpensesAmount / 1000).toFixed(0)}K ₽` : '—'}
          </div>
          <div className="text-[10px] text-slate-400 mt-1">за период → Список</div>
        </div>
        <div onClick={() => navigate('/projects')} className="card p-5 cursor-pointer hover:shadow-md hover:border-green-300 dark:hover:border-green-700 transition-all group">
          <div className="text-xs font-bold text-green-600 dark:text-green-400 uppercase mb-2">📁 Проекты</div>
          <div className="text-3xl font-black text-slate-900 dark:text-slate-100 group-hover:text-green-600 dark:group-hover:text-green-400 transition-colors">{activeProjectsCount}</div>
          <div className="text-[10px] text-slate-400 mt-1">активных → Список</div>
        </div>
        <div onClick={() => navigate('/admin')} className="card p-5 cursor-pointer hover:shadow-md hover:border-purple-300 dark:hover:border-purple-700 transition-all group">
          <div className="text-xs font-bold text-purple-600 dark:text-purple-400 uppercase mb-2">👥 Сотрудники</div>
          <div className="text-3xl font-black text-slate-900 dark:text-slate-100 group-hover:text-purple-600 dark:group-hover:text-purple-400 transition-colors">{employeesCount}</div>
          <div className="text-[10px] text-slate-400 mt-1">в системе → Доступы</div>
        </div>
      </div>

      {/* Топ проектов + Топ сотрудников */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Топ проектов */}
        <div className="card p-5">
          <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-4 flex items-center gap-2">
            <span className="w-1.5 h-5 bg-indigo-500 rounded-full"></span>
            🏆 Топ проектов по часам
          </h3>
          {projectHours.length === 0 ? (
            <div className="text-center py-8 text-slate-400 dark:text-slate-500 text-sm">Нет данных за период</div>
          ) : (
            <div className="space-y-3">
              {projectHours.map((p, i) => {
                const pct = (p.hours / maxProjectHours) * 100;
                return (
                  <div key={i}>
                    <div className="flex items-center justify-between mb-1">
                      <div className="flex items-center gap-2 min-w-0">
                        <span className="text-xs font-bold text-slate-400 w-5">#{i + 1}</span>
                        <span className="text-sm font-medium text-slate-900 dark:text-slate-100 truncate">{p.name}</span>
                      </div>
                      <span className="text-sm font-bold text-indigo-600 dark:text-indigo-400 whitespace-nowrap ml-2">{formatHours(p.hours)}</span>
                    </div>
                    <div className="h-2 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                      <div className="h-full bg-gradient-to-r from-indigo-500 to-violet-500 rounded-full transition-all" style={{ width: `${pct}%` }} />
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Топ сотрудников */}
        <div className="card p-5">
          <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-4 flex items-center gap-2">
            <span className="w-1.5 h-5 bg-emerald-500 rounded-full"></span>
            👥 Топ сотрудников по часам
          </h3>
          {userHours.length === 0 ? (
            <div className="text-center py-8 text-slate-400 dark:text-slate-500 text-sm">Нет данных за период</div>
          ) : (
            <div className="space-y-3">
              {userHours.map((u, i) => {
                const pct = (u.hours / maxUserHours) * 100;
                return (
                  <div key={i}>
                    <div className="flex items-center justify-between mb-1">
                      <div className="flex items-center gap-2 min-w-0">
                        <span className="text-xs font-bold text-slate-400 w-5">#{i + 1}</span>
                        <span className="text-sm font-medium text-slate-900 dark:text-slate-100 truncate">{u.name}</span>
                      </div>
                      <span className="text-sm font-bold text-emerald-600 dark:text-emerald-400 whitespace-nowrap ml-2">{formatHours(u.hours)}</span>
                    </div>
                    <div className="h-2 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                      <div className="h-full bg-gradient-to-r from-emerald-500 to-teal-500 rounded-full transition-all" style={{ width: `${pct}%` }} />
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      {/* Распределение по дням недели + Расходы по типам */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Часы по дням недели */}
        <div className="card p-5">
          <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-4 flex items-center gap-2">
            <span className="w-1.5 h-5 bg-blue-500 rounded-full"></span>
            📆 Распределение по дням недели
          </h3>
          <div className="flex items-end gap-2 h-40">
            {hoursByWeekday.map((hours, i) => {
              const pct = (hours / maxWeekdayHours) * 100;
              const isWknd = i >= 5;
              return (
                <div key={i} className="flex-1 flex flex-col items-center gap-1">
                  <div className="text-xs font-bold text-slate-600 dark:text-slate-400">
                    {hours > 0 ? formatHours(hours) : '—'}
                  </div>
                  <div className="flex-1 w-full flex items-end">
                    <div className={`w-full rounded-t transition-all ${isWknd ? 'bg-red-400 dark:bg-red-600' : 'bg-gradient-to-t from-blue-500 to-indigo-400'}`}
                      style={{ height: `${pct}%`, minHeight: hours > 0 ? '4px' : '0' }} />
                  </div>
                  <div className={`text-xs font-semibold ${isWknd ? 'text-red-500' : 'text-slate-600 dark:text-slate-400'}`}>
                    {weekdayNames[i]}
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* Расходы по типам */}
        <div className="card p-5">
          <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-4 flex items-center gap-2">
            <span className="w-1.5 h-5 bg-orange-500 rounded-full"></span>
            💸 Расходы по типам
          </h3>
          {expensesByType.length === 0 ? (
            <div className="text-center py-8 text-slate-400 dark:text-slate-500 text-sm">Нет расходов за период</div>
          ) : (
            <div className="space-y-3">
              {expensesByType.map(([type, amount]) => {
                const pct = (amount / maxExpenseType) * 100;
                return (
                  <div key={type}>
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-sm font-medium text-slate-900 dark:text-slate-100">{EXPENSE_TYPE_LABELS[type] || type}</span>
                      <span className="text-sm font-bold text-orange-600 dark:text-orange-400 whitespace-nowrap ml-2">{formatMoney(amount)}</span>
                    </div>
                    <div className="h-2 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                      <div className="h-full bg-gradient-to-r from-orange-500 to-amber-400 rounded-full transition-all" style={{ width: `${pct}%` }} />
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      {/* Сводка */}
      <div className="card p-5 bg-gradient-to-r from-indigo-50 to-violet-50 dark:from-indigo-950/20 dark:to-violet-950/20 border-indigo-200 dark:border-indigo-900">
        <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-3 flex items-center gap-2">
          <span className="w-1.5 h-5 bg-indigo-500 rounded-full"></span>
          📊 Сводка за период
        </h3>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
          <div>
            <div className="text-xs text-slate-500 dark:text-slate-400 uppercase font-bold">Среднее в день</div>
            <div className="text-lg font-bold text-slate-900 dark:text-slate-100">
              {periodEntries.length > 0 ? formatHours(totalHours / new Set(periodEntries.map(e => e.date)).size) : '—'}
            </div>
          </div>
          <div>
            <div className="text-xs text-slate-500 dark:text-slate-400 uppercase font-bold">Уникальных проектов</div>
            <div className="text-lg font-bold text-slate-900 dark:text-slate-100">
              {new Set(periodEntries.map(e => e.projectId)).size}
            </div>
          </div>
          <div>
            <div className="text-xs text-slate-500 dark:text-slate-400 uppercase font-bold">Активных сотрудников</div>
            <div className="text-lg font-bold text-slate-900 dark:text-slate-100">
              {new Set(periodEntries.map(e => e.userId)).size}
            </div>
          </div>
          <div>
            <div className="text-xs text-slate-500 dark:text-slate-400 uppercase font-bold">Средний расход</div>
            <div className="text-lg font-bold text-slate-900 dark:text-slate-100">
              {periodExpenses.length > 0 ? formatMoney(totalExpensesAmount / periodExpenses.length) : '—'}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}