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
import type { EmployeeHoursEntry, ExpenseDto, IncomeDto, ProjectDto, UserDto } from '../types';
import { formatMoney } from '../lib/utils';
import './AnalyticsPage.css';

const RUB_RATES: Record<string, number> = { RUB: 1, USD: 92, EUR: 100, BYN: 28 };
const CHART_COLORS = ['#10b981', '#14b8a6', '#06b6d4', '#3b82f6', '#6366f1', '#8b5cf6', '#f59e0b', '#f97316', '#ef4444', '#84cc16'];

const EXPENSE_LABELS: Record<string, string> = {
  HOUSEHOLD: 'Хоз. нужды',
  CONTRACTORS: 'Подрядчики',
  ROAD: 'Дорога',
  PER_DIEM: 'Суточные',
  PER_DIEM_EXTRA: 'Суточные сверх нормы',
  CASH: 'Наличные',
  CARD: 'Карта',
  OTHER: 'Прочие расходы',
  TICKET: 'Билеты',
  TRAVEL: 'Командировки',
  HOTEL: 'Проживание',
};

const TYPE_ALIASES: Record<string, string> = {
  per_diem: 'PER_DIEM',
  perdiem: 'PER_DIEM',
  PERDIEM: 'PER_DIEM',
  per_diem_extra: 'PER_DIEM_EXTRA',
  perdiem_extra: 'PER_DIEM_EXTRA',
  PER_DIEM_EXCESS: 'PER_DIEM_EXTRA',
  per_diem_excess: 'PER_DIEM_EXTRA',
};

type AnalyticsTab = 'overview' | 'employees' | 'expenses' | 'projects';
type EmployeeSort = 'efficiency' | 'hours' | 'stability' | 'expenses';

interface EmployeeMetric {
  userId: string;
  name: string;
  position: string;
  hours: number;
  workDays: number;
  expectedWorkDays: number;
  projects: number;
  expenseRub: number;
  noReceiptRub: number;
  expenseCount: number;
  avgHoursPerDay: number;
  attendanceRate: number;
  efficiencyScore: number;
  stabilityScore: number;
}

interface ProjectMetric {
  projectId: string;
  name: string;
  revenueRub: number;
  expenseRub: number;
  marginRub: number;
  marginPct: number;
  hours: number;
  employeeCount: number;
  expenseCount: number;
  noReceiptRub: number;
  expenseShare: number;
}

function rub(amount: number, currency: string) {
  return amount * (RUB_RATES[currency] || 1);
}

function normalizeType(type?: string) {
  const raw = String(type || '').trim();
  const upper = raw.toUpperCase();
  return TYPE_ALIASES[raw] || TYPE_ALIASES[upper] || upper;
}

function expenseLabel(expense: Pick<ExpenseDto, 'type' | 'subcategory' | 'name'>) {
  const type = normalizeType(expense.subcategory || expense.type);
  if (EXPENSE_LABELS[type]) return EXPENSE_LABELS[type];
  const name = String(expense.name || '').trim();
  const lower = name.toLowerCase().replace(/ё/g, 'е');
  if (lower.includes('суточн')) return lower.includes('сверх') ? 'Суточные сверх нормы' : 'Суточные';
  if (lower.includes('хоз.')) return 'Хоз. нужды';
  if (lower.includes('билет')) return 'Билеты';
  return name || 'Прочие расходы';
}

function monthKey(date: string) {
  const d = new Date(date);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
}

function fmtMonth(key: string) {
  const [y, m] = key.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('ru-RU', { month: 'short', year: 'numeric' });
}

function formatMoneyExact(amount: number, currency = 'RUB') {
  return new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount) + ` ${currency}`;
}

function formatDuration(hours: number) {
  const totalMinutes = Math.round(hours * 60);
  return `${Math.floor(totalMinutes / 60)} ч ${totalMinutes % 60} мин`;
}

function shortPersonName(name: string) {
  const parts = String(name || '').trim().split(/\\s+/).filter(Boolean);
  if (parts.length <= 1) return parts[0] || '—';
  const surname = parts[0];
  const initials = parts.slice(1).map(part => `${part.charAt(0).toUpperCase()}.`).join('');
  return `${surname} ${initials}`.trim();
}

function clamp(n: number, min: number, max: number) {
  return Math.min(max, Math.max(min, n));
}

function businessDays(from: string, to: string) {
  const start = new Date(from);
  const end = new Date(to);
  let count = 0;
  for (const d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
    const day = d.getDay();
    if (day !== 0 && day !== 6) count++;
  }
  return count;
}

export function AnalyticsPage() {
  const [expenses, setExpenses] = useState<ExpenseDto[]>([]);
  const [incomes, setIncomes] = useState<IncomeDto[]>([]);
  const [entries, setEntries] = useState<EmployeeHoursEntry[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [users, setUsers] = useState<UserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<AnalyticsTab>('overview');
  const [periodMonths, setPeriodMonths] = useState<6 | 12>(6);
  const [selectedProjectFilter, setSelectedProjectFilter] = useState('all');
  const [sortEmployees, setSortEmployees] = useState<EmployeeSort>('efficiency');
  const [selectedEmployeeId, setSelectedEmployeeId] = useState<string | null>(null);
  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(null);

  useEffect(() => { void loadData(); }, []);

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
    } catch (error) {
      console.error('Ошибка загрузки аналитики:', error);
    } finally {
      setLoading(false);
    }
  }

  const period = useMemo(() => {
    const end = new Date();
    end.setHours(23, 59, 59, 999);
    const start = new Date(end.getFullYear(), end.getMonth() - periodMonths + 1, 1);
    return { from: start.toISOString().slice(0, 10), to: end.toISOString().slice(0, 10) };
  }, [periodMonths]);

  const filteredExpenses = useMemo(() => expenses.filter(x => x.date >= period.from && x.date <= period.to && (selectedProjectFilter === 'all' || x.projectId === selectedProjectFilter)), [expenses, period, selectedProjectFilter]);
  const filteredIncomes = useMemo(() => incomes.filter(x => x.date >= period.from && x.date <= period.to && (selectedProjectFilter === 'all' || x.projectId === selectedProjectFilter)), [incomes, period, selectedProjectFilter]);
  const filteredEntries = useMemo(() => entries.filter(x => x.date >= period.from && x.date <= period.to && (selectedProjectFilter === 'all' || x.projectId === selectedProjectFilter)), [entries, period, selectedProjectFilter]);
  const expectedDays = useMemo(() => businessDays(period.from, period.to), [period]);

  const totals = useMemo(() => {
    const expenseRub = filteredExpenses.reduce((s, x) => s + rub(Number(x.amount || 0), x.currency), 0);
    const incomeRub = filteredIncomes.reduce((s, x) => s + rub(Number(x.amount || 0), x.currency), 0);
    const hours = filteredEntries.reduce((s, x) => s + Number(x.hours || 0), 0);
    const noReceiptRub = filteredExpenses.filter(x => !x.receiptSubmitted && !x.hasReceiptPhoto).reduce((s, x) => s + rub(Number(x.amount || 0), x.currency), 0);
    const workingDays = new Set(filteredEntries.map(x => x.date)).size;
    return { expenseRub, incomeRub, hours, noReceiptRub, workingDays, balanceRub: incomeRub - expenseRub };
  }, [filteredExpenses, filteredIncomes, filteredEntries]);

  const monthlyTrend = useMemo(() => {
    const months: string[] = [];
    const cursor = new Date(period.from);
    while (cursor.toISOString().slice(0, 10) <= period.to) {
      months.push(monthKey(cursor.toISOString()));
      cursor.setMonth(cursor.getMonth() + 1);
    }
    return months.map(key => ({
      month: fmtMonth(key),
      expense: Math.round(filteredExpenses.filter(x => monthKey(x.date) === key).reduce((s, x) => s + rub(Number(x.amount || 0), x.currency), 0)),
      income: Math.round(filteredIncomes.filter(x => monthKey(x.date) === key).reduce((s, x) => s + rub(Number(x.amount || 0), x.currency), 0)),
      hours: Math.round(filteredEntries.filter(x => monthKey(x.date) === key).reduce((s, x) => s + Number(x.hours || 0), 0) * 10) / 10,
    }));
  }, [period, filteredExpenses, filteredIncomes, filteredEntries]);

  const employeeMetrics = useMemo((): EmployeeMetric[] => {
    const map = new Map<string, EmployeeMetric>();
    const daySets = new Map<string, Set<string>>();
    const projectSets = new Map<string, Set<string>>();
    const ensure = (id: string) => {
      const user = users.find(u => u.id === id);
      if (!map.has(id)) map.set(id, {
        userId: id,
        name: user?.name || `${user?.lastName || ''} ${user?.firstName || ''}`.trim() || 'Неизвестный',
        position: user?.position || '—', hours: 0, workDays: 0, expectedWorkDays: expectedDays, projects: 0,
        expenseRub: 0, noReceiptRub: 0, expenseCount: 0, avgHoursPerDay: 0, attendanceRate: 0, efficiencyScore: 0, stabilityScore: 0,
      });
      return map.get(id)!;
    };
    filteredEntries.forEach(x => {
      const m = ensure(x.userId);
      m.hours += Number(x.hours || 0);
      if (!daySets.has(x.userId)) daySets.set(x.userId, new Set());
      if (!projectSets.has(x.userId)) projectSets.set(x.userId, new Set());
      daySets.get(x.userId)!.add(x.date);
      projectSets.get(x.userId)!.add(x.projectId);
    });
    filteredExpenses.forEach(x => {
      const m = ensure(x.userId);
      const amount = rub(Number(x.amount || 0), x.currency);
      m.expenseRub += amount;
      m.expenseCount += 1;
      if (!x.receiptSubmitted && !x.hasReceiptPhoto) m.noReceiptRub += amount;
    });
    map.forEach(m => {
      m.workDays = daySets.get(m.userId)?.size || 0;
      m.projects = projectSets.get(m.userId)?.size || 0;
      m.avgHoursPerDay = m.workDays ? m.hours / m.workDays : 0;
      m.attendanceRate = expectedDays ? clamp((m.workDays / expectedDays) * 100, 0, 100) : 0;
      // Основной критерий пользователя: чем больше часов фактически в рабочий день, тем выше эффективность.
      m.efficiencyScore = clamp((m.avgHoursPerDay / 8) * 100, 0, 120);
      // Дополнительный критерий качества: регулярность присутствия в рабочих днях.
      m.stabilityScore = clamp(m.attendanceRate * 0.6 + clamp((m.avgHoursPerDay / 8) * 100, 0, 100) * 0.4, 0, 100);
    });
    return Array.from(map.values()).filter(x => x.hours > 0 || x.expenseRub > 0);
  }, [filteredEntries, filteredExpenses, users, expectedDays]);

  const sortedEmployees = useMemo(() => [...employeeMetrics].sort((a, b) => {
    if (sortEmployees === 'hours') return b.hours - a.hours;
    if (sortEmployees === 'stability') return b.stabilityScore - a.stabilityScore;
    if (sortEmployees === 'expenses') return b.expenseRub - a.expenseRub;
    return b.efficiencyScore - a.efficiencyScore;
  }), [employeeMetrics, sortEmployees]);

  const expenseCategories = useMemo(() => {
    const map = new Map<string, number>();
    filteredExpenses.forEach(x => {
      const label = expenseLabel(x);
      map.set(label, (map.get(label) || 0) + rub(Number(x.amount || 0), x.currency));
    });
    return Array.from(map, ([name, value]) => ({ name, value: Math.round(value) })).sort((a, b) => b.value - a.value).slice(0, 10);
  }, [filteredExpenses]);

  const receiptData = useMemo(() => {
    let withReceipt = 0; let withoutReceipt = 0;
    filteredExpenses.forEach(x => {
      const amount = rub(Number(x.amount || 0), x.currency);
      if (x.receiptSubmitted || x.hasReceiptPhoto) withReceipt += amount; else withoutReceipt += amount;
    });
    return [{ name: 'С чеком', value: Math.round(withReceipt) }, { name: 'Без чека', value: Math.round(withoutReceipt) }];
  }, [filteredExpenses]);

  const projectMetrics = useMemo((): ProjectMetric[] => {
    const map = new Map<string, ProjectMetric>();
    projects.forEach(p => map.set(p.id, {
      projectId: p.id, name: p.name, revenueRub: Number(p.revenue || p.sellingPrice || 0), expenseRub: 0,
      marginRub: Number(p.revenue || p.sellingPrice || 0), marginPct: Number(p.revenue || p.sellingPrice || 0) ? 100 : 0,
      hours: 0, employeeCount: 0, expenseCount: 0, noReceiptRub: 0, expenseShare: 0,
    }));
    filteredExpenses.forEach(x => {
      if (!map.has(x.projectId)) map.set(x.projectId, { projectId: x.projectId, name: x.projectName || 'Без проекта', revenueRub: 0, expenseRub: 0, marginRub: 0, marginPct: 0, hours: 0, employeeCount: 0, expenseCount: 0, noReceiptRub: 0, expenseShare: 0 });
      const m = map.get(x.projectId)!;
      const amount = rub(Number(x.amount || 0), x.currency);
      m.expenseRub += amount; m.expenseCount += 1;
      if (!x.receiptSubmitted && !x.hasReceiptPhoto) m.noReceiptRub += amount;
    });
    filteredEntries.forEach(x => { const m = map.get(x.projectId); if (m) m.hours += Number(x.hours || 0); });
    const people = new Map<string, Set<string>>();
    filteredEntries.forEach(x => { if (!people.has(x.projectId)) people.set(x.projectId, new Set()); people.get(x.projectId)!.add(x.userId); });
    const totalExpense = filteredExpenses.reduce((s, x) => s + rub(Number(x.amount || 0), x.currency), 0);
    map.forEach(m => {
      m.marginRub = m.revenueRub - m.expenseRub;
      m.marginPct = m.revenueRub ? (m.marginRub / m.revenueRub) * 100 : 0;
      m.employeeCount = people.get(m.projectId)?.size || 0;
      m.expenseShare = totalExpense ? (m.expenseRub / totalExpense) * 100 : 0;
    });
    return Array.from(map.values()).filter(m => m.expenseRub > 0 || m.hours > 0).sort((a, b) => b.expenseRub - a.expenseRub);
  }, [projects, filteredExpenses, filteredEntries]);

  const topProject = projectMetrics[0];
  const selectedEmployee = selectedEmployeeId ? employeeMetrics.find(x => x.userId === selectedEmployeeId) || null : null;
  const selectedProject = selectedProjectId ? projectMetrics.find(x => x.projectId === selectedProjectId) || null : null;

  const employeeDetail = useMemo(() => {
    if (!selectedEmployeeId) return null;
    const userEntries = filteredEntries.filter(x => x.userId === selectedEmployeeId).sort((a, b) => b.date.localeCompare(a.date));
    const userExpenses = filteredExpenses.filter(x => x.userId === selectedEmployeeId).sort((a, b) => b.date.localeCompare(a.date));
    const byProject = new Map<string, { name: string; hours: number; expense: number }>();
    userEntries.forEach(e => { const row = byProject.get(e.projectId) || { name: e.projectName || 'Без проекта', hours: 0, expense: 0 }; row.hours += Number(e.hours || 0); byProject.set(e.projectId, row); });
    userExpenses.forEach(e => { const row = byProject.get(e.projectId) || { name: e.projectName || 'Без проекта', hours: 0, expense: 0 }; row.expense += rub(Number(e.amount || 0), e.currency); byProject.set(e.projectId, row); });
    return { userEntries, userExpenses, byProject: Array.from(byProject.entries()).map(([projectId, v]) => ({ projectId, ...v })).sort((a, b) => b.hours - a.hours) };
  }, [selectedEmployeeId, filteredEntries, filteredExpenses]);

  const projectDetail = useMemo(() => {
    if (!selectedProjectId) return null;
    const projectEntries = filteredEntries.filter(x => x.projectId === selectedProjectId).sort((a, b) => b.date.localeCompare(a.date));
    const projectExpenses = filteredExpenses.filter(x => x.projectId === selectedProjectId).sort((a, b) => b.date.localeCompare(a.date));
    const byEmployee = new Map<string, { name: string; hours: number; expense: number }>();
    projectEntries.forEach(e => { const user = users.find(u => u.id === e.userId); const row = byEmployee.get(e.userId) || { name: user?.name || e.userName || 'Неизвестный', hours: 0, expense: 0 }; row.hours += Number(e.hours || 0); byEmployee.set(e.userId, row); });
    projectExpenses.forEach(e => { const user = users.find(u => u.id === e.userId); const row = byEmployee.get(e.userId) || { name: user?.name || 'Неизвестный', hours: 0, expense: 0 }; row.expense += rub(Number(e.amount || 0), e.currency); byEmployee.set(e.userId, row); });
    return { projectEntries, projectExpenses, byEmployee: Array.from(byEmployee.entries()).map(([userId, v]) => ({ userId, ...v })).sort((a, b) => b.hours - a.hours) };
  }, [selectedProjectId, filteredEntries, filteredExpenses, users]);

  const signals = useMemo(() => {
    const out: { tone: 'good' | 'warn' | 'bad'; title: string; text: string }[] = [];
    const receiptPct = totals.expenseRub ? (totals.noReceiptRub / totals.expenseRub) * 100 : 0;
    if (receiptPct > 20) out.push({ tone: 'bad', title: 'Много расходов без чека', text: `${formatMoney(Math.round(totals.noReceiptRub), 'RUB')} или ${Math.round(receiptPct)}% всех расходов требуют внимания.` });
    else if (totals.noReceiptRub > 0) out.push({ tone: 'warn', title: 'Есть операции без чека', text: `${formatMoney(Math.round(totals.noReceiptRub), 'RUB')} расходов пока без подтверждения.` });
    if (topProject) out.push({ tone: topProject.marginPct < 0 ? 'bad' : topProject.marginPct < 15 ? 'warn' : 'good', title: 'Главный проект по затратам', text: `${topProject.name} формирует ${Math.round(topProject.expenseShare)}% расходов периода.` });
    const best = [...employeeMetrics].sort((a, b) => b.efficiencyScore - a.efficiencyScore)[0];
    if (best) out.push({ tone: 'good', title: 'Лидер по рабочему ритму', text: `${best.name}: ${best.avgHoursPerDay.toFixed(1)} ч/день за ${best.workDays} рабочих дней.` });
    const overloaded = [...employeeMetrics].find(e => e.avgHoursPerDay > 10);
    if (overloaded) out.push({ tone: 'warn', title: 'Перегрузка сотрудника', text: `${overloaded.name} в среднем работает ${overloaded.avgHoursPerDay.toFixed(1)} ч/день — стоит проверить нагрузку.` });
    return out;
  }, [totals, topProject, employeeMetrics]);

  if (loading) return <div className="flex items-center justify-center h-64"><div className="animate-spin rounded-full h-12 w-12 border-b-2 border-emerald-500" /></div>;

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
            <div><span className="text-emerald-100 text-xs font-bold uppercase tracking-[0.18em]">ProLes Intelligence</span><h1 className="mt-2 text-3xl md:text-4xl font-black tracking-tight">Аналитический центр</h1><p className="mt-2 max-w-2xl text-emerald-50/90">Рабочий ритм команды, экономика проектов и контроль расходов — с детализацией до каждой операции.</p></div>
            <button onClick={() => void loadData()} className="analytics-glass-button px-4 py-2.5 rounded-xl text-sm font-semibold">↻ Обновить</button>
          </div>
          <div className="mt-7 flex flex-wrap items-center gap-3">
            <div className="analytics-period-group"><button onClick={() => setPeriodMonths(6)} className={periodMonths === 6 ? 'active' : ''}>6 мес.</button><button onClick={() => setPeriodMonths(12)} className={periodMonths === 12 ? 'active' : ''}>12 мес.</button></div>
            <select value={selectedProjectFilter} onChange={e => setSelectedProjectFilter(e.target.value)} className="analytics-select"><option value="all">Все проекты</option>{projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select>
          </div>
        </div>
      </div>

      <div className="analytics-tabs">{tabs.map(t => <button key={t.id} onClick={() => setTab(t.id)} className={tab === t.id ? 'active' : ''}><span>{t.icon}</span>{t.label}</button>)}</div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-4">
        <div className="analytics-kpi tone-blue bg-white dark:bg-gray-800 rounded-2xl p-5 shadow-sm"><p className="text-xs font-bold text-gray-400 uppercase">Расходы</p><p className="mt-2 text-2xl font-black">{formatMoney(Math.round(totals.expenseRub), 'RUB')}</p><p className="text-xs text-gray-500 mt-1">{filteredExpenses.length} операций</p></div>
        <div className="analytics-kpi tone-green bg-white dark:bg-gray-800 rounded-2xl p-5 shadow-sm"><p className="text-xs font-bold text-gray-400 uppercase">Рабочие часы</p><p className="mt-2 text-2xl font-black">{totals.hours.toFixed(1)}</p><p className="text-xs text-gray-500 mt-1">{totals.workingDays} активных дней</p></div>
        <div className="analytics-kpi tone-amber bg-white dark:bg-gray-800 rounded-2xl p-5 shadow-sm"><p className="text-xs font-bold text-gray-400 uppercase">Без чека</p><p className="mt-2 text-2xl font-black">{formatMoney(Math.round(totals.noReceiptRub), 'RUB')}</p><p className="text-xs text-gray-500 mt-1">нуждаются в проверке</p></div>
        <div className="analytics-kpi tone-red bg-white dark:bg-gray-800 rounded-2xl p-5 shadow-sm"><p className="text-xs font-bold text-gray-400 uppercase">Сотрудники</p><p className="mt-2 text-2xl font-black">{employeeMetrics.length}</p><p className="text-xs text-gray-500 mt-1">активны в периоде</p></div>
        <div className="analytics-kpi tone-green bg-white dark:bg-gray-800 rounded-2xl p-5 shadow-sm"><p className="text-xs font-bold text-gray-400 uppercase">Проекты</p><p className="mt-2 text-2xl font-black">{projectMetrics.length}</p><p className="text-xs text-gray-500 mt-1">есть работа или расходы</p></div>
      </div>

      {tab === 'overview' && <div className="space-y-6">
        <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
          <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm xl:col-span-2"><div className="flex items-start justify-between gap-3"><div><h2 className="text-lg font-bold text-gray-900 dark:text-white">Ритм бизнеса</h2><p className="text-sm text-gray-500">динамика расходов и часов по месяцам</p></div><span className="analytics-badge">TREND</span></div><div className="h-80 mt-4"><ResponsiveContainer width="100%" height="100%"><AreaChart data={monthlyTrend}><CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e5e7eb"/><XAxis dataKey="month" tickLine={false} axisLine={false}/><YAxis tickLine={false} axisLine={false}/><Tooltip content={({ active, payload, label }) => active && payload?.length ? <div className="analytics-tooltip"><div className="analytics-tooltip-title">{label}</div>{payload.map((item, i) => <div key={i} className="analytics-tooltip-row"><span>{item.name}</span><strong>{item.name === 'Часы' ? `${item.value} ч` : formatMoney(Number(item.value || 0), 'RUB')}</strong></div>)}</div> : null}/><Legend/><Area type="monotone" dataKey="expense" name="Расходы" stroke="#ef4444" fill="#fee2e2" strokeWidth={3}/><Area type="monotone" dataKey="hours" name="Часы" stroke="#10b981" fill="#d1fae5" strokeWidth={3}/></AreaChart></ResponsiveContainer></div></div>
          <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm"><h3 className="font-bold text-gray-900 dark:text-white">Сигналы</h3><div className="space-y-3 mt-4">{signals.map((s, i) => <div key={i} className={`signal-card ${s.tone}`}><div className="signal-icon">{s.tone === 'good' ? '✓' : '!'}</div><div><p className="font-bold text-sm">{s.title}</p><p className="text-xs text-gray-500 leading-5">{s.text}</p></div></div>)}{!signals.length && <div className="analytics-empty">Новых сигналов нет.</div>}</div></div>
        </div>
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm"><h3 className="font-bold text-gray-900 dark:text-white">Структура расходов</h3><div className="h-80"><ResponsiveContainer width="100%" height="100%"><PieChart><Pie data={expenseCategories} dataKey="value" nameKey="name" innerRadius={65} outerRadius={105} paddingAngle={2}>{expenseCategories.map((_, i) => <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]}/>)}</Pie><Tooltip content={({ active, payload }) => active && payload?.length ? <div className="analytics-tooltip"><div className="analytics-tooltip-row"><span>{String(payload[0].name || 'Категория')}</span><strong>{formatMoney(Number(payload[0].value || 0), 'RUB')}</strong></div></div> : null}/><Legend/></PieChart></ResponsiveContainer></div></div>
          <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm"><h3 className="font-bold text-gray-900 dark:text-white">Документооборот расходов</h3><div className="h-80"><ResponsiveContainer width="100%" height="100%"><PieChart><Pie data={receiptData} dataKey="value" nameKey="name" innerRadius={70} outerRadius={110} paddingAngle={3}>{receiptData.map((_, i) => <Cell key={i} fill={i === 0 ? '#10b981' : '#ef4444'}/>)}</Pie><Tooltip content={({ active, payload }) => active && payload?.length ? <div className="analytics-tooltip"><div className="analytics-tooltip-row"><span>{String(payload[0].name || '')}</span><strong>{formatMoney(Number(payload[0].value || 0), 'RUB')}</strong></div></div> : null}/><Legend/></PieChart></ResponsiveContainer></div></div>
        </div>
      </div>}

      {tab === 'employees' && <div className="space-y-6">
        <div className="flex flex-wrap items-center justify-between gap-3"><div><h2 className="text-xl font-black text-gray-900 dark:text-white">Эффективность сотрудников</h2><p className="text-sm text-gray-500">главный критерий — фактические часы на рабочий день; дополнительно учитывается стабильность</p></div><div className="analytics-segment">{([['efficiency', 'Часы / день'], ['stability', 'Стабильность'], ['hours', 'Всего часов'], ['expenses', 'Расходы']] as const).map(([k, label]) => <button key={k} onClick={() => setSortEmployees(k)} className={sortEmployees === k ? 'active' : ''}>{label}</button>)}</div></div>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-5 shadow-sm"><p className="text-xs font-bold text-gray-400 uppercase">Средняя норма</p><p className="text-3xl font-black mt-2">8 ч/день</p><p className="text-xs text-gray-500 mt-1">база для индекса эффективности</p></div>
          <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-5 shadow-sm"><p className="text-xs font-bold text-gray-400 uppercase">Среднее команды</p><p className="text-3xl font-black mt-2">{employeeMetrics.length ? (employeeMetrics.reduce((s, x) => s + x.avgHoursPerDay, 0) / employeeMetrics.length).toFixed(1) : '0.0'} ч/день</p><p className="text-xs text-gray-500 mt-1">по активным рабочим дням</p></div>
          <div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-5 shadow-sm"><p className="text-xs font-bold text-gray-400 uppercase">Средняя стабильность</p><p className="text-3xl font-black mt-2">{employeeMetrics.length ? Math.round(employeeMetrics.reduce((s, x) => s + x.stabilityScore, 0) / employeeMetrics.length) : 0}%</p><p className="text-xs text-gray-500 mt-1">выходы + рабочий ритм</p></div>
        </div>
        <div className="analytics-table-wrap bg-white dark:bg-gray-800 rounded-2xl shadow-sm overflow-hidden"><div className="overflow-x-auto"><table className="w-full text-sm"><thead><tr><th>Сотрудник</th><th>Часы</th><th>Дни</th><th>Часов/день</th><th>Стабильность</th><th>Расходы</th><th>Без чека</th><th>Детали</th></tr></thead><tbody>{sortedEmployees.map((e, i) => <tr key={e.userId} className="cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-700/40" onClick={() => setSelectedEmployeeId(e.userId)}><td><div className="flex items-center gap-3"><span className={`rank-dot rank-${i + 1}`}>{i + 1}</span><div><p className="font-bold text-gray-900 dark:text-white">{e.name}</p><p className="text-xs text-gray-500">{e.position}</p></div></div></td><td className="font-semibold">{e.hours.toFixed(1)}</td><td>{e.workDays}</td><td><span className={`metric-pill ${e.avgHoursPerDay >= 8 ? 'positive' : 'negative'}`}>{e.avgHoursPerDay.toFixed(1)} ч/день</span></td><td>{Math.round(e.stabilityScore)}%</td><td>{formatMoney(Math.round(e.expenseRub), 'RUB')}</td><td className={e.noReceiptRub ? 'text-red-500 font-bold' : 'text-emerald-600'}>{formatMoney(Math.round(e.noReceiptRub), 'RUB')}</td><td><button className="analytics-link" onClick={(event) => { event.stopPropagation(); setSelectedEmployeeId(e.userId); }}>Открыть →</button></td></tr>)}</tbody></table></div>{!sortedEmployees.length && <div className="analytics-empty py-12">Нет данных за выбранный период.</div>}</div>
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6"><div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm"><h3 className="font-bold text-gray-900 dark:text-white">Рабочий ритм</h3><p className="text-xs text-gray-500 mb-4">часы в фактически отработанный день — главный критерий эффективности</p><div className="h-80"><ResponsiveContainer width="100%" height="100%"><BarChart data={sortedEmployees.slice(0, 10)} layout="vertical" margin={{ left: 20, right: 20 }}><CartesianGrid strokeDasharray="3 3" horizontal={false}/><XAxis type="number" domain={[0, 12]}/><YAxis type="category" dataKey="name" width={100} tickFormatter={value => shortPersonName(String(value))}/><Tooltip content={({ active, payload }) => active && payload?.length ? <div className="analytics-tooltip"><div className="analytics-tooltip-row"><span>Часов в день</span><strong>{Number(payload[0].value || 0).toFixed(1)} ч</strong></div></div> : null}/><Bar dataKey="avgHoursPerDay" name="Часов в день" fill="#10b981" radius={[0,8,8,0]}/></BarChart></ResponsiveContainer></div></div><div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm"><h3 className="font-bold text-gray-900 dark:text-white">Стабильность команды</h3><p className="text-xs text-gray-500 mb-4">выходы в рабочие дни + соблюдение рабочего ритма</p><div className="h-80"><ResponsiveContainer width="100%" height="100%"><BarChart data={sortedEmployees.slice(0, 10)} layout="vertical" margin={{ left: 20, right: 20 }}><CartesianGrid strokeDasharray="3 3" horizontal={false}/><XAxis type="number" domain={[0, 100]} tickFormatter={v => `${v}%`}/><YAxis type="category" dataKey="name" width={100} tickFormatter={value => shortPersonName(String(value))}/><Tooltip content={({ active, payload }) => active && payload?.length ? <div className="analytics-tooltip"><div className="analytics-tooltip-row"><span>Стабильность</span><strong>{Math.round(Number(payload[0].value || 0))}%</strong></div></div> : null}/><Bar dataKey="stabilityScore" name="Стабильность" fill="#6366f1" radius={[0,8,8,0]}/></BarChart></ResponsiveContainer></div></div></div>
      </div>}

      {tab === 'expenses' && <div className="space-y-6"><div className="grid grid-cols-1 lg:grid-cols-3 gap-6"><div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm lg:col-span-2"><div className="flex justify-between mb-5"><div><h2 className="text-lg font-bold text-gray-900 dark:text-white">Pareto расходов</h2><p className="text-sm text-gray-500">категории приведены к понятным русским названиям</p></div><span className="analytics-badge">TOP 10</span></div><div className="h-80"><ResponsiveContainer width="100%" height="100%"><BarChart data={expenseCategories}><CartesianGrid strokeDasharray="3 3" vertical={false}/><XAxis dataKey="name" tickLine={false} axisLine={false} interval={0} angle={-18} textAnchor="end" height={75}/><YAxis tickLine={false} axisLine={false} tickFormatter={v => `${Math.round(Number(v) / 1000)}k`}/><Tooltip content={({ active, payload }) => active && payload?.length ? <div className="analytics-tooltip"><div className="analytics-tooltip-row"><span>{String(payload[0].payload?.name || '')}</span><strong>{formatMoney(Number(payload[0].value || 0), 'RUB')}</strong></div></div> : null}/><Bar dataKey="value" name="Сумма" radius={[8,8,0,0]}>{expenseCategories.map((_, i) => <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]}/>)}</Bar></BarChart></ResponsiveContainer></div></div><div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm"><h3 className="font-bold text-gray-900 dark:text-white">Контроль чеков</h3><div className="mt-5 text-center"><div className="text-4xl font-black text-red-500">{totals.expenseRub ? Math.round(totals.noReceiptRub / totals.expenseRub * 100) : 0}%</div><p className="text-sm text-gray-500 mt-2">расходов без подтверждения</p></div><div className="mt-6 space-y-3"><div className="flex justify-between text-sm"><span>Без чека</span><strong>{formatMoney(Math.round(totals.noReceiptRub), 'RUB')}</strong></div><div className="h-2 rounded-full bg-gray-100 overflow-hidden"><div className="h-full bg-red-500 rounded-full" style={{ width: `${clamp(totals.expenseRub ? totals.noReceiptRub / totals.expenseRub * 100 : 0, 0, 100)}%` }}/></div><div className="flex justify-between text-sm"><span>С чеком</span><strong>{formatMoney(Math.round(totals.expenseRub - totals.noReceiptRub), 'RUB')}</strong></div></div></div></div><div className="analytics-table-wrap bg-white dark:bg-gray-800 rounded-2xl shadow-sm overflow-hidden"><div className="p-5 border-b border-gray-100 dark:border-gray-700"><h3 className="font-bold text-gray-900 dark:text-white">Аудит операций</h3></div><div className="overflow-x-auto"><table className="w-full text-sm"><thead><tr><th>Дата</th><th>Категория</th><th>Название</th><th>Проект</th><th>Сотрудник</th><th>Чек</th><th>Сумма</th></tr></thead><tbody>{[...filteredExpenses].sort((a,b) => rub(Number(b.amount || 0),b.currency)-rub(Number(a.amount || 0),a.currency)).slice(0, 30).map(e => <tr key={e.id}><td>{new Date(e.date).toLocaleDateString('ru-RU')}</td><td><span className="metric-pill neutral">{expenseLabel(e)}</span></td><td className="font-semibold">{e.name || '—'}</td><td>{e.projectName || '—'}</td><td>{users.find(u => u.id === e.userId)?.name || '—'}</td><td>{e.receiptSubmitted || e.hasReceiptPhoto ? <span className="metric-pill positive">Есть</span> : <span className="metric-pill negative">Нет</span>}</td><td className="font-black">{formatMoneyExact(rub(Number(e.amount || 0), e.currency))}</td></tr>)}</tbody></table></div></div></div>}

      {tab === 'projects' && <div className="space-y-6"><div className="grid grid-cols-1 xl:grid-cols-2 gap-6"><div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm"><div className="flex justify-between mb-5"><div><h2 className="text-lg font-bold text-gray-900 dark:text-white">Экономика проектов</h2><p className="text-sm text-gray-500">затраты, часы и финансовое здоровье</p></div></div><div className="space-y-3">{projectMetrics.slice(0, 12).map(p => <button key={p.projectId} onClick={() => setSelectedProjectId(p.projectId)} className="project-health w-full text-left hover:border-emerald-300"><div className="flex items-center justify-between gap-3"><div className="min-w-0"><p className="font-bold text-gray-900 dark:text-white truncate">{p.name}</p><p className="text-xs text-gray-500">{p.employeeCount} чел. · {formatDuration(p.hours)} · {Math.round(p.expenseShare)}% расходов</p></div><div className="text-right shrink-0"><p className={`font-black ${p.marginPct >= 0 ? 'text-emerald-600' : 'text-red-500'}`}>{Math.round(p.marginPct)}%</p><p className="text-xs text-gray-500">{formatMoney(Math.round(p.expenseRub), 'RUB')}</p></div></div><div className="health-bar"><span style={{ width: `${clamp(p.expenseRub / Math.max(p.revenueRub, p.expenseRub, 1) * 100, 0, 100)}%` }}/></div></button>)}</div></div><div className="analytics-card bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm"><h2 className="text-lg font-bold text-gray-900 dark:text-white">Рабочая нагрузка проектов</h2><p className="text-sm text-gray-500 mb-5">объём фактически внесённого времени</p><div className="h-80"><ResponsiveContainer width="100%" height="100%"><BarChart data={projectMetrics.filter(p => p.hours > 0).slice(0, 10)}><CartesianGrid strokeDasharray="3 3" vertical={false}/><XAxis dataKey="name" tickLine={false} axisLine={false} tickFormatter={v => String(v).length > 12 ? `${String(v).slice(0,12)}…` : String(v)}/><YAxis/><Tooltip content={({ active, payload }) => active && payload?.length ? <div className="analytics-tooltip"><div className="analytics-tooltip-row"><span>Рабочее время</span><strong>{formatDuration(Number(payload[0].value || 0))}</strong></div></div> : null}/><Bar dataKey="hours" name="Часы" radius={[8,8,0,0]}>{projectMetrics.filter(p => p.hours > 0).slice(0, 10).map((project, index) => <Cell key={project.projectId} fill={CHART_COLORS[index % CHART_COLORS.length]} />)}</Bar></BarChart></ResponsiveContainer></div><div className="project-chart-legend">{projectMetrics.filter(p => p.hours > 0).slice(0, 10).map((project, index) => <span key={project.projectId} className="project-chart-legend-item"><i style={{ background: CHART_COLORS[index % CHART_COLORS.length] }} />{project.name}</span>)}</div></div></div><div className="analytics-table-wrap bg-white dark:bg-gray-800 rounded-2xl shadow-sm overflow-hidden"><div className="overflow-x-auto"><table className="w-full text-sm"><thead><tr><th>Проект</th><th>Часы</th><th>Сотрудники</th><th>Расходы</th><th>Без чека</th><th>Доля расходов</th><th>Маржа</th><th></th></tr></thead><tbody>{projectMetrics.map(p => <tr key={p.projectId} className="cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-700/40" onClick={() => setSelectedProjectId(p.projectId)}><td className="font-bold text-gray-900 dark:text-white">{p.name}</td><td>{formatDuration(p.hours)}</td><td>{p.employeeCount}</td><td>{formatMoney(Math.round(p.expenseRub), 'RUB')}</td><td>{formatMoney(Math.round(p.noReceiptRub), 'RUB')}</td><td>{Math.round(p.expenseShare)}%</td><td className={p.marginPct < 0 ? 'text-red-500 font-bold' : 'text-emerald-600 font-bold'}>{Math.round(p.marginPct)}%</td><td><button className="analytics-link" onClick={event => { event.stopPropagation(); setSelectedProjectId(p.projectId); }}>Открыть →</button></td></tr>)}</tbody></table></div></div></div>}

      {selectedEmployee && employeeDetail && <div className="analytics-detail-overlay" onClick={() => setSelectedEmployeeId(null)}><div className="analytics-detail-panel" onClick={e => e.stopPropagation()}><div className="analytics-detail-header"><div><span className="text-xs font-bold uppercase tracking-wider text-emerald-600">Сотрудник</span><h2>{selectedEmployee.name}</h2><p>{selectedEmployee.position} · {selectedEmployee.avgHoursPerDay.toFixed(1)} ч/день · стабильность {Math.round(selectedEmployee.stabilityScore)}%</p></div><button onClick={() => setSelectedEmployeeId(null)} className="analytics-close">×</button></div><div className="analytics-detail-grid"><div><span>Рабочее время</span><strong>{formatDuration(selectedEmployee.hours)}</strong></div><div><span>Рабочих дней</span><strong>{selectedEmployee.workDays}</strong></div><div><span>Расходы</span><strong>{formatMoneyExact(selectedEmployee.expenseRub)}</strong></div><div><span>Без чека</span><strong>{formatMoneyExact(selectedEmployee.noReceiptRub)}</strong></div></div><div className="analytics-detail-section"><h3>По проектам</h3><div className="space-y-2">{employeeDetail.byProject.map(p => <button key={p.projectId} onClick={() => { setSelectedEmployeeId(null); setSelectedProjectId(p.projectId); }} className="analytics-detail-row"><span>{p.name}</span><span>{formatDuration(p.hours)} · {formatMoneyExact(p.expense)}</span></button>)}</div></div><div className="analytics-detail-section"><h3>Время</h3><div className="analytics-detail-scroll">{employeeDetail.userEntries.map(e => <div key={`${e.userId}-${e.projectId}-${e.date}-${e.hours}`} className="analytics-detail-row"><span>{new Date(e.date).toLocaleDateString('ru-RU')} · {e.projectName || 'Без проекта'}</span><strong>{formatDuration(Number(e.hours || 0))}</strong></div>)}</div></div><div className="analytics-detail-section"><h3>Расходы</h3><div className="analytics-detail-scroll">{employeeDetail.userExpenses.map(e => <div key={e.id} className="analytics-detail-row"><span>{new Date(e.date).toLocaleDateString('ru-RU')} · {e.name?.trim() || expenseLabel(e)}</span><strong>{formatMoneyExact(rub(Number(e.amount || 0), e.currency))}</strong></div>)}</div></div></div></div>}

      {selectedProject && projectDetail && <div className="analytics-detail-overlay" onClick={() => setSelectedProjectId(null)}><div className="analytics-detail-panel" onClick={e => e.stopPropagation()}><div className="analytics-detail-header"><div><span className="text-xs font-bold uppercase tracking-wider text-emerald-600">Проект</span><h2>{selectedProject.name}</h2><p>{formatDuration(selectedProject.hours)} · {selectedProject.employeeCount} сотрудников · расходы {formatMoneyExact(selectedProject.expenseRub)}</p></div><button onClick={() => setSelectedProjectId(null)} className="analytics-close">×</button></div><div className="analytics-detail-grid"><div><span>Расходы</span><strong>{formatMoneyExact(selectedProject.expenseRub)}</strong></div><div><span>Без чека</span><strong>{formatMoneyExact(selectedProject.noReceiptRub)}</strong></div><div><span>Рабочее время</span><strong>{formatDuration(selectedProject.hours)}</strong></div><div><span>Маржа</span><strong>{Math.round(selectedProject.marginPct)}%</strong></div></div><div className="analytics-detail-section"><h3>Кто работал</h3><div className="space-y-2">{projectDetail.byEmployee.map(p => <button key={p.userId} onClick={() => { setSelectedProjectId(null); setSelectedEmployeeId(p.userId); }} className="analytics-detail-row"><span>{p.name}</span><span>{formatDuration(p.hours)} · {formatMoneyExact(p.expense)}</span></button>)}</div></div><div className="analytics-detail-section"><h3>Рабочее время</h3><div className="analytics-detail-scroll">{projectDetail.projectEntries.map(e => <div key={`${e.userId}-${e.date}-${e.hours}`} className="analytics-detail-row"><span>{new Date(e.date).toLocaleDateString('ru-RU')} · {shortPersonName(e.userName || '—')}</span><strong>{formatDuration(Number(e.hours || 0))}</strong></div>)}</div></div><div className="analytics-detail-section"><h3>Все расходы</h3><div className="analytics-detail-scroll">{projectDetail.projectExpenses.map(e => <div key={e.id} className="analytics-detail-row"><span>{new Date(e.date).toLocaleDateString('ru-RU')} · {shortPersonName(users.find(u => u.id === e.userId)?.name || '—')} · {e.name?.trim() || expenseLabel(e)}</span><strong>{formatMoneyExact(rub(Number(e.amount || 0), e.currency))}</strong></div>)}</div></div></div></div>}
    </div>
  );
}
