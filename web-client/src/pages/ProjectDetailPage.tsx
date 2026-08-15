import { useState, useEffect, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import api from '../api/client';
import type { ProjectDto, TimeEntryDto, ExpenseDto, IncomeDto } from '../types';
import { formatMoney, formatDate } from '../lib/utils';

export function ProjectDetailPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const [project, setProject] = useState<ProjectDto | null>(null);
  const [entries, setEntries] = useState<TimeEntryDto[]>([]);
  const [expenses, setExpenses] = useState<ExpenseDto[]>([]);
  const [incomes, setIncomes] = useState<IncomeDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<'overview' | 'hours' | 'expenses'>('overview');

  useEffect(() => {
    if (!projectId) return;
    (async () => {
      setLoading(true);
      const [projRes, entRes, expRes, incRes] = await Promise.allSettled([
        api.get<ProjectDto[]>('/projects'),
        api.get<TimeEntryDto[]>('/entries/all'),
        api.get<ExpenseDto[]>('/expenses/all'),
        api.get<IncomeDto[]>('/incomes/all'),
      ]);
      const projects = projRes.status === 'fulfilled' ? projRes.value.data : [];
      const found = projects.find(p => p.id === projectId);
      setProject(found || null);
      const allEntries = entRes.status === 'fulfilled' ? entRes.value.data : [];
      const allExpenses = expRes.status === 'fulfilled' ? expRes.value.data : [];
      const allIncomes = incRes.status === 'fulfilled' ? incRes.value.data : [];
      setEntries(allEntries.filter(e => e.projectId === projectId));
      setExpenses(allExpenses.filter(e => e.projectId === projectId));
      setIncomes(allIncomes.filter(i => i.projectId === projectId));
      setLoading(false);
    })();
  }, [projectId]);

  const stats = useMemo(() => {
    const totalHours = entries.reduce((s, e) => s + e.hours, 0);
    const totalExpenses = expenses.reduce((s, e) => s + e.amount, 0);
    const totalIncomes = incomes.reduce((s, e) => s + e.amount, 0);
    const workDays = new Set(entries.map(e => e.date)).size;
    const expensesByType = expenses.reduce((acc, e) => {
      acc[e.type] = (acc[e.type] || 0) + e.amount;
      return acc;
    }, {} as Record<string, number>);
    const incomesByType = incomes.reduce((acc, i) => {
      acc[i.name] = (acc[i.name] || 0) + i.amount;
      return acc;
    }, {} as Record<string, number>);
    return { totalHours, totalExpenses, totalIncomes, workDays, expensesByType, incomesByType };
  }, [entries, expenses, incomes]);

  const STATUS_CONFIG: Record<string, { label: string; emoji: string; color: string }> = {
    new: { label: 'Новый', emoji: '🆕', color: 'from-blue-500 to-indigo-500' },
    in_progress: { label: 'В работе', emoji: '🔄', color: 'from-orange-500 to-amber-500' },
    completed: { label: 'Завершён', emoji: '✅', color: 'from-emerald-500 to-green-500' },
    cancelled: { label: 'Отменён', emoji: '❌', color: 'from-red-500 to-rose-500' },
  };

  if (loading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;
  if (!project) return (
    <div className="card p-12 text-center">
      <div className="text-5xl mb-4">🔍</div>
      <h3 className="text-lg font-semibold">Проект не найден</h3>
      <button onClick={() => navigate('/projects')} className="btn-primary mt-4 px-5 py-2">← Назад к проектам</button>
    </div>
  );

  const st = STATUS_CONFIG[project.status] || STATUS_CONFIG.new;
  const profit = stats.totalIncomes - stats.totalExpenses;

  return (
    <div className="space-y-6 max-w-6xl mx-auto">
      {/* Кнопка назад */}
      <button onClick={() => navigate('/projects')} className="btn-ghost text-sm gap-2 text-slate-500 dark:text-slate-400">
        ← Назад к проектам
      </button>

      {/* 🌲 Hero-карточка проекта */}
      <div className={`relative overflow-hidden rounded-2xl bg-gradient-to-br ${st.color} p-6 md:p-8 text-white shadow-xl`}>
        <div className="absolute top-0 right-0 -mt-8 -mr-8 w-40 h-40 bg-white/10 rounded-full blur-2xl"></div>
        <div className="absolute bottom-0 left-0 -mb-8 -ml-8 w-32 h-32 bg-white/10 rounded-full blur-2xl"></div>
        <div className="relative z-10">
          <div className="flex items-start justify-between gap-4">
            <div>
              <div className="text-sm font-medium text-white/80 mb-1">{st.emoji} {st.label}</div>
              <h1 className="text-2xl md:text-3xl font-bold mb-2">{project.name}</h1>
              <div className="flex flex-wrap gap-3 text-sm text-white/90">
                {project.client && <span>👤 {project.client}</span>}
                {project.location && <span>📍 {project.location}</span>}
                {project.contract && <span>📄 {project.contract}</span>}
              </div>
            </div>
            <div className="text-right">
              <div className="text-3xl font-black">{stats.totalHours}</div>
              <div className="text-xs text-white/70 uppercase font-bold">часов</div>
            </div>
          </div>
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div className="card p-4 bg-gradient-to-br from-blue-50 to-indigo-50 dark:from-blue-950/30 dark:to-indigo-950/30 border-blue-100 dark:border-blue-900">
          <div className="text-xs font-bold text-blue-600 dark:text-blue-400 uppercase">Часы</div>
          <div className="text-2xl font-black text-blue-900 dark:text-blue-100 mt-1">{stats.totalHours.toFixed(1)}ч</div>
          <div className="text-[10px] text-blue-500 mt-0.5">{stats.workDays} рабочих дней</div>
        </div>
        <div className="card p-4 bg-gradient-to-br from-orange-50 to-amber-50 dark:from-orange-950/30 dark:to-amber-950/30 border-orange-100 dark:border-orange-900">
          <div className="text-xs font-bold text-orange-600 dark:text-orange-400 uppercase">Расходы</div>
          <div className="text-2xl font-black text-orange-900 dark:text-orange-100 mt-1">{formatMoney(stats.totalExpenses)}</div>
          <div className="text-[10px] text-orange-500 mt-0.5">{expenses.length} записей</div>
        </div>
        <div className="card p-4 bg-gradient-to-br from-emerald-50 to-green-50 dark:from-emerald-950/30 dark:to-green-950/30 border-emerald-100 dark:border-emerald-900">
          <div className="text-xs font-bold text-emerald-600 dark:text-emerald-400 uppercase">Расходы/Доходы</div>
          <div className="text-2xl font-black text-emerald-900 dark:text-emerald-100 mt-1">{formatMoney(stats.totalIncomes - stats.totalExpenses)}</div>
          <div className="text-[10px] text-emerald-500 mt-0.5">Сальдо проекта</div>
        </div>
        <div className={`card p-4 border ${profit >= 0 ? 'bg-gradient-to-br from-green-50 to-emerald-50 dark:from-green-950/30 dark:to-emerald-950/30 border-green-100 dark:border-green-900' : 'bg-gradient-to-br from-red-50 to-rose-50 dark:from-red-950/30 dark:to-rose-950/30 border-red-100 dark:border-red-900'}`}>
          <div className={`text-xs font-bold uppercase ${profit >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'}`}>Прибыль</div>
          <div className={`text-2xl font-black mt-1 ${profit >= 0 ? 'text-green-900 dark:text-green-100' : 'text-red-900 dark:text-red-100'}`}>{formatMoney(profit)}</div>
          <div className={`text-[10px] mt-0.5 ${profit >= 0 ? 'text-green-500' : 'text-red-500'}`}>{stats.totalIncomes > 0 ? `${((profit / stats.totalIncomes) * 100).toFixed(1)}% маржа` : '—'}</div>
        </div>
      </div>

      {/* Доп. информация о проекте */}
      {(project.productService || project.quantity > 1 || project.deliveryDate || project.notes) && (
        <div className="card p-5">
          <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-3 flex items-center gap-2">
            <span className="w-1.5 h-5 bg-indigo-500 rounded-full"></span>
            Детали проекта
          </h3>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-sm">
            {project.productService && (
              <div className="p-3 bg-slate-50 dark:bg-slate-800/50 rounded-lg">
                <div className="text-xs text-slate-500 dark:text-slate-400">🎯 Товар / Услуга</div>
                <div className="font-medium text-slate-900 dark:text-slate-100 mt-0.5">{project.productService}</div>
              </div>
            )}
            {project.quantity > 1 && (
              <div className="p-3 bg-slate-50 dark:bg-slate-800/50 rounded-lg">
                <div className="text-xs text-slate-500 dark:text-slate-400">📦 Количество</div>
                <div className="font-medium text-slate-900 dark:text-slate-100 mt-0.5">{project.quantity} шт.</div>
              </div>
            )}
            {project.deliveryDate && (
              <div className="p-3 bg-slate-50 dark:bg-slate-800/50 rounded-lg">
                <div className="text-xs text-slate-500 dark:text-slate-400">📅 Срок поставки</div>
                <div className="font-medium text-slate-900 dark:text-slate-100 mt-0.5">{formatDate(project.deliveryDate)}</div>
              </div>
            )}
            {project.notes && (
              <div className="p-3 bg-amber-50 dark:bg-amber-950/20 rounded-lg md:col-span-2">
                <div className="text-xs text-amber-600 dark:text-amber-400">📝 Заметки</div>
                <div className="font-medium text-slate-900 dark:text-slate-100 mt-0.5">{project.notes}</div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Табы */}
      <div className="flex gap-1 bg-white dark:bg-slate-900 rounded-xl p-1 border border-slate-200 dark:border-slate-700 w-fit">
        <button onClick={() => setActiveTab('overview')} className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${activeTab === 'overview' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'text-slate-600 dark:text-slate-400'}`}>
          📊 Обзор
        </button>
        <button onClick={() => setActiveTab('hours')} className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${activeTab === 'hours' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'text-slate-600 dark:text-slate-400'}`}>
          ⏱ Часы ({entries.length})
        </button>
        <button onClick={() => setActiveTab('expenses')} className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${activeTab === 'expenses' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'text-slate-600 dark:text-slate-400'}`}>
          💸 Расходы/Доходы ({expenses.length + incomes.length})
        </button>
      </div>

      {/* Контент табов */}
      {activeTab === 'overview' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {/* Расходы по типам */}
          <div className="card p-5">
            <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-4 flex items-center gap-2">
              <span className="w-1.5 h-5 bg-orange-500 rounded-full"></span>
              Расходы по типам
            </h3>
            {Object.keys(stats.expensesByType).length === 0 ? (
              <div className="text-center py-8 text-slate-400 text-sm">Нет расходов</div>
            ) : (
              <div className="space-y-3">
                {Object.entries(stats.expensesByType).sort((a, b) => b[1] - a[1]).map(([type, amount]) => {
                  const pct = (amount / stats.totalExpenses) * 100;
                  const labels: Record<string, string> = {
                    CONTRACTORS: '👷 Подрядчики', MATERIALS: '🧱 Материалы', EQUIPMENT: '🔧 Оборудование',
                    TRANSPORT: '🚚 Транспорт Доп.', ROAD: '🚗 Транспорт', MANAGER_COMMISSION: '💼 Комиссия',
                    FINES: '⚠️ Штрафы', CREDIT: '🏦 Кредит', OTHER: '📦 Другое',
                  };
                  return (
                    <div key={type}>
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-sm font-medium text-slate-900 dark:text-slate-100">{labels[type] || type}</span>
                        <span className="text-sm font-bold text-orange-600 dark:text-orange-400">{formatMoney(amount)}</span>
                      </div>
                      <div className="h-2 bg-slate-100 dark:bg-slate-800 rounded-full overflow-hidden">
                        <div className="h-full bg-gradient-to-r from-orange-500 to-amber-400 rounded-full" style={{ width: `${pct}%` }} />
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        </div>
      )}

      {activeTab === 'hours' && (
        <div className="card overflow-hidden">
          {entries.length === 0 ? (
            <div className="p-12 text-center text-slate-400">
              <div className="text-4xl mb-2">⏱</div>
              <p>Нет записей часов по этому проекту</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700">
                  <tr>
                    <th className="text-left p-3 font-semibold text-slate-600 dark:text-slate-400">Дата</th>
                    <th className="text-right p-3 font-semibold text-slate-600 dark:text-slate-400">Часы</th>
                    <th className="text-left p-3 font-semibold text-slate-600 dark:text-slate-400 hidden md:table-cell">Страна</th>
                    <th className="text-left p-3 font-semibold text-slate-600 dark:text-slate-400 hidden md:table-cell">Комментарий</th>
                  </tr>
                </thead>
                <tbody>
                  {entries.sort((a, b) => b.date.localeCompare(a.date)).map(entry => (
                    <tr key={entry.id} className="border-b border-slate-100 dark:border-slate-800 hover:bg-slate-50 dark:hover:bg-slate-800/30">
                      <td className="p-3 font-medium text-slate-900 dark:text-slate-100">{formatDate(entry.date)}</td>
                      <td className="p-3 text-right font-bold text-blue-600 dark:text-blue-400 tabular-nums">{entry.hours}ч</td>
                      <td className="p-3 text-slate-500 hidden md:table-cell">{entry.country === 'RF' ? '🇷🇺' : '🇧🇾'}</td>
                      <td className="p-3 text-slate-500 truncate max-w-[200px] hidden md:table-cell">{entry.comment || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {activeTab === 'expenses' && (
        <div className="space-y-2">
          {expenses.length === 0 ? (
            <div className="card p-12 text-center text-slate-400">
              <div className="text-4xl mb-2">💸</div>
              <p>Нет расходов по этому проекту</p>
            </div>
          ) : (
            expenses.sort((a, b) => b.date.localeCompare(a.date)).map(exp => (
              <div
                key={exp.id}
                className={`card p-4 flex items-center justify-between transition-all ${
                  exp.hasReceiptPhoto
                    ? 'bg-emerald-50 dark:bg-emerald-950/20 border-emerald-200 dark:border-emerald-800'
                    : ''
                }`}
              >
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="text-xs font-bold text-slate-400">{formatDate(exp.date)}</span>
                    <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-orange-100 text-orange-700 dark:bg-orange-950/40 dark:text-orange-400">{exp.type}</span>
                    {exp.hasReceiptPhoto && (
                      <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-emerald-100 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-400 flex items-center gap-1">
                        <span>✓</span>
                        <span>Чек</span>
                      </span>
                    )}
                  </div>
                  <div className="font-medium text-slate-900 dark:text-slate-100 truncate">{exp.name || 'Без названия'}</div>
                </div>
                <div className={`text-lg font-bold flex-shrink-0 ml-4 ${
                  exp.hasReceiptPhoto
                    ? 'text-emerald-600 dark:text-emerald-400'
                    : 'text-orange-600 dark:text-orange-400'
                }`}>
                  {formatMoney(exp.amount, exp.currency)}
                </div>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
}