import { useEffect, useMemo, useState } from 'react';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import api from '../api/client';
import type { EmployeeHoursEntry, ExpenseDto, IncomeDto, PayrollExportResponse, ProjectDto, UserDto } from '../types';
import { formatMoney } from '../lib/utils';
import './AnalyticsPage.css';

const RUB_RATES: Record<string, number> = { RUB: 1, USD: 92, EUR: 100, BYN: 28 };
const CHART_COLORS = ['#10b981', '#14b8a6', '#06b6d4', '#3b82f6', '#6366f1', '#8b5cf6', '#f59e0b', '#f97316', '#ef4444', '#84cc16'];

type AnalyticsTab = 'overview' | 'employees' | 'expenses' | 'projects';

interface EmployeeMetric {
  userId: string;
  name: string;
  position: string;
  hours: number;
  workDays: number;
  projects: number;
  expenseRub: number;
  incomeRub: number;
  noReceiptRub: number;
  incomePerHour: number;
  expensePerHour: number;
  balanceRub: number;
}

interface ProjectMetric {
  projectId: string;
  name: string;
  revenueRub: number;
  expenseRub: number;
  plannedExpenseRub: number;
  marginRub: number;
  marginPct: number;
  hours: number;
  expensePerHour: number;
  employeeCount: number;
}

function rub(amount: number, currency: string) {
  return amount * (RUB_RATES[currency] || 1);
}

function monthKey(date: string) {
  const d = new Date(date);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function fmtMonth(key: string) {
  const [y, m] = key.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('ru-RU', { month: 'short', year: 'numeric' });
}

function clamp(n: number, min: number, max: number) {
  return Math.min(max, Math.max(min, n));
}

export function AnalyticsPage() {
  const [expenses, setExpenses] = useState<ExpenseDto[]>([]);
  const [incomes, setIncomes] = useState<IncomeDto[]>([]);
  const [entries, setEntries] = useState<EmployeeHoursEntry[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [users, setUsers] = useState<UserDto[]>([]);
  const [payroll, setPayroll] = useState<PayrollExportResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<AnalyticsTab>('overview');
  const [periodMonths, setPeriodMonths] = useState<6 | 12>(6);
  const [selectedMonth, setSelectedMonth] = useState<string>('');
  const [selectedProjectId, setSelectedProjectId] = useState<string>('all');
  const [sortEmployees, setSortEmployees] = useState<'efficiency' | 'hours' | 'expenses'>('efficiency');

  useEffect(() => {
    void loadData();
  }, []);

  async function loadData() {
    setLoading(true);
    try {
      const [expensesRes, incomesRes, entriesRes, projectsRes, usersRes] = await Promise.all([
        api.get<ExpenseDto[]>('/expenses/all'),
        api.get<IncomeDto[]>('/incomes/all'),
        api.get<EmployeeHoursEntry[]>('/entries/all'),
        api.get<ProjectDto[]>('/projects'),
        api.get<UserDto[]>('/users'),
      ]);

      setExpenses(expensesRes.data || []);
      setIncomes(incomesRes.data || []);
      setEntries(entriesRes.data || []);
      setProjects(projectsRes.data || []);
      setUsers(usersRes.data || []);

      try {
        const now = new Date();
        const payrollRes = await api.get<PayrollExportResponse>('/payroll/export', {
          params: { year: now.getFullYear(), month: now.getMonth() + 1 },
        });
        setPayroll(payrollRes.data);
      } catch {
        setPayroll(null);
      }
    } catch (error) {
      console.error('Ошибка загрузки аналитики:', error);
    } finally {
      setLoading(false);
    }
  }

  const allDates = useMemo(
    () => [...expenses.map(x => x.date), ...incomes.map(x => x.date), ...entries.map(x => x.date)].filter(Boolean).sort(),
    [expenses, incomes, entries],
  );

  const availableMonths = useMemo(() => {
    const set = new Set(allDates.map(monthKey));
    const now = new Date();
    set.add(monthKey(now.toISOString()));
    return Array.from(set).sort().slice(-12);
  }, [allDates]);

  useEffect(() => {
    if (!selectedMonth) setSelectedMonth(availableMonths[availableMonths.length - 1] || monthKey(new Date().toISOString()));
  }, [availableMonths, selectedMonth]);

  const period = useMemo(() => {
    const end = new Date();
    end.setDate(1);
    end.setMonth(end.getMonth() + 1);
    const start = new Date(end);
    start.setMonth(start.getMonth() - periodMonths);
    const from = start.toISOString().slice(0, 10);
    const to = new Date(end.getTime() - 1).toISOString().slice(0, 10);
    return { from, to };
  }, [periodMonths]);

  const filteredExpenses = useMemo(
    () => expenses.filter(x => x.date >= period.from && x.date <= period.to && (selectedProjectId === 'all' || x.projectId === selectedProjectId)),
    [expenses, period, selectedProjectId],
  );
  const filteredIncomes = useMemo(
    () => incomes.filter(x => x.date >= period.from && x.date <= period.to && (selectedProjectId === 'all' || x.projectId === selectedProjectId)),
    [incomes, period, selectedProjectId],
  );
  const filteredEntries = useMemo(
    () => entries.filter(x => x.date >= period.from && x.date <= period.to && (selectedProjectId === 'all' || x.projectId === selectedProjectId)),
    [entries, period, selectedProjectId],
  );

  const totals = useMemo(() => {
    const expenseRub = filteredExpenses.reduce((s, x) => s + rub(x.amount, x.currency), 0);
    const incomeRub = filteredIncomes.reduce((s, x) => s + rub(x.amount, x.currency), 0);
    const hours = filteredEntries.reduce((s, x) => s + Number(x.hours || 0), 0);
    const noReceiptRub = filteredExpenses
      .filter(x => !x.receiptSubmitted && !x.hasReceiptPhoto)
      .reduce((s, x) => s + rub(x.amount, x.currency), 0);
    return {
      expenseRub,
      incomeRub,
      balanceRub: incomeRub - expenseRub,
      hours,
      noReceiptRub,
      expensePerHour: hours ? expenseRub / hours : 0,
      incomePerHour: hours ? incomeRub / hours : 0,
    };
  }, [filteredExpenses, filteredIncomes, filteredEntries]);

  const monthlyTrend = useMemo(() => {
    const months: string[] = [];
    const cursor = new Date(period.from);
    while (cursor.toISOString().slice(0, 10) <= period.to) {
      months.push(monthKey(cursor.toISOString()));
      cursor.setMonth(cursor.getMonth() + 1);
    }
    return months.map(key => {
      const e = filteredExpenses.filter(x => monthKey(x.date) === key).reduce((s, x) => s + rub(x.amount, x.currency), 0);
      const i = filteredIncomes.filter(x => monthKey(x.date) === key).reduce((s, x) => s + rub(x.amount, x.currency), 0);
      const h = filteredEntries.filter(x => monthKey(x.date) === key).reduce((s, x) => s + Number(x.hours || 0), 0);
      return {
        month: fmtMonth(key),
        expense: Math.round(e),
        income: Math.round(i),
        balance: Math.round(i - e),
        hours: Math.round(h * 10) / 10,
      };
    });
  }, [period, filteredExpenses, filteredIncomes, filteredEntries]);

  const employeeMetrics = useMemo((): EmployeeMetric[] => {
    const map = new Map<string, EmployeeMetric>();
    const ensure = (id: string) => {
      const user = users.find(u => u.id === id);
      if (!map.has(id)) {
        map.set(id, {
          userId: id,
          name: user?.name || `${user?.lastName || ''} ${user?.firstName || ''}`.trim() || 'Неизвестный',
          position: user?.position || '—',
          hours: 0,
          workDays: 0,
          projects: 0,
          expenseRub: 0,
          incomeRub: 0,
          noReceiptRub: 0,
          incomePerHour: 0,
          expensePerHour: 0,
          balanceRub: 0,
        });
      }
      return map.get(id)!;
    };

    filteredEntries.forEach(x => ensure(x.userId).hours += Number(x.hours || 0));
    filteredEntries.forEach(x => {
      const m = ensure(x.userId);
      // Собираем уникальные рабочие дни и проекты ниже через Set.
      m.workDays = 0;
    });
    filteredExpenses.forEach(x => {
      const m = ensure(x.userId);
      m.expenseRub += rub(x.amount, x.currency);
      if (!x.receiptSubmitted && !x.hasReceiptPhoto) m.noReceiptRub += rub(x.amount, x.currency);
    });
    filteredIncomes.forEach(x => ensure(x.userId).incomeRub += rub(x.amount, x.currency));

    const daySets = new Map<string, Set<string>>();
    const projectSets = new Map<string, Set<string>>();
    filteredEntries.forEach(x => {
      if (!daySets.has(x.userId)) daySets.set(x.userId, new Set());
      if (!projectSets.has(x.userId)) projectSets.set(x.userId, new Set());
      daySets.get(x.userId)!.add(x.date);
      projectSets.get(x.userId)!.add(x.projectId);
    });
    map.forEach(m => {
      m.workDays = daySets.get(m.userId)?.size || 0;
      m.projects = projectSets.get(m.userId)?.size || 0;
      m.incomePerHour = m.hours ? m.incomeRub / m.hours : 0;
      m.expensePerHour = m.hours ? m.expenseRub / m.hours : 0;
      m.balanceRub = m.incomeRub - m.expenseRub;
    });

    return Array.from(map.values()).filter(x => x.hours > 0 || x.expenseRub > 0 || x.incomeRub > 0);
  }, [filteredEntries, filteredExpenses, filteredIncomes, users]);

  const sortedEmployees = useMemo(() => {
    return [...employeeMetrics].sort((a, b) => {
      if (sortEmployees === 'hours') return b.hours - a.hours;
      if (sortEmployees === 'expenses') return b.expenseRub - a.expenseRub;
      const scoreA = a.incomePerHour - a.expensePerHour;
      const scoreB = b.incomePerHour - b.expensePerHour;
      return scoreB - scoreA;
    });
  }, [employeeMetrics, sortEmployees]);

  const expenseCategories = useMemo(() => {
    const map = new Map<string, number>();
    filteredExpenses.forEach(x => {
      const key = x.subcategory || x.type || x.name || 'Прочее';
      map.set(key, (map.get(key) || 0) + rub(x.amount, x.currency));
    });
    return Array.from(map, ([name, value]) => ({ name, value: Math.round(value) }))
      .sort((a, b) => b.value - a.value)
      .slice(0, 10);
  }, [filteredExpenses]);

  const receiptData = useMemo(() => {
    let withReceipt = 0;
    let withoutReceipt = 0;
    filteredExpenses.forEach(x => {
      const amount = rub(x.amount, x.currency);
      if (x.receiptSubmitted || x.hasReceiptPhoto) withReceipt += amount;
      else withoutReceipt += amount;
    });
    return [
      { name: 'С чеком', value: Math.round(withReceipt) },
      { name: 'Без чека', value: Math.round(withoutReceipt) },
    ];
  }, [filteredExpenses]);

  const projectMetrics = useMemo((): ProjectMetric[] => {
    const map = new Map<string, ProjectMetric>();
    for (const p of projects) {
      const revenue = rub(Number(p.revenue || p.sellingPrice || 0), 'RUB');
      map.set(p.id, {
        projectId: p.id,
        name: p.name,
        revenueRub: revenue,
        expenseRub: 0,
        plannedExpenseRub: rub(Number(p.productionCost || 0), 'RUB'),
        marginRub: revenue,
        marginPct: revenue ? 100 : 0,
        hours: 0,
        expensePerHour: 0,
        employeeCount: 0,
      });
    }
    filteredExpenses.forEach(x => {
      if (!map.has(x.projectId)) {
        map.set(x.projectId, {
          projectId: x.projectId,
          name: x.projectName || 'Без проекта',
          revenueRub: 0,
          expenseRub: 0,
          plannedExpenseRub: 0,
          marginRub: -rub(x.amount, x.currency),
          marginPct: 0,
          hours: 0,
          expensePerHour: 0,
          employeeCount: 0,
        });
      }
      map.get(x.projectId)!.expenseRub += rub(x.amount, x.currency);
    });
    filteredEntries.forEach(x => {
      const m = map.get(x.projectId);
      if (m) m.hours += Number(x.hours || 0);
    });
    const people = new Map<string, Set<string>>();
    filteredEntries.forEach(x => {
      if (!people.has(x.projectId)) people.set(x.projectId, new Set());
      people.get(x.projectId)!.add(x.userId);
    });
    map.forEach(m => {
      m.marginRub = m.revenueRub - m.expenseRub;
      m.marginPct = m.revenueRub ? (m.marginRub / m.revenueRub) * 100 : 0;
      m.expensePerHour = m.hours ? m.expenseRub / m.hours : 0;
      m.employeeCount = people.get(m.projectId)?.size || 0;
    });
    return Array.from(map.values()).sort((a, b) => b.expenseRub - a.expenseRub);
  }, [projects, filteredExpenses, filteredEntries]);

  const signals = useMemo(() => {
    const out: { tone: 'good' | 'warn' | 'bad'; title: string; text: string }[] = [];
    const avgExpensePerHour = totals.hours ? totals.expenseRub / totals.hours : 0;
    if (totals.noReceiptRub > 0) {
      out.push({
        tone: totals.noReceiptRub > totals.expenseRub * 0.25 ? 'bad' : 'warn',
        title: 'Контроль чеков',
        text: `${formatMoney(Math.round(totals.noReceiptRub), 'RUB')} расходов без подтверждения (${totals.expenseRub ? Math.round(totals.noReceiptRub / totals.expenseRub * 100) : 0}%).`,
      });
    }
    if (avgExpensePerHour > 0) {
      out.push({
        tone: avgExpensePerHour > (totals.incomePerHour || avgExpensePerHour) ? 'warn' : 'good',
        title: 'Экономика часа',
        text: `Расход на рабочий час — ${formatMoney(Math.round(avgExpensePerHour), 'RUB')}, доход — ${formatMoney(Math.round(totals.incomePerHour), 'RUB')}.`,
      });
    }
    const topProject = projectMetrics.find(p => p.expenseRub > 0);
    if (topProject) {
      out.push({
        tone: topProject.marginPct < 0 ? 'bad' : topProject.marginPct < 15 ? 'warn' : 'good',
        title: 'Главный финансовый драйвер',
        text: `${topProject.name}: ${formatMoney(Math.round(topProject.expenseRub), 'RUB')} расходов, маржа ${Math.round(topProject.marginPct)}%.`,
      });
    }
    const best = [...employeeMetrics].filter(x => x.hours >= 10).sort((a, b) => (b.incomePerHour - b.expensePerHour) - (a.incomePerHour - a.expensePerHour))[0];
    if (best) {
      out.push({
        tone: 'good',
        title: 'Лидер эффективности',
        text: `${best.name}: ${formatMoney(Math.round(best.incomePerHour - best.expensePerHour), 'RUB')} чистого эффекта на час.`,
      });
    }
    return out;
  }, [totals, projectMetrics, employeeMetrics]);

  const selectedPayroll = useMemo(() => {
    if (!payroll || payroll.year !== Number(selectedMonth.slice(0, 4)) || payroll.month !== Number(selectedMonth.slice(5, 7))) return null;
    return new Map(payroll.employees.map(x => [x.userId, x]));
  }, [payroll, selectedMonth]);

  if (loading) {
    return <div className="flex items-center justify-center h-64"><div className="animate-spin rounded-full h-12 w-12 border-b-2 border-emerald-500" /></div>;
  }

  const tabs: { id: AnalyticsTab; label: string; icon: string }[] = [
    { id: 'overview', label: 'Обзор', icon: '◈' },
    { id: 'employees', label: 'Сотрудники', icon: '◎' },
    { id: 'expenses', label: 'Расходы', icon: '◌' },
    { id: 'projects', label: 'Проекты', icon: '▦' },
  ];

  return (
    <div className="analytics-page p-6 space-y-6">
      <div className="analytics-hero rounded-3xl p-6 md:p-8 text-white shadow-xl">
        <div className="relative z-10">
          <div className="flex flex-wrap items-start justify-between gap-5">
            <div>
              <span className="text-emerald-100 text-xs font-bold uppercase tracking-[0.18em]">ProLes Intelligence</span>
              <h1 className="mt-2 text-3xl md:text-4xl font-black tracking-tight">Аналитический центр</h1>
              <p className="mt-2 max-w-2xl text-emerald-50/90">Эффективность команды, экономика проектов и контроль расходов в одном экране.</p>
            </div>
            <button onClick={() => void loadData()} className="analytics-glass-button px-4 py-2.5 rounded-xl text-sm font-semibold">↻ Обновить</button>
          </div>
          <div className="mt-7 flex flex-wrap items-center gap-3">
            <div className="analytics-period-group">
              <button onClick={() => setPeriodMonths(6)} className={periodMonths === 6 ? 'active' : ''}>6 мес.</button>
              <button onClick={() => setPeriodMonths(12)} className={periodMonths === 12 ? 'active' : ''}>12 мес.</button>
            </div>
            <select value={selectedMonth} onChange={e => setSelectedMonth(e.target.value)} className="analytics-select">
              {availableMonths.map(m => <option key={m} value={m}>{fmtMonth(m)}</option>)}
            </select>
            <select value={selectedProjectId} onChange={e => setSelectedProjectId(e.target.value)} className="analytics-select">
              <option value="all">Все проекты</option>
              {projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </div>
        </div>
      </div>

      <div className="analytics-tabs">
        {tabs.map(t => (
          <button key={t.id} onClick={() => setTab(t.id)} className={tab === t.id ? 'active' : ''}><span>{t.icon}</span>{t.label}</button>
        ))}
      </div>

      <div className="grid grid-cols-2 xl:grid-cols-5 gap-4">
        {[
          ['Оборот', formatMoney(Math.round(totals.incomeRub), 'RUB'), 'за период', 'green'],
          ['Расходы', formatMoney(Math.round(totals.expenseRub), 'RUB'), 'за период', 'slate'],
          ['Баланс', formatMoney(Math.round(totals.balanceRub), 'RUB'), totals.balanceRub >= 0 ? 'положительный эффект' : 'нужен контроль', totals.balanceRub >= 0 ? 'green' : 'red'],
          ['Часы', `${Math.round(totals.hours * 10) / 10} ч`, 'учтено в табеле', 'blue'],
          ['Без чеков', formatMoney(Math.round(totals.noReceiptRub), 'RUB'), `${totals.expenseRub ? Math.round(totals.noReceiptRub / totals.expenseRub * 100) : 0}% расходов`, totals.noReceiptRub ? 'amber' : 'green'],
        ].map(([title, value, hint, tone]) => (
          <div key={title} className={`analytics-kpi tone-${tone} bg-white dark:bg-gray-800 rounded-2xl p-5 shadow-sm border border-gray-100 dark:border-gray-700`}>
            <p className="text-xs uppercase tracking-wider text-gray-500 dark:text-gray-400 font-bold">{title}</p>
            <p className="mt-2 text-2xl font-black text-gray-900 dark:text-white">{value}</p>
            <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{hint}</p>
          </div>
        ))}
      </div>

      {tab === 'overview' && (
        <>
          <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
            <div className="xl:col-span-2 analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
              <div className="flex items-center justify-between gap-3 mb-5">
                <div><h2 className="text-lg font-bold text-gray-900 dark:text-white">Финансовый ритм</h2><p className="text-sm text-gray-500">оборот, расходы и баланс по месяцам</p></div>
                <span className="analytics-badge">TREND</span>
              </div>
              <div className="h-80">
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={monthlyTrend}>
                    <defs>
                      <linearGradient id="incomeFill" x1="0" y1="0" x2="0" y2="1"><stop offset="5%" stopColor="#10b981" stopOpacity={0.28}/><stop offset="95%" stopColor="#10b981" stopOpacity={0}/></linearGradient>
                      <linearGradient id="expenseFill" x1="0" y1="0" x2="0" y2="1"><stop offset="5%" stopColor="#f59e0b" stopOpacity={0.2}/><stop offset="95%" stopColor="#f59e0b" stopOpacity={0}/></linearGradient>
                    </defs>
                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e5e7eb"/>
                    <XAxis dataKey="month" tickLine={false} axisLine={false} />
                    <YAxis tickLine={false} axisLine={false} width={80} tickFormatter={v => `${Math.round(v / 1000)}k`} />
                    <Tooltip formatter={(v: number) => formatMoney(Math.round(v), 'RUB')} />
                    <Legend />
                    <Area type="monotone" dataKey="income" name="Доходы" stroke="#10b981" fill="url(#incomeFill)" strokeWidth={3}/>
                    <Area type="monotone" dataKey="expense" name="Расходы" stroke="#f59e0b" fill="url(#expenseFill)" strokeWidth={3}/>
                    <Area type="monotone" dataKey="balance" name="Баланс" stroke="#3b82f6" fill="none" strokeWidth={2} strokeDasharray="7 5"/>
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            </div>

            <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
              <div className="flex items-center justify-between mb-5"><div><h2 className="text-lg font-bold text-gray-900 dark:text-white">Сигналы</h2><p className="text-sm text-gray-500">автоматические наблюдения</p></div><span className="text-xl">✦</span></div>
              <div className="space-y-3">
                {signals.length === 0 && <div className="analytics-empty">Недостаточно данных для сигналов</div>}
                {signals.map((s, i) => <div key={i} className={`signal-card ${s.tone}`}><div className="signal-icon">{s.tone === 'good' ? '✓' : s.tone === 'warn' ? '!' : '×'}</div><div><p className="font-bold text-sm text-gray-900 dark:text-white">{s.title}</p><p className="text-xs leading-5 text-gray-500 dark:text-gray-400">{s.text}</p></div></div>)}
              </div>
            </div>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
              <div className="flex items-center justify-between mb-5"><div><h2 className="text-lg font-bold text-gray-900 dark:text-white">Где работают деньги</h2><p className="text-sm text-gray-500">топ-10 категорий расходов</p></div><span className="analytics-badge">80/20</span></div>
              <div className="h-72">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={expenseCategories} layout="vertical" margin={{ left: 20, right: 20 }}>
                    <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="#e5e7eb"/>
                    <XAxis type="number" tickLine={false} axisLine={false} tickFormatter={v => `${Math.round(v / 1000)}k`} />
                    <YAxis type="category" dataKey="name" width={110} tickLine={false} axisLine={false} />
                    <Tooltip formatter={(v: number) => formatMoney(Math.round(v), 'RUB')} />
                    <Bar dataKey="value" radius={[0, 8, 8, 0]}>{expenseCategories.map((_, i) => <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]}/>)}</Bar>
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </div>
            <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
              <div className="flex items-center justify-between mb-5"><div><h2 className="text-lg font-bold text-gray-900 dark:text-white">Качество документов</h2><p className="text-sm text-gray-500">структура расходов по чекам</p></div><span className="analytics-badge">{totals.expenseRub ? Math.round((1 - totals.noReceiptRub / totals.expenseRub) * 100) : 0}% OK</span></div>
              <div className="h-72">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie data={receiptData} dataKey="value" nameKey="name" innerRadius={72} outerRadius={105} paddingAngle={4}>
                      {receiptData.map((_, i) => <Cell key={i} fill={i === 0 ? '#10b981' : '#ef4444'} />)}
                    </Pie>
                    <Tooltip formatter={(v: number) => formatMoney(Math.round(v), 'RUB')} />
                    <Legend />
                  </PieChart>
                </ResponsiveContainer>
              </div>
            </div>
          </div>
        </>
      )}

      {tab === 'employees' && (
        <div className="space-y-6">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div><h2 className="text-xl font-black text-gray-900 dark:text-white">Эффективность сотрудников</h2><p className="text-sm text-gray-500">модель = доход/час − расход/час</p></div>
            <div className="analytics-segment">
              {([['efficiency', 'Эффективность'], ['hours', 'Загрузка'], ['expenses', 'Расходы']] as const).map(([k, label]) => <button key={k} onClick={() => setSortEmployees(k)} className={sortEmployees === k ? 'active' : ''}>{label}</button>)}
            </div>
          </div>
          <div className="analytics-table-wrap bg-white dark:bg-gray-800 rounded-2xl shadow-sm overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead><tr><th>Сотрудник</th><th>Часы</th><th>Дни</th><th>Проекты</th><th>Доход/ч</th><th>Расход/ч</th><th>Без чека</th><th>Эффект</th></tr></thead>
                <tbody>
                  {sortedEmployees.map((e, i) => {
                    const effect = e.incomePerHour - e.expensePerHour;
                    const payrollRow = selectedPayroll?.get(e.userId);
                    return <tr key={e.userId}>
                      <td><div className="flex items-center gap-3"><span className={`rank-dot rank-${i + 1}`}>{i + 1}</span><div><p className="font-bold text-gray-900 dark:text-white">{e.name}</p><p className="text-xs text-gray-500">{e.position}</p></div></div></td>
                      <td className="font-semibold">{e.hours.toFixed(1)}</td><td>{e.workDays}</td><td>{e.projects}</td>
                      <td>{formatMoney(Math.round(e.incomePerHour), 'RUB')}</td><td>{formatMoney(Math.round(e.expensePerHour), 'RUB')}</td>
                      <td className={e.noReceiptRub ? 'text-red-500 font-bold' : 'text-emerald-600'}>{formatMoney(Math.round(e.noReceiptRub), 'RUB')}</td>
                      <td><span className={`metric-pill ${effect >= 0 ? 'positive' : 'negative'}`}>{formatMoney(Math.round(effect), 'RUB')}/ч</span>{payrollRow && <div className="text-[10px] text-gray-400 mt-1">ЗП: {formatMoney(Math.round(payrollRow.salaryTotal), 'RUB')}</div>}</td>
                    </tr>;
                  })}
                </tbody>
              </table>
            </div>
            {sortedEmployees.length === 0 && <div className="analytics-empty py-12">Нет данных за выбранный период.</div>}
          </div>
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm"><h3 className="font-bold text-gray-900 dark:text-white">Доход и расход на час</h3><p className="text-xs text-gray-500 mb-4">показывает, кто создаёт максимальный экономический эффект от времени</p><div className="h-80"><ResponsiveContainer width="100%" height="100%"><BarChart data={sortedEmployees.slice(0, 8)}><CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e5e7eb"/><XAxis dataKey="name" tickLine={false} axisLine={false} tickFormatter={v => v.split(' ')[0]} /><YAxis tickLine={false} axisLine={false} tickFormatter={v => `${Math.round(v / 1000)}k`} /><Tooltip formatter={(v: number) => formatMoney(Math.round(v), 'RUB')} /><Legend/><Bar dataKey="incomePerHour" name="Доход/ч" fill="#10b981" radius={[8,8,0,0]}/><Bar dataKey="expensePerHour" name="Расход/ч" fill="#f59e0b" radius={[8,8,0,0]}/></BarChart></ResponsiveContainer></div></div>
            <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm"><h3 className="font-bold text-gray-900 dark:text-white">Баланс по сотрудникам</h3><p className="text-xs text-gray-500 mb-4">доходы минус расходы за выбранный период</p><div className="h-80"><ResponsiveContainer width="100%" height="100%"><BarChart data={sortedEmployees.slice(0, 8)}><CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e5e7eb"/><XAxis dataKey="name" tickLine={false} axisLine={false} tickFormatter={v => v.split(' ')[0]} /><YAxis tickLine={false} axisLine={false} tickFormatter={v => `${Math.round(v / 1000)}k`} /><Tooltip formatter={(v: number) => formatMoney(Math.round(v), 'RUB')} /><Bar dataKey="balanceRub" name="Баланс" fill="#3b82f6" radius={[8,8,0,0]}/></BarChart></ResponsiveContainer></div></div>
          </div>
        </div>
      )}

      {tab === 'expenses' && (
        <div className="space-y-6">
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
            <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm lg:col-span-2">
              <div className="flex justify-between mb-5"><div><h2 className="text-lg font-bold text-gray-900 dark:text-white">Pareto расходов</h2><p className="text-sm text-gray-500">какие статьи создают основную массу затрат</p></div><span className="analytics-badge">TOP 10</span></div>
              <div className="h-80"><ResponsiveContainer width="100%" height="100%"><BarChart data={expenseCategories}><CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e5e7eb"/><XAxis dataKey="name" tickLine={false} axisLine={false} interval={0} angle={-18} textAnchor="end" height={70}/><YAxis tickLine={false} axisLine={false} tickFormatter={v => `${Math.round(v / 1000)}k`}/><Tooltip formatter={(v: number) => formatMoney(Math.round(v), 'RUB')}/><Bar dataKey="value" name="Расход" radius={[8,8,0,0]}>{expenseCategories.map((_, i) => <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]}/>)}</Bar></BarChart></ResponsiveContainer></div>
            </div>
            <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm"><h3 className="font-bold text-gray-900 dark:text-white">Стоп-факторы</h3><div className="space-y-3 mt-4">{signals.filter(s => s.tone !== 'good').map((s, i) => <div key={i} className={`signal-card ${s.tone}`}><div className="signal-icon">!</div><div><p className="font-bold text-sm">{s.title}</p><p className="text-xs text-gray-500 leading-5">{s.text}</p></div></div>)}{signals.filter(s => s.tone !== 'good').length === 0 && <div className="analytics-empty">Критичных отклонений не найдено.</div>}</div></div>
          </div>
          <div className="analytics-table-wrap bg-white dark:bg-gray-800 rounded-2xl shadow-sm overflow-hidden"><div className="p-5 border-b border-gray-100 dark:border-gray-700"><h3 className="font-bold text-gray-900 dark:text-white">Недавние и крупные расходы</h3><p className="text-xs text-gray-500">быстрый аудит операций</p></div><div className="overflow-x-auto"><table className="w-full text-sm"><thead><tr><th>Дата</th><th>Расход</th><th>Проект</th><th>Пользователь</th><th>Чек</th><th>Сумма</th></tr></thead><tbody>{[...filteredExpenses].sort((a,b) => rub(b.amount,b.currency)-rub(a.amount,a.currency)).slice(0, 15).map(e => <tr key={e.id}><td>{new Date(e.date).toLocaleDateString('ru-RU')}</td><td className="font-semibold">{e.name}</td><td>{e.projectName || '—'}</td><td>{users.find(u=>u.id===e.userId)?.name || '—'}</td><td>{e.receiptSubmitted || e.hasReceiptPhoto ? <span className="metric-pill positive">Есть</span> : <span className="metric-pill negative">Нет</span>}</td><td className="font-black">{formatMoney(Math.round(rub(e.amount,e.currency)), 'RUB')}</td></tr>)}</tbody></table></div></div>
        </div>
      )}

      {tab === 'projects' && (
        <div className="space-y-6">
          <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
            <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm"><div className="flex justify-between mb-5"><div><h2 className="text-lg font-bold text-gray-900 dark:text-white">Экономика проектов</h2><p className="text-sm text-gray-500">маржа, затраты и загрузка</p></div><span className="analytics-badge">PORTFOLIO</span></div><div className="space-y-3">{projectMetrics.slice(0, 10).map(p => <div key={p.projectId} className="project-health"><div className="flex items-center justify-between gap-3"><div><p className="font-bold text-gray-900 dark:text-white truncate">{p.name}</p><p className="text-xs text-gray-500">{p.employeeCount} чел. · {p.hours.toFixed(1)} ч.</p></div><div className="text-right"><p className={`font-black ${p.marginPct >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>{Math.round(p.marginPct)}%</p><p className="text-xs text-gray-500">{formatMoney(Math.round(p.expenseRub), 'RUB')}</p></div></div><div className="health-bar"><span style={{ width: `${clamp(p.revenueRub ? p.expenseRub / p.revenueRub * 100 : 100, 0, 100)}%` }}/></div></div>)}</div></div>
            <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm"><h2 className="text-lg font-bold text-gray-900 dark:text-white">Затраты на рабочий час</h2><p className="text-sm text-gray-500 mb-5">сравнение проектов по ресурсоёмкости</p><div className="h-80"><ResponsiveContainer width="100%" height="100%"><BarChart data={projectMetrics.filter(p=>p.hours>0).slice(0,8)}><CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e5e7eb"/><XAxis dataKey="name" tickLine={false} axisLine={false} tickFormatter={v => v.length > 12 ? `${v.slice(0,12)}…` : v}/><YAxis tickLine={false} axisLine={false} tickFormatter={v => `${Math.round(v / 1000)}k`}/><Tooltip formatter={(v: number) => formatMoney(Math.round(v), 'RUB')} /><Bar dataKey="expensePerHour" name="Расход/ч" fill="#8b5cf6" radius={[8,8,0,0]}/></BarChart></ResponsiveContainer></div></div>
          </div>
        </div>
      )}
    </div>
  );
}
