import { useState, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import api from '../api/client';
import type { IncomeDto, ProjectDto, UserDto } from '../types';
import { generateUUID, formatDate, formatMoney } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';
import { ScopeTabs } from '../components/ScopeTabs';

const INCOME_TYPES = [
  { key: 'HOUSEHOLD', label: 'Хоз.нужды', icon: '🏠', color: 'bg-blue-100 text-blue-700 border-blue-200 dark:bg-blue-950/40 dark:text-blue-400 dark:border-blue-900' },
  { key: 'CARD', label: 'По карте', icon: '💳', color: 'bg-purple-100 text-purple-700 border-purple-200 dark:bg-purple-950/40 dark:text-purple-400 dark:border-purple-900' },
  { key: 'CASH', label: 'Наличными', icon: '💵', color: 'bg-emerald-100 text-emerald-700 border-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-400 dark:border-emerald-900' },
];

// Тип хранится в поле name (в DTO нет отдельного поля type)
const findType = (key: string) => INCOME_TYPES.find(t => t.key === key);

export function IncomesPage() {
  const [user, setUser] = useState<UserDto | null>(null);
  const { can } = usePermissions();
  const canViewAll = can('expenses_all', 'view');

  const [scope, setScope] = useState<'my' | 'all'>('my');
  const effectiveScope = canViewAll ? scope : 'my';

  const [myIncomes, setMyIncomes] = useState<IncomeDto[]>([]);
  const [allIncomes, setAllIncomes] = useState<IncomeDto[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [users, setUsers] = useState<UserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);

  const [sortField, setSortField] = useState<'date' | 'amount'>('date');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');
  const [filterUser, setFilterUser] = useState('all');
  const [filterType, setFilterType] = useState('all');

  const [form, setForm] = useState({
    projectId: '',
    type: 'CASH',
    amount: '',
    currency: 'RUB',
    date: new Date().toISOString().slice(0, 10),
  });
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) try { setUser(JSON.parse(stored)); } catch {}
  }, []);

  const loadData = useCallback(async () => {
    if (!user) return;
    setLoading(true);
    const [myIncRes, allIncRes, projRes, usersRes] = await Promise.allSettled([
      api.get<IncomeDto[]>(`/incomes?userId=${user.id}`),
      canViewAll ? api.get<IncomeDto[]>('/incomes/all') : Promise.resolve({ data: [] }),
      api.get<ProjectDto[]>('/projects'),
      canViewAll ? api.get<UserDto[]>('/users') : Promise.resolve({ data: [] }),
    ]);
    setMyIncomes(myIncRes.status === 'fulfilled' ? myIncRes.value.data : []);
    setAllIncomes(allIncRes.status === 'fulfilled' ? allIncRes.value.data : []);
    setProjects(projRes.status === 'fulfilled' ? projRes.value.data.filter(p => p.isActive) : []);
    setUsers(usersRes.status === 'fulfilled' ? usersRes.value.data : []);
    setLoading(false);
  }, [user, canViewAll]);

  useEffect(() => { loadData(); }, [loadData]);

  const incomes = effectiveScope === 'all' ? allIncomes : myIncomes;
  const myCount = myIncomes.length;
  const allCount = allIncomes.length;
  const filtered = incomes
    .filter(i => effectiveScope !== 'all' || filterUser === 'all' || i.userId === filterUser)
    .filter(i => effectiveScope !== 'all' || filterType === 'all' || i.name === filterType)
    .sort((a, b) => {
      const dir = sortDir === 'asc' ? 1 : -1;
      return sortField === 'date' ? a.date.localeCompare(b.date) * dir : (a.amount - b.amount) * dir;
    });

  const totalAmount = filtered.reduce((s, i) => s + i.amount, 0);

  const handleCreate = async () => {
    if (!user || !form.amount) return;
    setSaving(true);
    try {
      await api.post('/incomes', {
        id: generateUUID(),
        userId: user.id,
        projectId: form.projectId || null,
        projectName: projects.find(p => p.id === form.projectId)?.name || '',
        date: form.date,
        name: form.type, // сохраняем ключ типа
        amount: parseFloat(form.amount),
        currency: form.currency,
      });
      setShowForm(false);
      setForm({ ...form, projectId: '', amount: '' });
      await loadData();
    } catch { alert('Ошибка создания дохода'); }
    finally { setSaving(false); }
  };

  const handleDelete = async (id: string) => {
    if (!confirm('Удалить доход?')) return;
    await api.delete('/incomes', { params: { incomeId: id } });
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
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">💵 Доходы</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            {filtered.length} записей • Итого: <span className="font-bold text-emerald-600 dark:text-emerald-400">{formatMoney(totalAmount)}</span>
          </p>
        </div>
        <button onClick={() => setShowForm(!showForm)} className="btn-primary px-5 py-2.5 shadow-indigo-200 shadow-md">
          {showForm ? '✕ Закрыть' : '＋ Новый доход'}
        </button>
      </div>

      <ScopeTabs scope={effectiveScope} onChange={setScope} canViewAll={canViewAll} myCount={myCount} allCount={allCount} />

      {/* 🌲 PROLES MODAL: Новый доход */}
      {showForm && createPortal(
        <div className="proles-modal-backdrop" onClick={() => !saving && setShowForm(false)}>
          <div className="proles-modal" onClick={(e) => e.stopPropagation()}>
            <div className="proles-modal-header">
              <div className="proles-modal-title">
                <div className="proles-modal-icon">💵</div>
                <div>
                  <div>Новый доход</div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                    Поступление по проекту
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
                    <label>Тип дохода</label>
                    <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })} className="input bg-white dark:bg-slate-900">
                      {INCOME_TYPES.map(t => <option key={t.key} value={t.key}>{t.icon} {t.label}</option>)}
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
                </div>
              </div>
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Сумма поступления</div>
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
              <button onClick={handleCreate} disabled={saving || !form.amount} className="proles-btn-save">
                {saving ? '⏳ Сохранение...' : '💾 Сохранить'}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* Фильтры — только в режиме «Все» */}
      {effectiveScope === 'all' && (
        <div className="card p-4 animate-fade-in">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <select value={filterUser} onChange={(e) => setFilterUser(e.target.value)} className="input bg-white dark:bg-slate-900">
              <option value="all">Все сотрудники</option>
              {users.map(u => <option key={u.id} value={u.id}>{u.name}</option>)}
            </select>
            <select value={filterType} onChange={(e) => setFilterType(e.target.value)} className="input bg-white dark:bg-slate-900">
              <option value="all">Все типы</option>
              {INCOME_TYPES.map(t => <option key={t.key} value={t.key}>{t.icon} {t.label}</option>)}
            </select>
          </div>
        </div>
      )}

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
          <div className="text-5xl mb-4 opacity-50">💵</div>
          <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Нет доходов</h3>
        </div>
      ) : (
        <div className="space-y-3">
          {filtered.map(income => {
            const type = findType(income.name);
            const userName = effectiveScope === 'all' ? users.find(u => u.id === income.userId)?.name : null;
            return (
              <div key={income.id} className="card p-4 md:p-5 group hover:shadow-md transition-all animate-fade-in">
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 flex-wrap mb-1">
                      {type ? (
                        <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold border ${type.color}`}>
                          {type.icon} {type.label}
                        </span>
                      ) : (
                        <span className="text-sm font-medium text-slate-700 dark:text-slate-300">{income.name}</span>
                      )}
                      <span className="text-xs text-slate-400">{formatDate(income.date)}</span>
                      {userName && <span className="text-xs font-medium text-indigo-600 dark:text-indigo-400">👤 {userName}</span>}
                    </div>
                    <div className="font-bold text-slate-900 dark:text-slate-100 truncate">
                      {income.projectName || 'Без проекта'}
                    </div>
                  </div>
                  <div className="text-xl font-bold text-emerald-600 dark:text-emerald-400 flex-shrink-0">
                    +{formatMoney(income.amount, income.currency)}
                  </div>
                </div>
                <div className="flex items-center justify-end pt-3 mt-2 border-t border-slate-100 dark:border-slate-800">
                  <button onClick={() => handleDelete(income.id)} className="text-xs font-medium text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30 px-2 py-1 rounded-lg opacity-0 group-hover:opacity-100 transition-all">🗑</button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}