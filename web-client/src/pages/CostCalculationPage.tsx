import { useState, useEffect, useMemo } from 'react';
import api from '../api/client';
import type { ProjectDto, ExpenseDto, TimeEntryDto } from '../types';
import { formatMoney } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';

interface CostCalculationRow {
  project: ProjectDto;
  materials: number;
  techHours: number;
  // Затраты на реализацию (составляющие)
  employeeExpenses: number;
  householdExpenses: number;
  advances: number;
  tickets: number;
  perDiem: number;
  other: number;
  totalImplementationCosts: number;
  // Транспорт сверху (составляющие)
  transportTech: number;
  transportOther: number;
  totalTransportOverhead: number;
  // Остальные поля
  transportToClientManual: number;
  contractorsManual: number;
  creditPercentManual: number;
  // Итого
  fullCost: number;
}

const EXPENSE_TYPES = {
  MATERIALS: 'MATERIALS',
  ROAD: 'ROAD',
  HOUSEHOLD: 'HOUSEHOLD',
  PER_DIEM: 'PER_DIEM',
  CONTRACTORS: 'CONTRACTORS',
  CREDIT: 'CREDIT',
  OTHER: 'OTHER',
};

export function CostCalculationPage() {
  const { can, loading: permLoading } = usePermissions();
  const canView = !permLoading && can('cost_calculation', 'view');
  const canEdit = !permLoading && can('cost_calculation', 'edit');
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [expenses, setExpenses] = useState<ExpenseDto[]>([]);
  const [timeEntries, setTimeEntries] = useState<TimeEntryDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [editingCell, setEditingCell] = useState<{ projectId: string; field: string } | null>(null);
  const [editValue, setEditValue] = useState('');
  const [saving, setSaving] = useState(false);
  const [expandedImplementation, setExpandedImplementation] = useState<Set<string>>(new Set());
  const [expandedTransport, setExpandedTransport] = useState<Set<string>>(new Set());

  useEffect(() => {
    if (!canView) return;
    (async () => {
      setLoading(true);
      const [projRes, expRes] = await Promise.allSettled([
        api.get<ProjectDto[]>('/projects'),
        api.get<ExpenseDto[]>('/expenses/all'),
      ]);
      setProjects(projRes.status === 'fulfilled' ? projRes.value.data.filter(p => p.isActive) : []);
      setExpenses(expRes.status === 'fulfilled' ? expRes.value.data : []);
      setLoading(false);
    })();
  }, [canView]);

  const projectData = useMemo(() => {
    return projects.map(project => {
      const projectExpenses = expenses.filter(e => e.projectId === project.id);
      const transportOverhead = projectExpenses.filter(e => e.type === 'ROAD').reduce((s, e) => s + e.amount, 0);
      const externalCosts = projectExpenses.filter(e => e.type === 'OTHER').reduce((s, e) => s + e.amount, 0);
      const fullCost = project.productionCost + transportOverhead + project.transportToClient + externalCosts;
      const margin = project.sellingPrice - fullCost;
      const marginPercent = project.sellingPrice > 0 ? (margin / project.sellingPrice) * 100 : 0;
      return {
        project,
        transportOverhead,
        externalCosts,
        fullCost,
        margin,
        marginPercent,
      };
    });
  }, [projects, expenses]);

  const totals = useMemo(() => {
    return projectData.reduce((acc, d) => ({
      productionCost: acc.productionCost + d.project.productionCost,
      transportOverhead: acc.transportOverhead + d.transportOverhead,
      transportToClient: acc.transportToClient + d.project.transportToClient,
      externalCosts: acc.externalCosts + d.externalCosts,
      fullCost: acc.fullCost + d.fullCost,
      sellingPrice: acc.sellingPrice + d.project.sellingPrice,
      margin: acc.margin + d.margin,
    }), { productionCost: 0, transportOverhead: 0, transportToClient: 0, externalCosts: 0, fullCost: 0, sellingPrice: 0, margin: 0 });
  }, [projectData]);

  const openEdit = (project: ProjectDto) => {
    setEditingProject(project);
    setEditForm({
      productionCost: project.productionCost.toString(),
      transportToClient: project.transportToClient.toString(),
      sellingPrice: project.sellingPrice.toString(),
    });
  };

  const handleSave = async () => {
    if (!editingProject) return;
    setSaving(true);
    try {
      const updated = {
        ...editingProject,
        productionCost: parseFloat(editForm.productionCost) || 0,
        transportToClient: parseFloat(editForm.transportToClient) || 0,
        sellingPrice: parseFloat(editForm.sellingPrice) || 0,
      };
      await api.put('/projects', updated);
      setProjects(prev => prev.map(p => p.id === updated.id ? updated : p));
      setEditingProject(null);
    } catch { alert('Ошибка сохранения'); }
    finally { setSaving(false); }
  };

  if (permLoading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;
  if (!canView) return (
    <div className="card p-12 text-center">
      <div className="text-5xl mb-4 opacity-50">🔒</div>
      <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Нет доступа</h3>
      <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">У вас нет прав на просмотр себестоимости</p>
    </div>
  );
  if (loading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">🧮 Расчёт себестоимости</h1>
        <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
          {projects.length} проектов • Нажмите на строку для редактирования
        </p>
      </div>

      {/* KPI Итого */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        <div className="card p-4 bg-gradient-to-br from-slate-50 to-gray-50 dark:from-slate-800/50 dark:to-gray-800/50 border-slate-200 dark:border-slate-700">
          <div className="text-xs font-bold text-slate-600 dark:text-slate-400 uppercase">Полная с/с</div>
          <div className="text-xl font-black text-slate-900 dark:text-slate-100 mt-1">{formatMoney(totals.fullCost)}</div>
        </div>
        <div className="card p-4 bg-gradient-to-br from-blue-50 to-indigo-50 dark:from-blue-950/30 dark:to-indigo-950/30 border-blue-100 dark:border-blue-900">
          <div className="text-xs font-bold text-blue-600 dark:text-blue-400 uppercase">Продажная цена</div>
          <div className="text-xl font-black text-blue-900 dark:text-blue-100 mt-1">{formatMoney(totals.sellingPrice)}</div>
        </div>
        <div className={`card p-4 border ${totals.margin >= 0 ? 'bg-gradient-to-br from-emerald-50 to-green-50 dark:from-emerald-950/30 dark:to-green-950/30 border-emerald-100 dark:border-emerald-900' : 'bg-gradient-to-br from-red-50 to-rose-50 dark:from-red-950/30 dark:to-rose-950/30 border-red-100 dark:border-red-900'}`}>
          <div className={`text-xs font-bold uppercase ${totals.margin >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-600 dark:text-red-400'}`}>Маржа</div>
          <div className={`text-xl font-black mt-1 ${totals.margin >= 0 ? 'text-emerald-900 dark:text-emerald-100' : 'text-red-900 dark:text-red-100'}`}>{formatMoney(totals.margin)}</div>
        </div>
        <div className={`card p-4 border ${totals.margin >= 0 ? 'bg-gradient-to-br from-green-50 to-emerald-50 dark:from-green-950/30 dark:to-emerald-950/30 border-green-100 dark:border-green-900' : 'bg-gradient-to-br from-red-50 to-rose-50 dark:from-red-950/30 dark:to-rose-950/30 border-red-100 dark:border-red-900'}`}>
          <div className={`text-xs font-bold uppercase ${totals.margin >= 0 ? 'text-green-600 dark:text-green-400' : 'text-red-600 dark:text-red-400'}`}>Маржа %</div>
          <div className={`text-xl font-black mt-1 ${totals.margin >= 0 ? 'text-green-900 dark:text-green-100' : 'text-red-900 dark:text-red-100'}`}>
            {totals.sellingPrice > 0 ? `${((totals.margin / totals.sellingPrice) * 100).toFixed(1)}%` : '—'}
          </div>
        </div>
      </div>

      {/* Таблица */}
      <div className="card overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700">
              <tr>
                <th className="text-left p-3 font-semibold text-slate-600 dark:text-slate-400 min-w-[180px]">Проект</th>
                <th className="text-right p-3 font-semibold text-slate-600 dark:text-slate-400 whitespace-nowrap">С/с произв.</th>
                <th className="text-right p-3 font-semibold text-slate-600 dark:text-slate-400 whitespace-nowrap">Транс. сверху</th>
                <th className="text-right p-3 font-semibold text-slate-600 dark:text-slate-400 whitespace-nowrap">Транс. клиент</th>
                <th className="text-right p-3 font-semibold text-slate-600 dark:text-slate-400 whitespace-nowrap">Внешние</th>
                <th className="text-right p-3 font-semibold text-slate-600 dark:text-slate-400 whitespace-nowrap">Полная с/с</th>
                <th className="text-right p-3 font-semibold text-slate-600 dark:text-slate-400 whitespace-nowrap">Цена продажи</th>
                <th className="text-right p-3 font-semibold text-slate-600 dark:text-slate-400 whitespace-nowrap">Маржа</th>
                <th className="text-center p-3 font-semibold text-slate-600 dark:text-slate-400">%</th>
              </tr>
            </thead>
            <tbody>
              {projectData.map(({ project, transportOverhead, externalCosts, fullCost, margin, marginPercent }) => (
                <tr
                  key={project.id}
                  onClick={() => openEdit(project)}
                  className="border-b border-slate-100 dark:border-slate-800 hover:bg-slate-50 dark:hover:bg-slate-800/30 cursor-pointer transition-colors group"
                >
                  <td className="p-3">
                    <div className="font-medium text-slate-900 dark:text-slate-100 group-hover:text-indigo-600 dark:group-hover:text-indigo-400 transition-colors truncate max-w-[200px]">{project.name}</div>
                    {project.client && <div className="text-xs text-slate-500 truncate">{project.client}</div>}
                  </td>
                  <td className="p-3 text-right tabular-nums text-slate-700 dark:text-slate-300">{project.productionCost > 0 ? formatMoney(project.productionCost) : '—'}</td>
                  <td className="p-3 text-right tabular-nums text-slate-700 dark:text-slate-300">{transportOverhead > 0 ? formatMoney(transportOverhead) : '—'}</td>
                  <td className="p-3 text-right tabular-nums text-slate-700 dark:text-slate-300">{project.transportToClient > 0 ? formatMoney(project.transportToClient) : '—'}</td>
                  <td className="p-3 text-right tabular-nums text-slate-700 dark:text-slate-300">{externalCosts > 0 ? formatMoney(externalCosts) : '—'}</td>
                  <td className="p-3 text-right tabular-nums font-bold text-slate-900 dark:text-slate-100">{formatMoney(fullCost)}</td>
                  <td className="p-3 text-right tabular-nums text-blue-600 dark:text-blue-400 font-medium">{project.sellingPrice > 0 ? formatMoney(project.sellingPrice) : '—'}</td>
                  <td className={`p-3 text-right tabular-nums font-bold ${margin >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-600 dark:text-red-400'}`}>{formatMoney(margin)}</td>
                  <td className="p-3 text-center">
                    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-bold ${
                      marginPercent >= 20 ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-400' :
                      marginPercent >= 0 ? 'bg-amber-100 text-amber-700 dark:bg-amber-950/40 dark:text-amber-400' :
                      'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-400'
                    }`}>
                      {marginPercent.toFixed(1)}%
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
            <tfoot className="bg-slate-50 dark:bg-slate-800/50 border-t-2 border-slate-200 dark:border-slate-700">
              <tr>
                <td className="p-3 font-bold text-slate-900 dark:text-slate-100">ИТОГО</td>
                <td className="p-3 text-right tabular-nums font-bold text-slate-900 dark:text-slate-100">{formatMoney(totals.productionCost)}</td>
                <td className="p-3 text-right tabular-nums font-bold text-slate-900 dark:text-slate-100">{formatMoney(totals.transportOverhead)}</td>
                <td className="p-3 text-right tabular-nums font-bold text-slate-900 dark:text-slate-100">{formatMoney(totals.transportToClient)}</td>
                <td className="p-3 text-right tabular-nums font-bold text-slate-900 dark:text-slate-100">{formatMoney(totals.externalCosts)}</td>
                <td className="p-3 text-right tabular-nums font-black text-slate-900 dark:text-slate-100">{formatMoney(totals.fullCost)}</td>
                <td className="p-3 text-right tabular-nums font-bold text-blue-600 dark:text-blue-400">{formatMoney(totals.sellingPrice)}</td>
                <td className={`p-3 text-right tabular-nums font-black ${totals.margin >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-600 dark:text-red-400'}`}>{formatMoney(totals.margin)}</td>
                <td className="p-3 text-center">
                  <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-bold ${
                    totals.sellingPrice > 0 && (totals.margin / totals.sellingPrice * 100) >= 0
                      ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-400'
                      : 'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-400'
                  }`}>
                    {totals.sellingPrice > 0 ? `${(totals.margin / totals.sellingPrice * 100).toFixed(1)}%` : '—'}
                  </span>
                </td>
              </tr>
            </tfoot>
          </table>
        </div>
      </div>

      {/* Модалка редактирования */}
      {editingProject && (
        <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={() => !saving && setEditingProject(null)} />
          <div className="relative bg-white dark:bg-slate-900 rounded-2xl shadow-2xl w-full max-w-md p-6 animate-fade-in border border-slate-200 dark:border-slate-700">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-lg font-bold text-slate-900 dark:text-slate-100">✏️ {editingProject.name}</h2>
              <button onClick={() => setEditingProject(null)} className="text-slate-400 hover:text-slate-600 text-xl">✕</button>
            </div>
            <div className="space-y-4">
              <div className="space-y-1.5">
                <label className="text-xs font-medium text-slate-600 dark:text-slate-400">💰 Себестоимость производства</label>
                <input type="number" step="0.01" value={editForm.productionCost} onChange={(e) => setEditForm({ ...editForm, productionCost: e.target.value })} className="input" placeholder="0" />
              </div>
              <div className="space-y-1.5">
                <label className="text-xs font-medium text-slate-600 dark:text-slate-400">🚚 Транспорт до клиента</label>
                <input type="number" step="0.01" value={editForm.transportToClient} onChange={(e) => setEditForm({ ...editForm, transportToClient: e.target.value })} className="input" placeholder="0" />
              </div>
              <div className="space-y-1.5">
                <label className="text-xs font-medium text-slate-600 dark:text-slate-400">💵 Продажная цена (без НДС)</label>
                <input type="number" step="0.01" value={editForm.sellingPrice} onChange={(e) => setEditForm({ ...editForm, sellingPrice: e.target.value })} className="input" placeholder="0" />
              </div>
              {/* Превью маржи */}
              {(() => {
                const pc = parseFloat(editForm.productionCost) || 0;
                const tc = parseFloat(editForm.transportToClient) || 0;
                const sp = parseFloat(editForm.sellingPrice) || 0;
                const projExp = expenses.filter(e => e.projectId === editingProject.id);
                const to = projExp.filter(e => e.type === 'ROAD').reduce((s, e) => s + e.amount, 0);
                const ec = projExp.filter(e => e.type === 'OTHER').reduce((s, e) => s + e.amount, 0);
                const fc = pc + to + tc + ec;
                const m = sp - fc;
                const mp = sp > 0 ? (m / sp * 100) : 0;
                return (
                  <div className={`p-3 rounded-lg border ${m >= 0 ? 'bg-emerald-50 dark:bg-emerald-950/20 border-emerald-200 dark:border-emerald-900' : 'bg-red-50 dark:bg-red-950/20 border-red-200 dark:border-red-900'}`}>
                    <div className="flex justify-between text-sm">
                      <span className="text-slate-600 dark:text-slate-400">Маржа:</span>
                      <span className={`font-bold ${m >= 0 ? 'text-emerald-700 dark:text-emerald-400' : 'text-red-700 dark:text-red-400'}`}>
                        {formatMoney(m)} ({mp.toFixed(1)}%)
                      </span>
                    </div>
                  </div>
                );
              })()}
            </div>
            <div className="flex justify-end gap-2 mt-6">
              <button onClick={() => setEditingProject(null)} className="btn-outline px-4 py-2 text-sm">Отмена</button>
              <button onClick={handleSave} disabled={saving} className="btn-primary px-5 py-2 text-sm">
                {saving ? '⏳...' : '💾 Сохранить'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}