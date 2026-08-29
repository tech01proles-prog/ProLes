import { useState, useEffect, useMemo } from 'react';
import api from '../api/client';
import type { ProjectDto, ExpenseDto, TimeEntryDto } from '../types';
import { formatMoney } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';

interface CostRow {
  project: ProjectDto;
  materialsManual: number;
  techHours: number;
  expenses: {
    employeeExpenses: number;
    household: number;
    advances: number;
    tickets: number;
    perDiem: number;
    other: number;
    total: number;
  };
  transport: {
    techTransport: number;
    other: number;
    total: number;
  };
  transportToClientManual: number;
  contractorsManual: number;
  creditPercentManual: number;
  totalCost: number;
}

export function CostCalculationPage() {
  const { can, loading: permLoading } = usePermissions();
  const canView = !permLoading && can('cost_calculation', 'view');
  const canEdit = !permLoading && can('cost_calculation', 'edit');
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [expenses, setExpenses] = useState<ExpenseDto[]>([]);
  const [timeEntries, setTimeEntries] = useState<TimeEntryDto[]>([]);
  const [loading, setLoading] = useState(true);
  // Глобальное состояние раскрытия колонок для всей таблицы
  const [showExpensesDetail, setShowExpensesDetail] = useState(false);
  const [showTransportDetail, setShowTransportDetail] = useState(false);
  const [manualData, setManualData] = useState<Record<string, {
    materials: number;
    transportToClient: number;
    contractors: number;
    creditPercent: number;
  }>>({});
  const [editingCell, setEditingCell] = useState<{ projectId: string; field: string } | null>(null);
  const [editValue, setEditValue] = useState('');

  useEffect(() => {
    if (!canView) return;
    (async () => {
      setLoading(true);
      const [projRes, expRes, timeRes] = await Promise.allSettled([
        api.get<ProjectDto[]>('/projects'),
        api.get<ExpenseDto[]>('/expenses/all'),
        api.get<TimeEntryDto[]>('/entries'),
      ]);
      setProjects(projRes.status === 'fulfilled' ? (projRes.value.data || []).filter((p: ProjectDto) => p.isActive) : []);
      setExpenses(expRes.status === 'fulfilled' ? (expRes.value.data || []) : []);
      setTimeEntries(timeRes.status === 'fulfilled' ? (timeRes.value.data || []) : []);
      
      // Загружаем сохраненные ручные данные из проектов
      const manual: Record<string, { materials: number; transportToClient: number; contractors: number; creditPercent: number }> = {};
      projRes.status === 'fulfilled' && projRes.value.data.forEach((p: ProjectDto) => {
        manual[p.id] = {
          materials: (p as any).materials || 0,
          transportToClient: p.transportToClient || 0,
          contractors: (p as any).contractors || 0,
          creditPercent: (p as any).creditPercent || 0,
        };
      });
      setManualData(manual);
      setLoading(false);
    })();
  }, [canView]);

  const projectData = useMemo((): CostRow[] => {
    return projects.map(project => {
      const projectExpenses = (expenses || []).filter(e => e.projectId === project.id);
      
      // Расходы сотрудников (все кроме HOUSEHOLD, PER_DIEM, ROAD, OTHER)
      const employeeExpenses = projectExpenses
        .filter(e => e.type !== 'HOUSEHOLD' && e.type !== 'PER_DIEM' && e.type !== 'ROAD' && e.type !== 'OTHER')
        .reduce((sum, e) => sum + e.amount, 0);
      
      // Хоз.нужды
      const household = projectExpenses
        .filter(e => e.type === 'HOUSEHOLD')
        .reduce((sum, e) => sum + e.amount, 0);
      
      // Авансы (заглушка - 0)
      const advances = 0;
      
      // Билеты (расходы на билеты)
      const tickets = projectExpenses
        .filter(e => e.name.toLowerCase().includes('билет') || e.name.toLowerCase().includes('ticket'))
        .reduce((sum, e) => sum + e.amount, 0);
      
      // Проживание (суточные - PER_DIEM)
      const perDiem = projectExpenses
        .filter(e => e.type === 'PER_DIEM')
        .reduce((sum, e) => sum + e.amount, 0);
      
      // Иные расходы
      const other = projectExpenses
        .filter(e => e.type === 'OTHER')
        .reduce((sum, e) => sum + e.amount, 0);
      
      const expensesTotal = employeeExpenses + household + advances + tickets + perDiem + other;
      
      // Транспорт ТО (ROAD)
      const techTransport = projectExpenses
        .filter(e => e.type === 'ROAD')
        .reduce((sum, e) => sum + e.amount, 0);
      
      // Транспорт другое (заглушка)
      const transportOther = 0;
      const transportTotal = techTransport + transportOther;
      
      // Часы ТО (сумма часов всех сотрудников роли employee по этому проекту)
      const techHours = (timeEntries || [])
        .filter(t => t.projectId === project.id)
        .reduce((sum, t) => sum + t.hours, 0);
      
      // Ручные данные
      const manual = manualData[project.id] || { materials: 0, transportToClient: 0, contractors: 0, creditPercent: 0 };
      
      // Затраты на реализацию = материалы + расходы + транспорт ТО
      const totalCost = manual.materials + expensesTotal + transportTotal + manual.transportToClient + manual.contractors + manual.creditPercent;
      
      return {
        project,
        materialsManual: manual.materials,
        techHours,
        expenses: {
          employeeExpenses,
          household,
          advances,
          tickets,
          perDiem,
          other,
          total: expensesTotal,
        },
        transport: {
          techTransport,
          other: transportOther,
          total: transportTotal,
        },
        transportToClientManual: manual.transportToClient,
        contractorsManual: manual.contractors,
        creditPercentManual: manual.creditPercent,
        totalCost,
      };
    });
  }, [projects, expenses, timeEntries, manualData]);

  const totals = useMemo(() => {
    return projectData.reduce((acc, row) => ({
      materialsManual: acc.materialsManual + row.materialsManual,
      techHours: acc.techHours + row.techHours,
      expensesTotal: acc.expensesTotal + row.expenses.total,
      transportTotal: acc.transportTotal + row.transport.total,
      transportToClientManual: acc.transportToClientManual + row.transportToClientManual,
      contractorsManual: acc.contractorsManual + row.contractorsManual,
      creditPercentManual: acc.creditPercentManual + row.creditPercentManual,
      totalCost: acc.totalCost + row.totalCost,
    }), {
      materialsManual: 0,
      techHours: 0,
      expensesTotal: 0,
      transportTotal: 0,
      transportToClientManual: 0,
      contractorsManual: 0,
      creditPercentManual: 0,
      totalCost: 0,
    });
  }, [projectData]);

  const handleCellEdit = (projectId: string, field: string, currentValue: number) => {
    if (!canEdit) return;
    setEditingCell({ projectId, field });
    setEditValue(currentValue.toString());
  };

  const handleCellSave = async () => {
    if (!editingCell) return;
    try {
      const project = projects.find(p => p.id === editingCell.projectId);
      if (!project) return;

      const currentManual = manualData[editingCell.projectId] || { materials: 0, transportToClient: 0, contractors: 0, creditPercent: 0 };
      let updatedManual = { ...currentManual };

      if (editingCell.field === 'materials') {
        updatedManual.materials = parseFloat(editValue) || 0;
      } else if (editingCell.field === 'transportToClient') {
        updatedManual.transportToClient = parseFloat(editValue) || 0;
      } else if (editingCell.field === 'contractors') {
        updatedManual.contractors = parseFloat(editValue) || 0;
      } else if (editingCell.field === 'creditPercent') {
        updatedManual.creditPercent = parseFloat(editValue) || 0;
      }

      setManualData(prev => ({ ...prev, [editingCell.projectId]: updatedManual }));

      // Сохраняем в проект
      const updatedProject = {
        ...project,
        transportToClient: updatedManual.transportToClient,
        materials: updatedManual.materials,
        contractors: updatedManual.contractors,
        creditPercent: updatedManual.creditPercent,
      };
      await api.put('/projects', updatedProject);
      setProjects(prev => prev.map(p => p.id === updatedProject.id ? updatedProject : p));
    } catch (err) {
      console.error('Ошибка сохранения:', err);
      alert('Ошибка сохранения');
    } finally {
      setEditingCell(null);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handleCellSave();
    } else if (e.key === 'Escape') {
      setEditingCell(null);
    }
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
    <div className="space-y-6 max-w-full mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">🧮 Расчёт себестоимости</h1>
        <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
          {projects.length} проектов • Нажмите на ячейку для редактирования (требуются права редактора)
        </p>
      </div>

      {/* Таблица в стиле Excel */}
      <div className="card overflow-x-auto">
        <table className="w-full text-xs border-collapse">
          <thead>
            {/* Заголовок - строка 1: Группы колонок */}
            <tr className="bg-slate-100 dark:bg-slate-800">
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-left font-semibold text-slate-700 dark:text-slate-300 min-w-[200px]">Проект</th>
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-right font-semibold text-slate-700 dark:text-slate-300 w-[100px]">Материалы</th>
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-right font-semibold text-slate-700 dark:text-slate-300 w-[80px]">Часы ТО</th>
              <th colSpan={7} className="border border-slate-300 dark:border-slate-600 p-2 text-center font-semibold text-slate-700 dark:text-slate-300 bg-indigo-50 dark:bg-indigo-900/30">Затраты на реализацию</th>
              <th colSpan={3} className="border border-slate-300 dark:border-slate-600 p-2 text-center font-semibold text-slate-700 dark:text-slate-300 bg-blue-50 dark:bg-blue-900/30">Транспорт</th>
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-right font-semibold text-slate-700 dark:text-slate-300 w-[120px]">Транспорт до клиента</th>
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-right font-semibold text-slate-700 dark:text-slate-300 w-[120px]">Услуги подрядчиков</th>
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-right font-semibold text-slate-700 dark:text-slate-300 w-[120px]">% по кредиту</th>
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-right font-semibold text-slate-700 dark:text-slate-300 w-[120px] bg-emerald-50 dark:bg-emerald-900/30">Итого с/с</th>
            </tr>
            {/* Заголовок - строка 2: Подколонки */}
            <tr className="bg-slate-50 dark:bg-slate-800/50">
              {/* Затраты на реализацию - подколонки */}
              <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[100px]">Расходы</th>
              <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[90px]">Хоз.нужды</th>
              <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[80px]">Авансы</th>
              <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[80px]">Билеты</th>
              <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[90px]">Проживание</th>
              <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[80px]">Иные</th>
              <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[100px] bg-indigo-100 dark:bg-indigo-900/50">Σ</th>
              {/* Транспорт - подколонки */}
              <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[110px]">Транспорт ТО</th>
              <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[80px]">Другое</th>
              <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[100px] bg-blue-100 dark:bg-blue-900/50">Σ</th>
            </tr>
          </thead>
          <tbody>
            {projectData.map((row) => {
              return (
                <tr key={row.project.id} className="hover:bg-slate-50 dark:hover:bg-slate-800/30">
                  {/* Проект */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2">
                    <div className="font-medium text-slate-900 dark:text-slate-100 truncate">{row.project.name}</div>
                    {row.project.client && <div className="text-xs text-slate-500 truncate">{row.project.client}</div>}
                  </td>
                  
                  {/* Материалы */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right">
                    {canEdit ? (
                      editingCell?.projectId === row.project.id && editingCell?.field === 'materials' ? (
                        <input
                          type="number"
                          step="0.01"
                          value={editValue}
                          onChange={(e) => setEditValue(e.target.value)}
                          onBlur={handleCellSave}
                          onKeyDown={handleKeyDown}
                          className="w-full text-right px-1 py-0.5 border border-indigo-400 rounded focus:outline-none focus:ring-2 focus:ring-indigo-500"
                          autoFocus
                        />
                      ) : (
                        <button
                          onClick={() => handleCellEdit(row.project.id, 'materials', row.materialsManual)}
                          className="w-full text-right hover:bg-indigo-50 dark:hover:bg-indigo-900/30 rounded px-1 py-0.5 cursor-pointer"
                        >
                          {row.materialsManual > 0 ? formatMoney(row.materialsManual) : '—'}
                        </button>
                      )
                    ) : (
                      row.materialsManual > 0 ? formatMoney(row.materialsManual) : '—'
                    )}
                  </td>
                  
                  {/* Часы ТО */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-700 dark:text-slate-300">
                    {row.techHours > 0 ? row.techHours.toFixed(1) : '—'}
                  </td>
                  
                  {/* Затраты на реализацию - основная колонка (Σ) */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right bg-indigo-50/30 dark:bg-indigo-900/10">
                    <button
                      onClick={() => setShowExpensesDetail(!showExpensesDetail)}
                      className="w-full text-right font-medium hover:bg-indigo-100 dark:hover:bg-indigo-900/30 rounded px-1 py-0.5 flex items-center justify-end gap-1"
                    >
                      <span>{showExpensesDetail ? '▼' : '▶'}</span>
                      <span className="tabular-nums">{formatMoney(row.expenses.total)}</span>
                    </button>
                  </td>
                  
                  {/* Скрытые колонки расходов */}
                  {showExpensesDetail ? (
                    <>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.expenses.employeeExpenses > 0 ? formatMoney(row.expenses.employeeExpenses) : '—'}</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.expenses.household > 0 ? formatMoney(row.expenses.household) : '—'}</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.expenses.advances > 0 ? formatMoney(row.expenses.advances) : '—'}</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.expenses.tickets > 0 ? formatMoney(row.expenses.tickets) : '—'}</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.expenses.perDiem > 0 ? formatMoney(row.expenses.perDiem) : '—'}</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.expenses.other > 0 ? formatMoney(row.expenses.other) : '—'}</td>
                    </>
                  ) : (
                    <>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right text-slate-400">—</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right text-slate-400">—</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right text-slate-400">—</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right text-slate-400">—</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right text-slate-400">—</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right text-slate-400">—</td>
                    </>
                  )}
                  
                  {/* Транспорт - основная колонка (Σ) */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right bg-blue-50/30 dark:bg-blue-900/10">
                    <button
                      onClick={() => setShowTransportDetail(!showTransportDetail)}
                      className="w-full text-right font-medium hover:bg-blue-100 dark:hover:bg-blue-900/30 rounded px-1 py-0.5 flex items-center justify-end gap-1"
                    >
                      <span>{showTransportDetail ? '▼' : '▶'}</span>
                      <span className="tabular-nums">{formatMoney(row.transport.total)}</span>
                    </button>
                  </td>
                  
                  {/* Скрытые колонки транспорта */}
                  {showTransportDetail ? (
                    <>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.transport.techTransport > 0 ? formatMoney(row.transport.techTransport) : '—'}</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.transport.other > 0 ? formatMoney(row.transport.other) : '—'}</td>
                    </>
                  ) : (
                    <>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right text-slate-400">—</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right text-slate-400">—</td>
                    </>
                  )}
                  
                  {/* Транспорт до клиента */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right">
                    {canEdit ? (
                      editingCell?.projectId === row.project.id && editingCell?.field === 'transportToClient' ? (
                        <input
                          type="number"
                          step="0.01"
                          value={editValue}
                          onChange={(e) => setEditValue(e.target.value)}
                          onBlur={handleCellSave}
                          onKeyDown={handleKeyDown}
                          className="w-full text-right px-1 py-0.5 border border-indigo-400 rounded focus:outline-none focus:ring-2 focus:ring-indigo-500"
                          autoFocus
                        />
                      ) : (
                        <button
                          onClick={() => handleCellEdit(row.project.id, 'transportToClient', row.transportToClientManual)}
                          className="w-full text-right hover:bg-indigo-50 dark:hover:bg-indigo-900/30 rounded px-1 py-0.5 cursor-pointer"
                        >
                          {row.transportToClientManual > 0 ? formatMoney(row.transportToClientManual) : '—'}
                        </button>
                      )
                    ) : (
                      row.transportToClientManual > 0 ? formatMoney(row.transportToClientManual) : '—'
                    )}
                  </td>
                  
                  {/* Услуги подрядчиков */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right">
                    {canEdit ? (
                      editingCell?.projectId === row.project.id && editingCell?.field === 'contractors' ? (
                        <input
                          type="number"
                          step="0.01"
                          value={editValue}
                          onChange={(e) => setEditValue(e.target.value)}
                          onBlur={handleCellSave}
                          onKeyDown={handleKeyDown}
                          className="w-full text-right px-1 py-0.5 border border-indigo-400 rounded focus:outline-none focus:ring-2 focus:ring-indigo-500"
                          autoFocus
                        />
                      ) : (
                        <button
                          onClick={() => handleCellEdit(row.project.id, 'contractors', row.contractorsManual)}
                          className="w-full text-right hover:bg-indigo-50 dark:hover:bg-indigo-900/30 rounded px-1 py-0.5 cursor-pointer"
                        >
                          {row.contractorsManual > 0 ? formatMoney(row.contractorsManual) : '—'}
                        </button>
                      )
                    ) : (
                      row.contractorsManual > 0 ? formatMoney(row.contractorsManual) : '—'
                    )}
                  </td>
                  
                  {/* % по кредиту */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right">
                    {canEdit ? (
                      editingCell?.projectId === row.project.id && editingCell?.field === 'creditPercent' ? (
                        <input
                          type="number"
                          step="0.01"
                          value={editValue}
                          onChange={(e) => setEditValue(e.target.value)}
                          onBlur={handleCellSave}
                          onKeyDown={handleKeyDown}
                          className="w-full text-right px-1 py-0.5 border border-indigo-400 rounded focus:outline-none focus:ring-2 focus:ring-indigo-500"
                          autoFocus
                        />
                      ) : (
                        <button
                          onClick={() => handleCellEdit(row.project.id, 'creditPercent', row.creditPercentManual)}
                          className="w-full text-right hover:bg-indigo-50 dark:hover:bg-indigo-900/30 rounded px-1 py-0.5 cursor-pointer"
                        >
                          {row.creditPercentManual > 0 ? formatMoney(row.creditPercentManual) : '—'}
                        </button>
                      )
                    ) : (
                      row.creditPercentManual > 0 ? formatMoney(row.creditPercentManual) : '—'
                    )}
                  </td>
                  
                  {/* Итого с/с */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right font-bold bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-400">
                    {formatMoney(row.totalCost)}
                  </td>
                </tr>
              );
            })}
          </tbody>
          <tfoot>
            <tr className="bg-slate-100 dark:bg-slate-800 font-bold">
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-left">ИТОГО</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(totals.materialsManual)}</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{totals.techHours.toFixed(1)}</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right bg-indigo-100 dark:bg-indigo-900/30">{formatMoney(totals.expensesTotal)}</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right text-slate-500">—</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right text-slate-500">—</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right text-slate-500">—</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right text-slate-500">—</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right text-slate-500">—</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right text-slate-500">—</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right bg-blue-100 dark:bg-blue-900/30">{formatMoney(totals.transportTotal)}</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right text-slate-500">—</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right text-slate-500">—</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(totals.transportToClientManual)}</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(totals.contractorsManual)}</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(totals.creditPercentManual)}</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400">{formatMoney(totals.totalCost)}</td>
            </tr>
          </tfoot>
        </table>
      </div>

      {/* Легенда */}
      <div className="card p-4 bg-slate-50 dark:bg-slate-800/50 text-xs text-slate-600 dark:text-slate-400">
        <div className="font-semibold mb-2">📋 Пояснения:</div>
        <ul className="space-y-1">
          <li><span className="font-medium">Материалы</span> — заполняется вручную администратором</li>
          <li><span className="font-medium">Часы ТО</span> — сумма часов тех.отдела (роль employee) из табеля</li>
          <li><span className="font-medium">Затраты на реализацию</span> — сумма: Материалы + Расходы + Транспорт ТО</li>
          <li><span className="font-medium">Расходы</span> — сумма расходов всех сотрудников к проекту (кроме хоз.нужд, суточных, транспорта)</li>
          <li><span className="font-medium">Хоз.нужды</span> — расходы типа HOUSEHOLD</li>
          <li><span className="font-medium">Авансы</span> — выплаты сотрудникам по авансам (пока заглушка 0)</li>
          <li><span className="font-medium">Билеты</span> — расходы по билетам</li>
          <li><span className="font-medium">Проживание</span> — суточные (тип PER_DIEM)</li>
          <li><span className="font-medium">Транспорт ТО</span> — расходы тех.отдела на дорогу (тип ROAD)</li>
          <li><span className="font-medium">Транспорт до клиента</span> — заполняется вручную администратором</li>
          <li><span className="font-medium">Услуги подрядчиков</span> — заполняется вручную администратором</li>
          <li><span className="font-medium">% по кредиту</span> — заполняется вручную администратором</li>
        </ul>
      </div>
    </div>
  );
}
