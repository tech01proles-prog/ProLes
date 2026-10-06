import { useEffect, useMemo, useState } from 'react';
import api from '../api/client';
import type { ExpenseDto, ProjectDto, TimeEntryDto } from '../types';
import { formatMoney } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';
import * as XLSX from 'xlsx';
import { saveAs } from 'file-saver';

type PeriodType = 'month' | 'quarter' | 'year';
type PaidSalary = { userId: string; year: number; month: number; amount: number; taxInclusiveAmount: number; status: string };
type RealizationCost = { id: string; projectId: string; amount: number; comment: string; date: string; createdAt: number };
type Bounds = { start: Date; end: Date; fromYear: number; fromMonth: number; toYear: number; toMonth: number };
type CostRow = { key: string; projectId: string; projectName: string; subprojectName: string; hours: number; salary: number; salaryWithTax: number; realization: number; total: number };

const boundsFor = (type: PeriodType, value: string): Bounds | null => {
  if (type === 'month') {
    const [year, month] = value.split('-').map(Number);
    if (!year || !month) return null;
    return { start: new Date(year, month - 1, 1), end: new Date(year, month, 0, 23, 59, 59, 999), fromYear: year, fromMonth: month, toYear: year, toMonth: month };
  }
  if (type === 'quarter') {
    const [yearText, quarterText] = value.split('-Q');
    const year = Number(yearText);
    const quarter = Number(quarterText);
    if (!year || quarter < 1 || quarter > 4) return null;
    const firstMonth = (quarter - 1) * 3;
    return { start: new Date(year, firstMonth, 1), end: new Date(year, firstMonth + 3, 0, 23, 59, 59, 999), fromYear: year, fromMonth: firstMonth + 1, toYear: year, toMonth: firstMonth + 3 };
  }
  const year = Number(value);
  if (!year) return null;
  return { start: new Date(year, 0, 1), end: new Date(year, 11, 31, 23, 59, 59, 999), fromYear: year, fromMonth: 1, toYear: year, toMonth: 12 };
};

const isRealizationExpense = (expense: ExpenseDto) => {
  const value = `${expense.type || ''} ${expense.name || ''} ${expense.comment || ''}`.toLowerCase();
  return value.includes('шм') || value.includes('производ') || value.includes('реализац');
};

export function CostCalculationPage() {
  const { can, loading: permissionLoading } = usePermissions();
  const canView = !permissionLoading && can('cost_calculation', 'view');
  const [periodType, setPeriodType] = useState<PeriodType>('month');
  const [selectedPeriod, setSelectedPeriod] = useState('');
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [entries, setEntries] = useState<TimeEntryDto[]>([]);
  const [expenses, setExpenses] = useState<ExpenseDto[]>([]);
  const [salaries, setSalaries] = useState<PaidSalary[]>([]);
  const [realizationCosts, setRealizationCosts] = useState<RealizationCost[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [showRealizationDialog, setShowRealizationDialog] = useState(false);
  const [realizationProjectId, setRealizationProjectId] = useState<string | null>(null);
  const [realizationAmount, setRealizationAmount] = useState('');
  const [realizationComment, setRealizationComment] = useState('');
  const [realizationDate, setRealizationDate] = useState('');
  const [realizationSaving, setRealizationSaving] = useState(false);

  useEffect(() => {
    const now = new Date();
    if (periodType === 'month') setSelectedPeriod(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`);
    if (periodType === 'quarter') setSelectedPeriod(`${now.getFullYear()}-Q${Math.floor(now.getMonth() / 3) + 1}`);
    if (periodType === 'year') setSelectedPeriod(String(now.getFullYear()));
  }, [periodType]);

  const bounds = useMemo(() => boundsFor(periodType, selectedPeriod), [periodType, selectedPeriod]);

  useEffect(() => {
    if (!canView || !bounds) return;
    let cancelled = false;

    const load = async () => {
      setLoading(true);
      try {
        const [projectResponse, entryResponse, expenseResponse, salaryResponse, realizationResponse] = await Promise.all([
          api.get<ProjectDto[]>('/projects'),
          api.get<TimeEntryDto[]>('/entries/all'),
          api.get<ExpenseDto[]>('/expenses/all'),
          api.get<PaidSalary[]>('/project-costs/paid-salaries', { params: { fromYear: bounds.fromYear, fromMonth: bounds.fromMonth, toYear: bounds.toYear, toMonth: bounds.toMonth } }),
          api.get<RealizationCost[]>('/project-costs/realization-costs', {
            params: { fromDate: bounds.start.toISOString().slice(0, 10), toDate: bounds.end.toISOString().slice(0, 10) },
          }),
        ]);
        if (cancelled) return;
        setProjects((projectResponse.data || []).filter((project) => project.isActive));
        setEntries(entryResponse.data || []);
        setExpenses(expenseResponse.data || []);
        setSalaries(salaryResponse.data || []);
        setRealizationCosts(realizationResponse.data || []);
      } catch (error) {
        console.error('Ошибка загрузки себестоимости:', error);
        if (!cancelled) {
          setProjects([]);
          setEntries([]);
          setExpenses([]);
          setSalaries([]);
          setRealizationCosts([]);
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    };

    void load();
    return () => { cancelled = true; };
  }, [bounds, canView]);

  const rows = useMemo(() => {
    if (!bounds) return [];

    const periodEntries = entries.filter((entry) => {
      const date = new Date(`${entry.date}T00:00:00`);
      return date >= bounds.start && date <= bounds.end && entry.hours > 0;
    });
    const periodExpenses = expenses.filter((expense) => {
      const date = new Date(`${expense.date}T00:00:00`);
      return date >= bounds.start && date <= bounds.end && isRealizationExpense(expense);
    });

    const realizationByProject = new Map<string, number>();
    realizationCosts.forEach((cost) => {
      realizationByProject.set(cost.projectId, (realizationByProject.get(cost.projectId) || 0) + cost.amount);
    });

    const monthKey = (userId: string, year: number, month: number) => `${userId}:${year}-${String(month).padStart(2, '0')}`;
    const totalHoursByUserMonth = new Map<string, number>();

    periodEntries.forEach((entry) => {
      const date = new Date(`${entry.date}T00:00:00`);
      const key = monthKey(entry.userId, date.getFullYear(), date.getMonth() + 1);
      totalHoursByUserMonth.set(key, (totalHoursByUserMonth.get(key) || 0) + entry.hours);
    });

    const paidByUserMonth = new Map<string, { salary: number; salaryWithTax: number }>();
    salaries.forEach((salary) => {
      const key = monthKey(salary.userId, salary.year, salary.month);
      const current = paidByUserMonth.get(key) || { salary: 0, salaryWithTax: 0 };
      current.salary += salary.amount;
      current.salaryWithTax += salary.taxInclusiveAmount || salary.amount;
      paidByUserMonth.set(key, current);
    });

    const map = new Map<string, CostRow>();
    const ensureRow = (projectId: string, subprojectId: string | null, projectName: string, subprojectName: string) => {
      const key = `${projectId}:${subprojectId || 'root'}`;
      if (!map.has(key)) map.set(key, { key, projectId, projectName, subprojectName, hours: 0, salary: 0, salaryWithTax: 0, realization: 0, total: 0 });
      return map.get(key)!;
    };

    projects.forEach((project) => ensureRow(project.id, null, project.name, ''));
    periodEntries.forEach((entry) => {
      const project = projects.find((item) => item.id === entry.projectId);
      if (!project) return;
      const row = ensureRow(project.id, entry.subprojectId || null, project.name, entry.subprojectName || '');
      const entryDate = new Date(`${entry.date}T00:00:00`);
      const key = monthKey(entry.userId, entryDate.getFullYear(), entryDate.getMonth() + 1);
      const employeeHours = totalHoursByUserMonth.get(key) || 0;
      const paid = paidByUserMonth.get(key);
      row.hours += entry.hours;
      if (employeeHours > 0 && paid) {
        row.salary += paid.salary * entry.hours / employeeHours;
        row.salaryWithTax += paid.salaryWithTax * entry.hours / employeeHours;
      }
    });
    periodExpenses.forEach((expense) => {
      if (!expense.projectId) return;
      const project = projects.find((item) => item.id === expense.projectId);
      if (!project) return;
      const row = ensureRow(project.id, expense.subprojectId || null, project.name, expense.subprojectName || '');
      row.realization += expense.amount;
    });

    realizationByProject.forEach((amount, projectId) => {
      const project = projects.find((item) => item.id === projectId);
      if (!project) return;
      const row = ensureRow(project.id, null, project.name, '');
      row.realization += amount;
    });

    return Array.from(map.values()).map((row) => ({ ...row, total: row.salaryWithTax + row.realization })).sort((a, b) => a.projectName.localeCompare(b.projectName, 'ru') || a.subprojectName.localeCompare(b.subprojectName, 'ru'));
  }, [bounds, entries, expenses, projects, salaries, realizationCosts]);

  const filteredRows = useMemo(() => {
    const query = search.trim().toLowerCase();
    return rows.filter((row) => !query || `${row.projectName} ${row.subprojectName}`.toLowerCase().includes(query));
  }, [rows, search]);

  const totals = useMemo(() => filteredRows.reduce((result, row) => ({ hours: result.hours + row.hours, salary: result.salary + row.salary, salaryWithTax: result.salaryWithTax + row.salaryWithTax, realization: result.realization + row.realization, total: result.total + row.total }), { hours: 0, salary: 0, salaryWithTax: 0, realization: 0, total: 0 }), [filteredRows]);

  const openRealizationDialog = (projectId: string) => {
    if (!bounds) return;
    setRealizationProjectId(projectId);
    setRealizationAmount('');
    setRealizationComment('');
    setRealizationDate(bounds.start.toISOString().slice(0, 10));
    setShowRealizationDialog(true);
  };

  const saveRealizationCost = async () => {
    if (!realizationProjectId || !realizationDate) return;
    const amount = Number(realizationAmount);
    if (!Number.isFinite(amount) || amount <= 0) {
      alert('Укажите сумму больше нуля');
      return;
    }
    setRealizationSaving(true);
    try {
      const { data } = await api.post<RealizationCost>(
        '/project-costs/' + realizationProjectId + '/realization-costs',
        { amount, comment: realizationComment.trim(), date: realizationDate },
      );
      setRealizationCosts((current) => [...current, data]);
      setShowRealizationDialog(false);
    } catch (error) {
      console.error(error);
      alert('Не удалось сохранить затраты на реализацию');
    } finally {
      setRealizationSaving(false);
    }
  };

  const exportXlsx = () => {
    const data = filteredRows.map((row) => ({ Проект: row.projectName, Подпроект: row.subprojectName || '', 'Часы ТО': row.hours, 'Выплаченная зарплата': row.salary, 'Зарплата с налогами': row.salaryWithTax, 'Затраты на реализацию': row.realization, Себестоимость: row.total }));
    data.push({ Проект: 'ИТОГО', Подпроект: '', 'Часы ТО': totals.hours, 'Выплаченная зарплата': totals.salary, 'Зарплата с налогами': totals.salaryWithTax, 'Затраты на реализацию': totals.realization, Себестоимость: totals.total });
    const workbook = XLSX.utils.book_new();
    const worksheet = XLSX.utils.json_to_sheet(data);
    worksheet['!cols'] = [{ wch: 32 }, { wch: 28 }, { wch: 12 }, { wch: 22 }, { wch: 22 }, { wch: 24 }, { wch: 20 }];
    XLSX.utils.book_append_sheet(workbook, worksheet, 'Себестоимость');
    const output = XLSX.write(workbook, { bookType: 'xlsx', type: 'array' });
    saveAs(new Blob([output], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), `Себестоимость_${selectedPeriod}.xlsx`);
  };

  if (permissionLoading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">◌</div></div>;
  if (!canView) return <div className="card p-12 text-center"><h3 className="text-lg font-semibold">Нет доступа</h3><p className="mt-1 text-sm text-slate-500">У вас нет права на просмотр себестоимости</p></div>;
  if (loading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">◌</div></div>;

  return (
    <div className="mx-auto max-w-full space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">Себестоимость</h1>
          <p className="mt-1 text-sm text-slate-500">Фактическая зарплата распределяется по проектам пропорционально часам за выбранный период</p>
        </div>
        <button type="button" onClick={exportXlsx} className="rounded-xl bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-700">Экспорт XLSX</button>
      </div>

      <div className="card flex flex-wrap items-center gap-3 p-4">
        <select value={periodType} onChange={(event) => setPeriodType(event.target.value as PeriodType)} className="input bg-white dark:bg-slate-900">
          <option value="month">Месяц</option>
          <option value="quarter">Квартал</option>
          <option value="year">Год</option>
        </select>
        {periodType === 'month' && <input type="month" value={selectedPeriod} onChange={(event) => setSelectedPeriod(event.target.value)} className="input" />}
        {periodType === 'quarter' && (
          <select value={selectedPeriod} onChange={(event) => setSelectedPeriod(event.target.value)} className="input bg-white dark:bg-slate-900">
            {Array.from({ length: 5 }, (_, index) => new Date().getFullYear() - index).flatMap((year) => [1, 2, 3, 4].map((quarter) => <option key={`${year}-Q${quarter}`} value={`${year}-Q${quarter}`}>{year}, квартал {quarter}</option>))}
          </select>
        )}
        {periodType === 'year' && (
          <select value={selectedPeriod} onChange={(event) => setSelectedPeriod(event.target.value)} className="input bg-white dark:bg-slate-900">
            {Array.from({ length: 5 }, (_, index) => new Date().getFullYear() - index).map((year) => <option key={year} value={year}>{year}</option>)}
          </select>
        )}
        <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Проект или подпроект" className="input min-w-[220px] flex-1" />
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
        <Summary label="Часы ТО" value={totals.hours.toFixed(1)} />
        <Summary label="Выплаченная зарплата" value={formatMoney(totals.salary)} />
        <Summary label="Зарплата с налогами" value={formatMoney(totals.salaryWithTax)} />
        <Summary label="Затраты на реализацию" value={formatMoney(totals.realization)} />
        <Summary label="Себестоимость" value={formatMoney(totals.total)} accent />
      </div>

      <div className="card overflow-x-auto">
        <table className="w-full min-w-[900px] text-sm">
          <thead className="bg-slate-100 dark:bg-slate-800">
            <tr>
              <th className="px-4 py-3 text-left">Проект</th>
              <th className="px-4 py-3 text-left">Подпроект</th>
              <th className="px-4 py-3 text-right">Часы ТО</th>
              <th className="px-4 py-3 text-right">Выплаченная зарплата</th>
              <th className="px-4 py-3 text-right">Зарплата с налогами</th>
              <th className="px-4 py-3 text-right">Затраты на реализацию</th>
              <th className="px-4 py-3 text-right">Себестоимость</th>
            </tr>
          </thead>
          <tbody>
            {filteredRows.map((row) => (
              <tr key={row.key} className="border-t border-slate-200 dark:border-slate-700">
                <td className="px-4 py-3 font-semibold">{row.projectName}</td>
                <td className="px-4 py-3 text-indigo-600 dark:text-indigo-400">{row.subprojectName || 'Без подпроекта'}</td>
                <td className="px-4 py-3 text-right">{row.hours.toFixed(1)}</td>
                <td className="px-4 py-3 text-right">{formatMoney(row.salary)}</td>
                <td className="px-4 py-3 text-right">{formatMoney(row.salaryWithTax)}</td>
                <td className="px-4 py-3 text-right">
                  <div className="flex items-center justify-end gap-2">
                    <span>{formatMoney(row.realization)}</span>
                    {!row.subprojectName && (
                      <button
                        type="button"
                        onClick={() => openRealizationDialog(row.projectId)}
                        className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-emerald-50 text-emerald-700 border border-emerald-200 hover:bg-emerald-100"
                        title="Добавить затраты на реализацию"
                      >
                        ＋
                      </button>
                    )}
                  </div>
                </td>
                <td className="px-4 py-3 text-right font-black text-emerald-700 dark:text-emerald-400">{formatMoney(row.total)}</td>
              </tr>
            ))}
            {filteredRows.length === 0 && <tr><td colSpan={7} className="px-4 py-10 text-center text-slate-500">Нет данных за выбранный период</td></tr>}
          </tbody>
          <tfoot className="border-t-2 border-slate-300 bg-slate-50 font-bold dark:border-slate-600 dark:bg-slate-800">
            <tr>
              <td className="px-4 py-3" colSpan={2}>Итого</td>
              <td className="px-4 py-3 text-right">{totals.hours.toFixed(1)}</td>
              <td className="px-4 py-3 text-right">{formatMoney(totals.salary)}</td>
              <td className="px-4 py-3 text-right">{formatMoney(totals.salaryWithTax)}</td>
              <td className="px-4 py-3 text-right">{formatMoney(totals.realization)}</td>
              <td className="px-4 py-3 text-right text-emerald-700 dark:text-emerald-400">{formatMoney(totals.total)}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </div>
  );
}

    {showRealizationDialog && (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4" onClick={() => !realizationSaving && setShowRealizationDialog(false)}>
        <div className="w-full max-w-md rounded-2xl bg-white p-5 shadow-2xl dark:bg-slate-900" onClick={(event) => event.stopPropagation()}>
          <h2 className="text-lg font-black text-slate-900 dark:text-slate-100">Добавить затраты на реализацию</h2>
          <p className="mt-1 text-sm text-slate-500">{projects.find((project) => project.id === realizationProjectId)?.name || 'Проект'}</p>
          <div className="mt-4 space-y-3">
            <div className="proles-input-group">
              <label>Сумма *</label>
              <input type="number" min="0" step="0.01" value={realizationAmount} onChange={(event) => setRealizationAmount(event.target.value)} className="input" placeholder="0.00" />
            </div>
            <div className="proles-input-group">
              <label>Комментарий</label>
              <textarea value={realizationComment} onChange={(event) => setRealizationComment(event.target.value)} className="input min-h-24 resize-y" placeholder="Например: доставка, монтаж, упаковка..." />
            </div>
            <div className="proles-input-group">
              <label>Дата</label>
              <input type="date" value={realizationDate} onChange={(event) => setRealizationDate(event.target.value)} className="input" />
            </div>
          </div>
          <div className="mt-5 flex justify-end gap-2">
            <button type="button" onClick={() => setShowRealizationDialog(false)} disabled={realizationSaving} className="btn-outline px-4 py-2">Отмена</button>
            <button type="button" onClick={() => void saveRealizationCost()} disabled={realizationSaving} className="btn-primary px-4 py-2">{realizationSaving ? 'Сохранение…' : 'Добавить'}</button>
          </div>
        </div>
      </div>
    )}

function Summary({ label, value, accent = false }: { label: string; value: string; accent?: boolean }) {
  return <div className={`card p-4 ${accent ? 'border-emerald-300 bg-emerald-50 dark:border-emerald-800 dark:bg-emerald-950/20' : ''}`}><div className="text-xs text-slate-500">{label}</div><div className={`mt-2 text-xl font-black ${accent ? 'text-emerald-700 dark:text-emerald-400' : 'text-slate-900 dark:text-slate-100'}`}>{value}</div></div>;
}