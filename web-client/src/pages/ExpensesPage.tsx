import { useState, useEffect, useCallback, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import { createPortal } from 'react-dom';
import api from '../api/client';
import type { ExpenseDto, ProjectDto, UserDto } from '../types';
import { generateUUID, formatDate, fileToBase64, formatMoney } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';
import { ScopeTabs } from '../components/ScopeTabs';

const EXPENSE_TYPES = [
  { key: 'CONTRACTORS', label: '👷 Подрядчики', color: 'bg-purple-100 text-purple-700 border-purple-200 dark:bg-purple-950/40 dark:text-purple-400 dark:border-purple-900' },
  { key: 'MATERIALS', label: '🧱 Материалы', color: 'bg-orange-100 text-orange-700 border-orange-200 dark:bg-orange-950/40 dark:text-orange-400 dark:border-orange-900' },
  { key: 'EQUIPMENT', label: '🔧 Оборудование', color: 'bg-blue-100 text-blue-700 border-blue-200 dark:bg-blue-950/40 dark:text-blue-400 dark:border-blue-900' },
  { key: 'TRANSPORT', label: '🚚 Транспорт Доп.', color: 'bg-cyan-100 text-cyan-700 border-cyan-200 dark:bg-cyan-950/40 dark:text-cyan-400 dark:border-cyan-900' },
  { key: 'ROAD', label: '🚗 Транспорт', color: 'bg-teal-100 text-teal-700 border-teal-200 dark:bg-teal-950/40 dark:text-teal-400 dark:border-teal-900' },
  { key: 'MANAGER_COMMISSION', label: '💼 Комиссия', color: 'bg-indigo-100 text-indigo-700 border-indigo-200 dark:bg-indigo-950/40 dark:text-indigo-400 dark:border-indigo-900' },
  { key: 'FINES', label: '⚠️ Штрафы', color: 'bg-red-100 text-red-700 border-red-200 dark:bg-red-950/40 dark:text-red-400 dark:border-red-900' },
  { key: 'CREDIT', label: '🏦 Кредит', color: 'bg-pink-100 text-pink-700 border-pink-200 dark:bg-pink-950/40 dark:text-pink-400 dark:border-pink-900' },
  { key: 'OTHER', label: '📦 Другое', color: 'bg-slate-100 text-slate-700 border-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:border-slate-700' },
];

export function ExpensesPage() {
  const [searchParams] = useSearchParams();
  const [user, setUser] = useState<UserDto | null>(null);
  const { can } = usePermissions();
  const canViewAll = can('expenses_all', 'view');

  // 🎯 Область видимости: 'my' — свои, 'all' — все (только при наличии права)
  const [scope, setScope] = useState<'my' | 'all'>('my');
  const effectiveScope = canViewAll ? scope : 'my';

  const [myExpenses, setMyExpenses] = useState<ExpenseDto[]>([]);
  const [allExpenses, setAllExpenses] = useState<ExpenseDto[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [users, setUsers] = useState<UserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [sortField, setSortField] = useState<'date' | 'amount'>('date');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');
  const [filterUser, setFilterUser] = useState(searchParams.get('userId') || 'all');
  const [filterProject, setFilterProject] = useState('all');
  const [filterType, setFilterType] = useState('all');
  
  // Состояние формы
  const [form, setForm] = useState({ projectId: '', date: new Date().toISOString().split('T')[0], type: 'OTHER', amount: '', currency: 'RUB', name: '' });
  const [saving, setSaving] = useState(false);
  const [uploadingReceipt, setUploadingReceipt] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [selectedExpenseForReceipt, setSelectedExpenseForReceipt] = useState<string | null>(null);
  
  // Фильтр по датам
  const now = new Date();
  const currentMonthStart = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`;
  const currentMonthEnd = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-31`;
  const [dateFrom, setDateFrom] = useState(searchParams.get('dateFrom') || currentMonthStart);
  const [dateTo, setDateTo] = useState(searchParams.get('dateTo') || currentMonthEnd);
  const [datePreset, setDatePreset] = useState<'current' | 'last' | 'custom'>(
    searchParams.get('dateFrom') ? 'custom' : 'current'
  );

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) try { setUser(JSON.parse(stored)); } catch {}
  }, []);

  const loadData = useCallback(async () => {
    if (!user) return;
    setLoading(true);
    const [myExpRes, allExpRes, projRes, usersRes] = await Promise.allSettled([
      api.get<ExpenseDto[]>(`/expenses?userId=${user.id}`),
      canViewAll ? api.get<ExpenseDto[]>('/expenses/all') : Promise.resolve({ data: [] }),
      api.get<ProjectDto[]>('/projects'),
      canViewAll ? api.get<UserDto[]>('/users') : Promise.resolve({ data: [] }),
    ]);
    setMyExpenses(myExpRes.status === 'fulfilled' ? myExpRes.value.data : []);
    setAllExpenses(allExpRes.status === 'fulfilled' ? allExpRes.value.data : []);
    setProjects(projRes.status === 'fulfilled' ? projRes.value.data.filter(p => p.isActive) : []);
    // 🆕 Фильтруем пользователя COMPANY из списка сотрудников (не показываем в фильтрах)
    setUsers(usersRes.status === 'fulfilled' ? usersRes.value.data.filter(u => u.name !== 'COMPANY') : []);
    setLoading(false);
  }, [user, canViewAll]);

  useEffect(() => { loadData(); }, [loadData]);

  const expenses = effectiveScope === 'all' ? allExpenses : myExpenses;
  const myCount = myExpenses.length;
  const allCount = allExpenses.length;
  const filtered = expenses
    .filter(e => effectiveScope !== 'all' || filterUser === 'all' || e.userId === filterUser)
    .filter(e => effectiveScope !== 'all' || filterProject === 'all' || e.projectId === filterProject)
    .filter(e => effectiveScope !== 'all' || filterType === 'all' || e.type === filterType)
    .filter(e => e.date >= dateFrom && e.date <= dateTo)
    .sort((a, b) => {
      const dir = sortDir === 'asc' ? 1 : -1;
      return sortField === 'date' ? a.date.localeCompare(b.date) * dir : (a.amount - b.amount) * dir;
    });

  const totalAmount = filtered.reduce((s, e) => s + e.amount, 0);

  const handleCreate = async () => {
    if (!user || !form.projectId || !form.amount) return;
    setSaving(true);
    try {
      await api.post('/expenses', {
        id: generateUUID(), userId: user.id, projectId: form.projectId,
        projectName: projects.find(p => p.id === form.projectId)?.name || '',
        date: form.date, type: form.type,
        name: form.name || EXPENSE_TYPES.find(t => t.key === form.type)?.label || '',
        amount: parseFloat(form.amount), currency: form.currency,
        comment: '', receiptSubmitted: false, hasReceiptPhoto: false,
      });
      setShowForm(false);
      setForm({ ...form, projectId: '', name: '', amount: '' });
      await loadData();
    } catch { alert('Ошибка создания расхода'); }
    finally { setSaving(false); }
  };

  const handleUploadReceipt = async (expenseId: string, file: File) => {
    setUploadingReceipt(expenseId);
    try {
      const base64 = await fileToBase64(file);
      await api.post('/expenses/upload-receipt', { expenseId, imageBase64: base64 });
      await loadData();
    } catch { alert('Ошибка загрузки чека'); }
    finally { setUploadingReceipt(null); }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Удалить расход?')) return;
    await api.delete('/expenses', { params: { expenseId: id } });
    await loadData();
  };

  const toggleSort = (field: 'date' | 'amount') => {
    if (sortField === field) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortField(field); setSortDir('desc'); }
  };

  return (
    <div className="space-y-6 max-w-5xl mx-auto">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">💸 Расходы</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            {filtered.length} записей • Итого: <span className="font-bold text-slate-900 dark:text-slate-100">{formatMoney(totalAmount)}</span>
          </p>
        </div>
        <button onClick={() => setShowForm(!showForm)} className="btn-primary px-5 py-2.5 shadow-indigo-200 shadow-md">
          {showForm ? '✕ Закрыть' : '＋ Новый расход'}
        </button>
      </div>

      {/* 🎯 Мои / Все */}
      <ScopeTabs scope={effectiveScope} onChange={setScope} canViewAll={canViewAll} myCount={myCount} allCount={allCount} />

      {/* 🌲 PROLES MODAL: Новый расход */}
      {showForm && createPortal(
        <div className="proles-modal-backdrop" onClick={() => !saving && setShowForm(false)}>
          <div className="proles-modal" onClick={(e) => e.stopPropagation()}>
            <div className="proles-modal-header">
              <div className="proles-modal-title">
                <div className="proles-modal-icon">💸</div>
                <div>
                  <div>Новый расход</div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                    Финансовая операция по проекту
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
                    <label>Дата</label>
                    <input type="date" value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} className="input" />
                  </div>
                  <div className="proles-input-group">
                    <label>Тип расхода</label>
                    <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })} className="input bg-white dark:bg-slate-900">
                      {EXPENSE_TYPES.map(t => <option key={t.key} value={t.key}>{t.label}</option>)}
                    </select>
                  </div>
                </div>
              </div>
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Финансовые детали</div>
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
                  <div className="proles-input-group" style={{ gridColumn: 'span 2' }}>
                    <label>Название (опционально)</label>
                    <input type="text" placeholder="Например: Цемент 50 мешков" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className="input" />
                  </div>
                </div>
              </div>
            </div>
            <div className="proles-modal-footer">
              <button onClick={() => setShowForm(false)} className="proles-btn-cancel">Отмена</button>
              <button onClick={handleCreate} disabled={saving || !form.projectId || !form.amount} className="proles-btn-save">
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
            <select value={filterProject} onChange={(e) => setFilterProject(e.target.value)} className="input bg-white dark:bg-slate-900">
              <option value="all">Все проекты</option>
              {projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
            <select value={filterType} onChange={(e) => setFilterType(e.target.value)} className="input bg-white dark:bg-slate-900">
              <option value="all">Все типы</option>
              {EXPENSE_TYPES.map(t => <option key={t.key} value={t.key}>{t.label}</option>)}
            </select>
          </div>
        )}
      </div>

      {/* Сортировка */}
      <div className="flex gap-2">
        <button onClick={() => toggleSort('date')} className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${sortField === 'date' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}>
          📅 Дата {sortField === 'date' ? (sortDir === 'desc' ? '↓' : '↑') : ''}
        </button>
        <button onClick={() => toggleSort('amount')} className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${sortField === 'amount' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}>
          💰 Сумма {sortField === 'amount' ? (sortDir === 'desc' ? '↓' : '↑') : ''}
        </button>
      </div>

      {/* Список */}
      {loading ? (
        <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>
      ) : filtered.length === 0 ? (
        <div className="card p-12 text-center border-dashed border-2 border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-900/50">
          <div className="text-5xl mb-4 opacity-50">💸</div>
          <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Нет расходов</h3>
        </div>
      ) : (
        <div className="space-y-3">
          {filtered.map(expense => {
            const type = EXPENSE_TYPES.find(t => t.key === expense.type) || EXPENSE_TYPES[8];
            const userName = effectiveScope === 'all' ? users.find(u => u.id === expense.userId)?.name : null;
            // 🆕 Проверяем, является ли расход расходом компании
            const isCompanyExpense = expense.userId === users.find(u => u.name === 'COMPANY')?.id || 
                                     (userName === null && effectiveScope === 'all');
            return (
              <div key={expense.id} className="card p-4 md:p-5 group hover:shadow-md transition-all animate-fade-in">
                <div className="flex items-start justify-between gap-4 mb-2">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 flex-wrap mb-1">
                      <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold border ${type.color}`}>{type.label}</span>
                      <span className="text-xs text-slate-400">{formatDate(expense.date)}</span>
                      {userName && <span className="text-xs font-medium text-indigo-600 dark:text-indigo-400">👤 {userName}</span>}
                      {isCompanyExpense && !userName && <span className="text-xs font-medium text-amber-600 dark:text-amber-400">🏢 Компания</span>}
                    </div>
                    <div className="font-bold text-slate-900 dark:text-slate-100 truncate">{expense.projectName}</div>
                    {expense.name && <div className="text-sm text-slate-600 dark:text-slate-400 truncate">{expense.name}</div>}
                  </div>
                  <div className="text-xl font-bold text-slate-900 dark:text-slate-100 flex-shrink-0">{formatMoney(expense.amount, expense.currency)}</div>
                </div>
                <div className="flex items-center gap-2 pt-3 border-t border-slate-100 dark:border-slate-800">
                  {expense.hasReceiptPhoto ? (
                    <span className="text-xs font-medium text-green-600 dark:text-green-400 bg-green-50 dark:bg-green-950/30 px-2 py-1 rounded-lg">✓ Чек</span>
                  ) : (
                    <>
                      <input ref={fileInputRef} type="file" accept="image/*" className="hidden"
                        onChange={(e) => { const f = e.target.files?.[0]; if (f && selectedExpenseForReceipt) { handleUploadReceipt(selectedExpenseForReceipt, f); } e.target.value = ''; setSelectedExpenseForReceipt(null); }} />
                      <button onClick={() => { setSelectedExpenseForReceipt(expense.id); fileInputRef.current?.click(); }} disabled={uploadingReceipt === expense.id}
                        className="text-xs font-medium text-indigo-600 dark:text-indigo-400 hover:bg-indigo-50 dark:hover:bg-indigo-950/30 px-2 py-1 rounded-lg">
                        {uploadingReceipt === expense.id ? '⏳...' : '📷 Чек'}
                      </button>
                    </>
                  )}
                  <button onClick={() => handleDelete(expense.id)} className="ml-auto text-xs font-medium text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30 px-2 py-1 rounded-lg opacity-0 group-hover:opacity-100 transition-all">🗑</button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}