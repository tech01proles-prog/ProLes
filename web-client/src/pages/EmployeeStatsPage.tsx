import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/client';
import type { TimeEntryDto, ExpenseDto, IncomeDto, ProjectDto } from '../types';
import { formatMoney } from '../lib/utils';

export function EmployeeStatsPage() {
  const navigate = useNavigate();
  const [entries, setEntries] = useState<TimeEntryDto[]>([]);
  const [expenses, setExpenses] = useState<ExpenseDto[]>([]);
  const [incomes, setIncomes] = useState<IncomeDto[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [period, setPeriod] = useState<'week' | 'month' | 'year'>('month');

  // Получаем текущего пользователя
  const [userId, setUserId] = useState<string | null>(null);
  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) {
      try {
        const u = JSON.parse(stored);
        setUserId(u.id);
      } catch {}
    }
  }, []);

  useEffect(() => {
    (async () => {
      if (!userId) return;
      setLoading(true);
      const [eRes, exRes, incRes, pRes] = await Promise.allSettled([
        api.get<TimeEntryDto[]>(`/entries?userId=${userId}`),
        api.get<ExpenseDto[]>(`/expenses?userId=${userId}`),
        api.get<IncomeDto[]>(`/incomes?userId=${userId}`),
        api.get<ProjectDto[]>('/projects'),
      ]);
      setEntries(eRes.status === 'fulfilled' ? eRes.value.data : []);
      setExpenses(exRes.status === 'fulfilled' ? exRes.value.data : []);
      setIncomes(incRes.status === 'fulfilled' ? incRes.value.data : []);
      setProjects(pRes.status === 'fulfilled' ? pRes.value.data.filter(p => p.isActive) : []);
      setLoading(false);
    })();
  }, [userId]);

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
  const periodIncomes = useMemo(() => incomes.filter(e => e.date >= cutoffDate), [incomes, cutoffDate]);

  // KPI
  const totalHours = periodEntries.reduce((s, e) => s + e.hours, 0);
  const totalExpensesAmount = periodExpenses.reduce((s, e) => s + e.amount, 0);
  const totalIncomesAmount = periodIncomes.reduce((s, e) => s + e.amount, 0);
  const workDays = new Set(periodEntries.map(e => e.date)).size;
  const activeProjectsCount = new Set(periodEntries.map(e => e.projectId)).size;

  // Расходы по типам
  const expensesByType = useMemo(() => {
    const map = new Map<string, number>();
    periodExpenses.forEach(e => {
      const key = e.type === 'ROAD' ? '🚗 Дорога' : `📦 ${e.name || 'Другое'}`;
      map.set(key, (map.get(key) || 0) + e.amount);
    });
    return [...map.entries()].sort((a, b) => b[1] - a[1]);
  }, [periodExpenses]);

  // Доходы по проектам
  const incomesByProject = useMemo(() => {
    const map = new Map<string, { name: string; amount: number }>();
    periodIncomes.forEach(i => {
      const name = i.projectName || projects.find(p => p.id === i.projectId)?.name || 'Без проекта';
      const cur = map.get(i.projectId || '') || { name, amount: 0 };
      cur.amount += i.amount;
      map.set(i.projectId || '', cur);
    });
    return [...map.values()].sort((a, b) => b.amount - a.amount);
  }, [periodIncomes, projects]);

  // Часы по проектам
  const hoursByProject = useMemo(() => {
    const map = new Map<string, { name: string; hours: number }>();
    periodEntries.forEach(e => {
      const name = projects.find(p => p.id === e.projectId)?.name || 'Без проекта';
      const cur = map.get(e.projectId || '') || { name, hours: 0 };
      cur.hours += e.hours;
      map.set(e.projectId || '', cur);
    });
    return [...map.values()].sort((a, b) => b.hours - a.hours);
  }, [periodEntries, projects]);

  // Сравнение доходов и расходов по проектам
  const projectComparison = useMemo(() => {
    const projectIds = new Set([...periodIncomes.map(i => i.projectId), ...periodExpenses.map(e => e.projectId)].filter(Boolean) as string[]);
    return [...projectIds].map(pid => {
      const name = projects.find(p => p.id === pid)?.name || 'Без проекта';
      const income = periodIncomes.filter(i => i.projectId === pid).reduce((s, i) => s + i.amount, 0);
      const expense = periodExpenses.filter(e => e.projectId === pid).reduce((s, e) => s + e.amount, 0);
      return { name, income, expense };
    }).sort((a, b) => b.income - a.income);
  }, [periodIncomes, periodExpenses, projects]);

  const formatHours = (h: number) => {
    const hrs = Math.floor(h);
    const mins = Math.round((h - hrs) * 60);
    return mins > 0 ? `${hrs}ч ${mins}м` : `${hrs}ч`;
  };

  if (loading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;

  return (
    <div className="space-y-6 max-w-5xl mx-auto">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">📊 Моя статистика</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">{periodEntries.length} записей • {periodExpenses.length} расходов</p>
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
      <div className="grid grid-cols-2 lg:grid-cols-3 gap-3">
        <div className="card p-4">
          <div className="text-xs font-bold text-green-600 dark:text-green-400 uppercase mb-2">⏱ Часы</div>
          <div className="text-2xl font-black text-slate-900 dark:text-slate-100">{formatHours(totalHours)}</div>
          <div className="text-[10px] text-slate-400 mt-1">за период</div>
        </div>
        <div className="card p-4">
          <div className="text-xs font-bold text-emerald-600 dark:text-emerald-400 uppercase mb-2">💰 Доходы</div>
          <div className="text-2xl font-black text-slate-900 dark:text-slate-100">{formatMoney(totalIncomesAmount)}</div>
          <div className="text-[10px] text-slate-400 mt-1">за период</div>
        </div>
        <div className="card p-4">
          <div className="text-xs font-bold text-orange-600 dark:text-orange-400 uppercase mb-2">💸 Расходы</div>
          <div className="text-2xl font-black text-slate-900 dark:text-slate-100">{formatMoney(totalExpensesAmount)}</div>
          <div className="text-[10px] text-slate-400 mt-1">за период</div>
        </div>
        <div className="card p-4">
          <div className="text-xs font-bold text-blue-600 dark:text-blue-400 uppercase mb-2">📁 Проекты</div>
          <div className="text-2xl font-black text-slate-900 dark:text-slate-100">{activeProjectsCount}</div>
          <div className="text-[10px] text-slate-400 mt-1">активных</div>
        </div>
        <div className="card p-4">
          <div className="text-xs font-bold text-indigo-600 dark:text-indigo-400 uppercase mb-2">📅 Дни</div>
          <div className="text-2xl font-black text-slate-900 dark:text-slate-100">{workDays}</div>
          <div className="text-[10px] text-slate-400 mt-1">с записями</div>
        </div>
        <div className="card p-4">
          <div className="text-xs font-bold uppercase mb-2" style={{ color: totalIncomesAmount >= totalExpensesAmount ? '#2E7D32' : '#C62828' }}>📈 Баланс</div>
          <div className="text-2xl font-black text-slate-900 dark:text-slate-100" style={{ color: totalIncomesAmount >= totalExpensesAmount ? '#2E7D32' : '#C62828' }}>{formatMoney(totalIncomesAmount - totalExpensesAmount)}</div>
          <div className="text-[10px] text-slate-400 mt-1">доход - расход</div>
        </div>
      </div>

      {/* Сравнение доходов и расходов по проектам */}
      {projectComparison.length > 0 && (
        <div className="card p-5">
          <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-4 flex items-center gap-2">
            <span className="w-1.5 h-5 bg-purple-500 rounded-full"></span>
            💰 Доходы vs Расходы по проектам
          </h3>
          <div className="space-y-3">
            {projectComparison.map((p, i) => (
              <div key={i} className="border-b border-slate-100 dark:border-slate-800 pb-3 last:border-0 last:pb-0">
                <div className="font-medium text-slate-900 dark:text-slate-100 mb-2">{p.name}</div>
                <div className="grid grid-cols-3 gap-2 text-sm">
                  <div>
                    <div className="text-xs text-slate-400">Доход</div>
                    <div className="font-bold text-emerald-600 dark:text-emerald-400">{formatMoney(p.income)}</div>
                  </div>
                  <div>
                    <div className="text-xs text-slate-400">Расход</div>
                    <div className="font-bold text-orange-600 dark:text-orange-400">{formatMoney(p.expense)}</div>
                  </div>
                  <div>
                    <div className="text-xs text-slate-400">Баланс</div>
                    <div className={`font-bold ${p.income - p.expense >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'}`}>{formatMoney(p.income - p.expense)}</div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Доходы по проектам */}
      {incomesByProject.length > 0 && (
        <div className="card p-5">
          <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-4 flex items-center gap-2">
            <span className="w-1.5 h-5 bg-emerald-500 rounded-full"></span>
            📈 Доходы по проектам
          </h3>
          <div className="space-y-3">
            {incomesByProject.map((p, i) => {
              const maxAmount = incomesByProject[0]?.amount || 1;
              const pct = (p.amount / maxAmount) * 100;
              return (
                <div key={i}>
                  <div className="flex items-center justify-between mb-1">
                    <span className="text-sm font-medium text-slate-900 dark:text-slate-100 truncate">{p.name}</span>
                    <span className="text-sm font-bold text-emerald-600 dark:text-emerald-400 whitespace-nowrap ml-2">{formatMoney(p.amount)}</span>
                  </div>
                  <div className="h-2 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                    <div className="h-full bg-gradient-to-r from-emerald-500 to-teal-500 rounded-full transition-all" style={{ width: `${pct}%` }} />
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Часы по проектам */}
      {hoursByProject.length > 0 && (
        <div className="card p-5">
          <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-4 flex items-center gap-2">
            <span className="w-1.5 h-5 bg-blue-500 rounded-full"></span>
            ⏱ Часы по проектам
          </h3>
          <div className="space-y-3">
            {hoursByProject.map((p, i) => {
              const maxHours = hoursByProject[0]?.hours || 1;
              const pct = (p.hours / maxHours) * 100;
              return (
                <div key={i}>
                  <div className="flex items-center justify-between mb-1">
                    <span className="text-sm font-medium text-slate-900 dark:text-slate-100 truncate">{p.name}</span>
                    <span className="text-sm font-bold text-blue-600 dark:text-blue-400 whitespace-nowrap ml-2">{formatHours(p.hours)}</span>
                  </div>
                  <div className="h-2 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                    <div className="h-full bg-gradient-to-r from-blue-500 to-indigo-400 rounded-full transition-all" style={{ width: `${pct}%` }} />
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Расходы по типам */}
      {expensesByType.length > 0 && (
        <div className="card p-5">
          <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-4 flex items-center gap-2">
            <span className="w-1.5 h-5 bg-orange-500 rounded-full"></span>
            💸 Расходы по категориям
          </h3>
          <div className="space-y-3">
            {expensesByType.map(([type, amount], i) => {
              const maxAmount = expensesByType[0]?.[1] || 1;
              const pct = (amount / maxAmount) * 100;
              return (
                <div key={i}>
                  <div className="flex items-center justify-between mb-1">
                    <span className="text-sm font-medium text-slate-900 dark:text-slate-100">{type}</span>
                    <span className="text-sm font-bold text-orange-600 dark:text-orange-400 whitespace-nowrap ml-2">{formatMoney(amount)}</span>
                  </div>
                  <div className="h-2 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                    <div className="h-full bg-gradient-to-r from-orange-500 to-amber-400 rounded-full transition-all" style={{ width: `${pct}%` }} />
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
