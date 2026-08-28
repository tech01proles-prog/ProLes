import { useState, useEffect, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { createPortal } from 'react-dom';
import api from '../api/client';
import type { IncomeDto, ExpenseDto, ProjectDto, UserDto } from '../types';
import { generateUUID, formatDate, formatMoney } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';
import { ScopeTabs } from '../components/ScopeTabs';

const INCOME_TYPES = [
  { key: 'HOUSEHOLD', label: 'Хоз.нужды', icon: '🏠', color: 'bg-blue-100 text-blue-700 border-blue-200 dark:bg-blue-950/40 dark:text-blue-400 dark:border-blue-900' },
  { key: 'CARD', label: 'По карте', icon: '💳', color: 'bg-purple-100 text-purple-700 border-purple-200 dark:bg-purple-950/40 dark:text-purple-400 dark:border-purple-900' },
  { key: 'CASH', label: 'Наличными', icon: '💵', color: 'bg-emerald-100 text-emerald-700 border-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-400 dark:border-emerald-900' },
];

const EXPENSE_TYPES = [
  { key: 'ROAD', label: 'Дорога', icon: '🚗' },
  { key: 'OTHER', label: 'Прочее', icon: '📦' },
];

const findIncomeType = (key: string) => INCOME_TYPES.find(t => t.key === key);
const findExpenseType = (key: string) => EXPENSE_TYPES.find(t => t.key === key);

interface CombinedEntry {
  id: string;
  type: 'INCOME' | 'EXPENSE';
  userId: string;
  userName?: string;
  projectId: string;
  projectName: string;
  date: string;
  name: string;
  category: string;
  amount: number;
  currency: string;
  comment: string;
  hasReceipt?: boolean;
}

export function ExpensesPage() {
  const [searchParams] = useSearchParams();
  const [user, setUser] = useState<UserDto | null>(null);
  const { can } = usePermissions();
  const canViewAll = can('expenses_all', 'view');

  const [scope, setScope] = useState<'my' | 'all'>('my');
  const effectiveScope = canViewAll ? scope : 'my';

  const [myIncomes, setMyIncomes] = useState<IncomeDto[]>([]);
  const [allIncomes, setAllIncomes] = useState<IncomeDto[]>([]);
  const [myExpenses, setMyExpenses] = useState<ExpenseDto[]>([]);
  const [allExpenses, setAllExpenses] = useState<ExpenseDto[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [users, setUsers] = useState<UserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [formType, setFormType] = useState<'INCOME' | 'EXPENSE'>('INCOME');

  const [sortField, setSortField] = useState<'date' | 'amount' | 'type'>('date');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');
  const [filterUser, setFilterUser] = useState(searchParams.get('userId') || 'all');
  const [filterType, setFilterType] = useState('all');
  const [filterEntryType, setFilterEntryType] = useState<'all' | 'INCOME' | 'EXPENSE'>('all');
  const [hidePerDiem, setHidePerDiem] = useState(false);
  
  // Автоматически переключаем на 'all' если в URL есть userId или scope=all
  useEffect(() => {
    const userIdFromUrl = searchParams.get('userId');
    const scopeFromUrl = searchParams.get('scope');
    if ((userIdFromUrl || scopeFromUrl === 'all') && canViewAll) {
      setScope('all');
      setFilterUser(userIdFromUrl || 'all');
    }
  }, [searchParams, canViewAll]);
  
  // Фильтр по датам
  const now = new Date();
  const currentMonthStart = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`;
  const currentMonthEnd = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-31`;
  const [dateFrom, setDateFrom] = useState(searchParams.get('dateFrom') || currentMonthStart);
  const [dateTo, setDateTo] = useState(searchParams.get('dateTo') || currentMonthEnd);
  const [datePreset, setDatePreset] = useState<'current' | 'last' | 'custom'>(
    searchParams.get('dateFrom') ? 'custom' : 'current'
  );

  const [form, setForm] = useState({
    projectId: '',
    type: 'ROAD',
    amount: '',
    currency: 'RUB',
    date: new Date().toISOString().slice(0, 10),
    comment: '',
    name: '',
  });
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) try { setUser(JSON.parse(stored)); } catch {}
  }, []);

  const loadData = useCallback(async () => {
    if (!user) return;
    setLoading(true);
    const [myIncRes, allIncRes, myExpRes, allExpRes, projRes, usersRes] = await Promise.allSettled([
      api.get<IncomeDto[]>(`/incomes?userId=${user.id}`),
      canViewAll ? api.get<IncomeDto[]>('/incomes/all') : Promise.resolve({ data: [] }),
      api.get<ExpenseDto[]>(`/expenses?userId=${user.id}`),
      canViewAll ? api.get<ExpenseDto[]>('/expenses/all') : Promise.resolve({ data: [] }),
      api.get<ProjectDto[]>('/projects'),
      canViewAll ? api.get<UserDto[]>('/users') : Promise.resolve({ data: [] }),
    ]);
    setMyIncomes(myIncRes.status === 'fulfilled' ? myIncRes.value.data : []);
    setAllIncomes(allIncRes.status === 'fulfilled' ? allIncRes.value.data : []);
    setMyExpenses(myExpRes.status === 'fulfilled' ? myExpRes.value.data : []);
    setAllExpenses(allExpRes.status === 'fulfilled' ? allExpRes.value.data : []);
    setProjects(projRes.status === 'fulfilled' ? projRes.value.data.filter(p => p.isActive) : []);
    setUsers(usersRes.status === 'fulfilled' ? usersRes.value.data : []);
    setLoading(false);
  }, [user, canViewAll]);

  useEffect(() => { loadData(); }, [loadData]);

  const incomes = effectiveScope === 'all' ? allIncomes : myIncomes;
  const expenses = effectiveScope === 'all' ? allExpenses : myExpenses;
  const myCount = myIncomes.length + myExpenses.length;
  const allCount = allIncomes.length + allExpenses.length;

  // Объединяем доходы и расходы в одну таблицу
  const combinedEntries: CombinedEntry[] = [
    ...incomes.map(i => ({
      id: i.id,
      type: 'INCOME' as const,
      userId: i.userId,
      projectId: i.projectId,
      projectName: i.projectName,
      date: i.date,
      name: findIncomeType(i.name)?.label || i.name,
      category: i.name,
      amount: i.amount,
      currency: i.currency,
      comment: i.comment || '',
    })),
    ...expenses.map(e => ({
      id: e.id,
      type: 'EXPENSE' as const,
      userId: e.userId,
      projectId: e.projectId,
      projectName: e.projectName,
      date: e.date,
      name: e.name || findExpenseType(e.type)?.label || e.type,
      category: e.type === 'PER_DIEM' || e.type === 'per_diem' || e.type === 'perdiem' ? 'per_diem' : (findExpenseType(e.type)?.label || e.type),
      amount: e.amount,
      currency: e.currency,
      comment: e.comment || '',
      hasReceipt: e.receiptSubmitted || e.hasReceiptPhoto,
    })),
  ];

  const filtered = combinedEntries
    .filter(entry => effectiveScope !== 'all' || filterUser === 'all' || entry.userId === filterUser)
    .filter(entry => filterEntryType === 'all' || entry.type === filterEntryType)
    .filter(entry => filterType === 'all' || entry.category === filterType)
    .filter(entry => !hidePerDiem || entry.category !== 'per_diem')
    .filter(entry => entry.date >= dateFrom && entry.date <= dateTo)
    .sort((a, b) => {
      const dir = sortDir === 'asc' ? 1 : -1;
      if (sortField === 'date') return a.date.localeCompare(b.date) * dir;
      if (sortField === 'amount') return (a.amount - b.amount) * dir;
      if (sortField === 'type') return a.type.localeCompare(b.type) * dir;
      return 0;
    });

  // Расчет сальдо: доходы минус расходы (в валютах)
  const saldoByCurrency = filtered.reduce((acc, entry) => {
    if (!acc[entry.currency]) acc[entry.currency] = 0;
    if (entry.type === 'INCOME') {
      acc[entry.currency] += entry.amount;
    } else {
      acc[entry.currency] -= entry.amount;
    }
    return acc;
  }, {} as Record<string, number>);

  const handleCreate = async () => {
    if (!user || !form.amount) return;
    setSaving(true);
    try {
      if (formType === 'INCOME') {
        await api.post('/incomes', {
          id: generateUUID(),
          userId: user.id,
          projectId: form.projectId || null,
          projectName: projects.find(p => p.id === form.projectId)?.name || '',
          date: form.date,
          name: form.type,
          amount: parseFloat(form.amount),
          currency: form.currency,
          comment: form.comment,
        });
      } else {
        await api.post('/expenses', {
          id: generateUUID(),
          userId: user.id,
          projectId: form.projectId || null,
          projectName: projects.find(p => p.id === form.projectId)?.name || '',
          date: form.date,
          type: form.type,
          name: form.type === 'ROAD' ? 'Дорога' : form.name,
          amount: parseFloat(form.amount),
          currency: form.currency,
          comment: form.comment,
        });
      }
      setShowForm(false);
      setForm({ ...form, projectId: '', amount: '', comment: '', name: '' });
      await loadData();
    } catch { alert(`Ошибка создания ${formType === 'INCOME' ? 'дохода' : 'расхода'}`); }
    finally { setSaving(false); }
  };

  const handleDelete = async (id: string, type: 'INCOME' | 'EXPENSE') => {
    if (type === 'INCOME') {
      if (!confirm('Удалить доход?')) return;
      await api.delete('/incomes', { params: { incomeId: id } });
    } else {
      if (!confirm('Удалить расход?')) return;
      await api.delete('/expenses', { params: { expenseId: id } });
    }
    await loadData();
  };

  const toggleSort = (field: 'date' | 'amount' | 'type') => {
    if (sortField === field) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortField(field); setSortDir('desc'); }
  };

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      {/* Заголовок и Сальдо */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">📊 Доходы и Расходы</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            {filtered.length} записей за период с {formatDate(dateFrom)} по {formatDate(dateTo)}
          </p>
        </div>
        <div className="flex gap-2">
          <button onClick={() => { setFormType('INCOME'); setShowForm(!showForm); }} className={`btn-primary px-5 py-2.5 shadow-md ${!showForm || formType === 'INCOME' ? 'shadow-indigo-200' : ''}`}>
            {showForm && formType === 'INCOME' ? '✕ Закрыть' : '＋ Новый доход'}
          </button>
          <button onClick={() => { setFormType('EXPENSE'); setShowForm(true); }} className="bg-red-600 hover:bg-red-700 text-white px-5 py-2.5 rounded-xl font-semibold transition-all shadow-md shadow-red-200 dark:shadow-red-900/30">
            {showForm && formType === 'EXPENSE' ? '✕ Закрыть' : '－ Новый расход'}
          </button>
        </div>
      </div>

      {/* Сальдо по валютам */}
      <div className="card p-4 bg-gradient-to-r from-indigo-50 to-purple-50 dark:from-indigo-950/30 dark:to-purple-950/30 border-indigo-200 dark:border-indigo-800">
        <div className="flex items-center gap-2 mb-2">
          <span className="text-lg font-bold text-indigo-700 dark:text-indigo-300">💰 Сальдо</span>
          <span className="text-xs text-indigo-500 dark:text-indigo-400">(Доходы − Расходы)</span>
        </div>
        <div className="flex flex-wrap gap-4">
          {Object.entries(saldoByCurrency).map(([currency, amount]) => (
            <div key={currency} className="flex items-baseline gap-2">
              <span className={`text-2xl font-bold ${amount >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-600 dark:text-red-400'}`}>
                {amount >= 0 ? '+' : ''}{formatMoney(amount, currency)}
              </span>
            </div>
          ))}
          {Object.keys(saldoByCurrency).length === 0 && (
            <span className="text-slate-400 dark:text-slate-500">Нет данных за выбранный период</span>
          )}
        </div>
      </div>

      <ScopeTabs scope={effectiveScope} onChange={setScope} canViewAll={canViewAll} myCount={myCount} allCount={allCount} />

      {/* 🌲 PROLES MODAL: Новый доход / Новый расход */}
      {showForm && createPortal(
        <div className="proles-modal-backdrop" onClick={() => !saving && setShowForm(false)}>
          <div className="proles-modal" onClick={(e) => e.stopPropagation()}>
            <div className="proles-modal-header">
              <div className="proles-modal-title">
                <div className="proles-modal-icon">{formType === 'INCOME' ? '💵' : '💸'}</div>
                <div>
                  <div>{formType === 'INCOME' ? 'Новый доход' : 'Новый расход'}</div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                    {formType === 'INCOME' ? 'Поступление по проекту' : 'Списание средств'}
                  </div>
                </div>
              </div>
              <button onClick={() => setShowForm(false)} className="proles-modal-close">✕</button>
            </div>
            <div className="proles-modal-body">
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Тип и проект</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group">
                    <label>{formType === 'INCOME' ? 'Тип дохода' : 'Тип расхода'}</label>
                    <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })} className="input bg-white dark:bg-slate-900">
                      {(formType === 'INCOME' ? INCOME_TYPES : EXPENSE_TYPES).map(t => <option key={t.key} value={t.key}>{t.icon} {t.label}</option>)}
                    </select>
                  </div>
                  <div className="proles-input-group">
                    <label>Дата</label>
                    <input type="date" value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} className="input" />
                  </div>
                  <div className="proles-input-group" style={{ gridColumn: 'span 2' }}>
                    <label>Проект (опционально)</label>
                    <select value={form.projectId} onChange={(e) => setForm({ ...form, projectId: e.target.value })} className="input bg-white dark:bg-slate-900">
                      <option value="">Без проекта</option>
                      {projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
                    </select>
                  </div>
                  {formType === 'EXPENSE' && form.type === 'OTHER' && (
                    <div className="proles-input-group" style={{ gridColumn: 'span 2' }}>
                      <label>Название *</label>
                      <input type="text" placeholder="Введите название расхода" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className="input" />
                    </div>
                  )}
                </div>
              </div>
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">{formType === 'INCOME' ? 'Сумма поступления' : 'Сумма расхода'}</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group">
                    <label>Сумма *</label>
                    <input type="number" step="0.01" placeholder="0.00" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} className="input" />
                  </div>
                  <div className="proles-input-group">
                    <label>Валюта</label>
                    <select value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })} className="input bg-white dark:bg-slate-900">
                      <option>RUB</option><option>USD</option><option>EUR</option><option>BYN</option>
                    </select>
                  </div>
                </div>
              </div>
            </div>
            <div className="proles-modal-footer">
              <button onClick={() => setShowForm(false)} className="proles-btn-cancel">Отмена</button>
              <button onClick={handleCreate} disabled={saving || !form.amount || (formType === 'EXPENSE' && form.type === 'OTHER' && !form.name)} className={`proles-btn-save ${formType === 'EXPENSE' ? 'bg-red-600 hover:bg-red-700' : ''}`}>
                {saving ? '⏳ Сохранение...' : '💾 Сохранить'}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* Фильтры */}
      <div className="card p-4 animate-fade-in">
        {/* Предустановки дат */}
        <div className="flex flex-wrap gap-2 mb-3">
          <button
            onClick={() => {
              const now = new Date();
              const year = now.getFullYear();
              const month = String(now.getMonth() + 1).padStart(2, '0');
              setDateFrom(`${year}-${month}-01`);
              setDateTo(`${year}-${month}-31`);
              setDatePreset('current');
            }}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${datePreset === 'current' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}
          >
            Текущий месяц
          </button>
          <button
            onClick={() => {
              const now = new Date();
              const lastMonth = new Date(now.getFullYear(), now.getMonth() - 1, 1);
              const year = lastMonth.getFullYear();
              const month = String(lastMonth.getMonth() + 1).padStart(2, '0');
              const lastDay = new Date(year, lastMonth.getMonth() + 1, 0).getDate();
              setDateFrom(`${year}-${month}-01`);
              setDateTo(`${year}-${month}-${lastDay}`);
              setDatePreset('last');
            }}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${datePreset === 'last' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}
          >
            Прошлый месяц
          </button>
          <button
            onClick={() => setDatePreset('custom')}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${datePreset === 'custom' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}
          >
            Произвольный
          </button>
        </div>
        
        {/* Поля ввода дат */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-3">
          <div>
            <label className="text-xs text-slate-500 dark:text-slate-400 block mb-1">С даты</label>
            <input type="date" value={dateFrom} onChange={(e) => { setDateFrom(e.target.value); setDatePreset('custom'); }} className="input text-sm" />
          </div>
          <div>
            <label className="text-xs text-slate-500 dark:text-slate-400 block mb-1">По дату</label>
            <input type="date" value={dateTo} onChange={(e) => { setDateTo(e.target.value); setDatePreset('custom'); }} className="input text-sm" />
          </div>
        </div>

        {/* Остальные фильтры — только в режиме «Все» */}
        {effectiveScope === 'all' && (
          <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
            <select value={filterUser} onChange={(e) => setFilterUser(e.target.value)} className="input bg-white dark:bg-slate-900">
              <option value="all">Все сотрудники</option>
              {users.map(u => <option key={u.id} value={u.id}>{u.name}</option>)}
            </select>
            <select value={filterEntryType} onChange={(e) => setFilterEntryType(e.target.value as 'all' | 'INCOME' | 'EXPENSE')} className="input bg-white dark:bg-slate-900">
              <option value="all">Все типы записей</option>
              <option value="INCOME">📈 Доходы</option>
              <option value="EXPENSE">📉 Расходы</option>
            </select>
            <select value={filterType} onChange={(e) => setFilterType(e.target.value)} className="input bg-white dark:bg-slate-900">
              <option value="all">Все категории</option>
              {[...INCOME_TYPES, ...EXPENSE_TYPES].map(t => <option key={t.key} value={t.key}>{t.icon} {t.label}</option>)}
            </select>
          </div>
        )}
        
        {/* Галочка "Скрыть суточные" */}
        <div className="flex items-center gap-2 mt-3 pt-3 border-t border-slate-200 dark:border-slate-700">
          <input 
            type="checkbox" 
            id="hidePerDiem"
            checked={hidePerDiem}
            onChange={(e) => setHidePerDiem(e.target.checked)}
            className="w-4 h-4 text-indigo-600 border-slate-300 rounded focus:ring-indigo-500"
          />
          <label htmlFor="hidePerDiem" className="text-sm font-medium text-slate-700 dark:text-slate-300 cursor-pointer select-none">
            🚫 Скрыть суточные
          </label>
        </div>
      </div>

      {/* Сортировка */}
      <div className="flex gap-2 flex-wrap">
        <button onClick={() => toggleSort('date')} className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${sortField === 'date' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}>
          📅 Дата {sortField === 'date' ? (sortDir === 'desc' ? '↓' : '↑') : ''}
        </button>
        <button onClick={() => toggleSort('amount')} className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${sortField === 'amount' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}>
          💰 Сумма {sortField === 'amount' ? (sortDir === 'desc' ? '↓' : '↑') : ''}
        </button>
        <button onClick={() => toggleSort('type')} className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${sortField === 'type' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}>
          🏷 Тип {sortField === 'type' ? (sortDir === 'desc' ? '↓' : '↑') : ''}
        </button>
      </div>

      {/* Таблица */}
      {loading ? (
        <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>
      ) : filtered.length === 0 ? (
        <div className="card p-12 text-center border-dashed border-2 border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-900/50">
          <div className="text-5xl mb-4 opacity-50">📊</div>
          <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Нет записей</h3>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-2">Измените параметры фильтра или добавьте новую запись</p>
        </div>
      ) : (
        <div className="card overflow-hidden animate-fade-in">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700">
                <tr>
                  <th className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">Дата</th>
                  <th className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">Тип</th>
                  <th className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">Проект</th>
                  <th className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">Сотрудник</th>
                  <th className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">Название</th>
                  <th className="px-4 py-3 text-right font-semibold text-slate-700 dark:text-slate-300">Сумма</th>
                  <th className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">Валюта</th>
                  <th className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">Комментарий</th>
                  <th className="px-4 py-3 text-right font-semibold text-slate-700 dark:text-slate-300"></th>
                </tr>
              </thead>
              <tbody>
                {filtered.map(entry => {
                  const userName = effectiveScope === 'all' ? users.find(u => u.id === entry.userId)?.name : null;
                  // Цвет фона: доходы - белый, расходы с чеком - зеленоватый, без чека - красноватый
                  let rowBgClass = 'bg-white dark:bg-slate-900';
                  if (entry.type === 'EXPENSE') {
                    rowBgClass = entry.hasReceipt 
                      ? 'bg-emerald-50 dark:bg-emerald-950/20' 
                      : 'bg-red-50 dark:bg-red-950/20';
                  }
                  return (
                    <tr key={entry.id} className={`border-b border-slate-100 dark:border-slate-800 hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors ${rowBgClass}`}>
                      <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{formatDate(entry.date)}</td>
                      <td className="px-4 py-3">
                        <span className={`px-2 py-1 rounded-full text-xs font-bold ${entry.type === 'INCOME' ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-400' : 'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-400'}`}>
                          {entry.type === 'INCOME' ? '📈 Доход' : '📉 Расход'}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-slate-900 dark:text-slate-100 font-medium">{entry.projectName || '—'}</td>
                      <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{userName || '—'}</td>
                      <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{entry.name}</td>
                      <td className={`px-4 py-3 text-right font-bold ${entry.type === 'INCOME' ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-600 dark:text-red-400'}`}>
                        {entry.type === 'INCOME' ? '+' : '-'}{entry.amount.toFixed(2)}
                      </td>
                      <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{entry.currency}</td>
                      <td className="px-4 py-3 text-slate-600 dark:text-slate-400 max-w-xs truncate" title={entry.comment}>
                        {entry.comment || '—'}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <button 
                          onClick={() => handleDelete(entry.id, entry.type)}
                          className="text-xs font-medium text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30 px-2 py-1 rounded-lg transition-all"
                          title="Удалить"
                        >
                          🗑
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}