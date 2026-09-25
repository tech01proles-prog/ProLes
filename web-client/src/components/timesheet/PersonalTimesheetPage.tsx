import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import api from '../../api/client';
import { generateUUID } from '../../lib/utils';

interface PersonalTask {
  id: string;
  name: string;
  description: string;
  hours: number;
  category: string;
  status: PersonalTaskStatus;
  isMonthTask?: boolean;
  sortOrder: number;
}

interface PersonalCategoryOption {
  id: string;
  name: string;
}

type PersonalTaskStatus =
  | 'Not started'
  | 'In progress'
  | 'Completed'
  | 'Blocked';

interface DropdownOption {
  value: string;
  label: string;
}

interface StyledDropdownProps {
  value: string;
  options: DropdownOption[];
  placeholder: string;
  activeClassName?: string;
  onChange: (value: string) => void;
  renderOptionSuffix?: (option: DropdownOption) => ReactNode;
}

const PERSONAL_STATUS_OPTIONS: DropdownOption[] = [
  { value: 'Not started', label: 'Не начато' },
  { value: 'In progress', label: 'В работе' },
  { value: 'Completed', label: 'Выполнено' },
  { value: 'Blocked', label: 'Заблокировано' },
];

const PERSONAL_STATUS_LABELS: Record<string, string> = {
  'Not started': 'Не начато',
  'In progress': 'В работе',
  Completed: 'Выполнено',
  Blocked: 'Заблокировано',
};

function getMonthBounds(year: number, month: number) {
  const pad = (value: number) => String(value).padStart(2, '0');
  const lastDay = new Date(year, month, 0).getDate();

  return {
    start: `${year}-${pad(month)}-01`,
    end: `${year}-${pad(month)}-${pad(lastDay)}`,
  };
}

function StyledDropdown({
  value,
  options,
  placeholder,
  activeClassName = '',
  onChange,
  renderOptionSuffix,
}: StyledDropdownProps) {
  if (!renderOptionSuffix) {
    return (
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className={[
          'h-11 w-full rounded-2xl border border-slate-200 bg-white px-3',
          'text-sm font-semibold text-slate-700 outline-none transition',
          'focus:border-[#dc78a2] focus:ring-4 focus:ring-[#f8dce7]',
          activeClassName,
        ].join(' ')}
      >
        <option value="">{placeholder}</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    );
  }

  return (
    <div
      className={[
        'rounded-2xl border border-slate-200 bg-white p-1',
        activeClassName,
      ].join(' ')}
    >
      <select
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="h-10 w-full rounded-xl border-0 bg-transparent px-2 text-sm font-semibold text-slate-700 outline-none"
      >
        <option value="">{placeholder}</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>

      {value && (
        <div className="flex justify-end border-t border-slate-100 pt-1">
          {renderOptionSuffix(
            options.find((option) => option.value === value) ?? {
              value,
              label: value,
            },
          )}
        </div>
      )}
    </div>
  );
}

function PinkStatusChart({
  tasks,
}: {
  tasks: PersonalTask[];
}) {
  const rows = PERSONAL_STATUS_OPTIONS.map((status) => ({
    ...status,
    count: tasks.filter((task) => task.status === status.value).length,
  }));

  const maximum = Math.max(...rows.map((row) => row.count), 1);

  return (
    <section className="rounded-3xl border border-[#f0d7e2] bg-[#fffafd] p-5 shadow-[0_6px_20px_rgba(15,23,42,0.05)]">
      <div className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-slate-400">
        Состояние задач
      </div>

      <div className="mt-5 space-y-4">
        {rows.map((row) => (
          <div key={row.value}>
            <div className="mb-1.5 flex items-center justify-between gap-3">
              <span className="text-sm font-bold text-slate-700">
                {row.label}
              </span>
              <span className="text-sm font-black text-[#a54873]">
                {row.count}
              </span>
            </div>

            <div className="h-2.5 overflow-hidden rounded-full bg-[#f4e5ec]">
              <div
                className="h-full rounded-full bg-gradient-to-r from-[#d96f9b] to-[#edb3c7] transition-[width]"
                style={{
                  width: `${(row.count / maximum) * 100}%`,
                }}
              />
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}

export function PersonalTimesheetPage() {
  const current = new Date();
  const [year, setYear] = useState(current.getFullYear());
  const [month, setMonth] = useState(current.getMonth() + 1);
  const [periodStart, setPeriodStart] = useState(getMonthBounds(current.getFullYear(), current.getMonth() + 1).start);
  const [periodEnd, setPeriodEnd] = useState(getMonthBounds(current.getFullYear(), current.getMonth() + 1).end);
  const [tasks, setTasks] = useState<PersonalTask[]>([]);
  const [monthlyTaskId, setMonthlyTaskId] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [categories, setCategories] = useState<PersonalCategoryOption[]>([]);
  const [newCategory, setNewCategory] = useState('');
  const saveVersionRef = useRef(0);

  const monthLabel = new Date(year, month - 1, 1).toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' });

  const normalizedTasks = tasks.map(task => ({ ...task, hours: Number.isFinite(task.hours) ? task.hours : 0 }));
  const totalHours = normalizedTasks.reduce((sum, task) => sum + task.hours, 0);
  const realTasks = normalizedTasks.filter(task => task.name.trim());
  const totalTasks = realTasks.length;
  const monthTask = tasks.find(task => task.id === monthlyTaskId);
  const isMonthTaskCompleted = monthTask?.status === 'Completed';

  const statusBorderClass: Record<string, string> = {
    'Not started': 'border-[#ef4444] bg-[#fff7f7]',
    'In progress': 'border-[#f59e0b] bg-[#fffbf3]',
    'Completed': 'border-[#22c55e] bg-[#f4fff7]',
    'Blocked': 'border-black bg-[#f7f7f7]',
  };

  const statusBadgeClass: Record<string, string> = {
    'Not started': 'bg-red-50 text-red-700 border-red-200',
    'In progress': 'bg-orange-50 text-orange-700 border-orange-200',
    'Completed': 'bg-green-50 text-green-700 border-green-200',
    'Blocked': 'bg-slate-100 text-slate-900 border-slate-300',
  };

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [{ data }, categoriesResponse] = await Promise.all([
        api.get<{ periodStart: string; periodEnd: string; monthlyTaskId: string | null; tasks: PersonalTask[] }>('/personal-timesheet', { params: { year, month } }),
        api.get<Array<{ id: string; name: string }>>('/personal-timesheet/categories'),
      ]);
      const categoryRows = categoriesResponse.data || [];
      setCategories(categoryRows.map(category => ({ id: category.id, name: category.name })));
      setPeriodStart(data.periodStart);
      setPeriodEnd(data.periodEnd);
      setMonthlyTaskId(data.monthlyTaskId || '');
      setTasks((data.tasks || []).map((task, index) => ({
        id: task.id || generateUUID(),
        name: task.name || '',
        description: task.description || '',
        hours: Number(task.hours) || 0,
        category: task.category || '',
        status: task.status || 'Not started',
        isMonthTask: Boolean(task.isMonthTask),
        sortOrder: index,
      })));
      setDirty(false);
    } catch (error) {
      console.error(error);
      setMessage('Не удалось загрузить табель');
    } finally {
      setLoading(false);
    }
  }, [year, month]);

  useEffect(() => { load(); }, [load]);

  const updateAndDirty = <T,>(updater: () => T) => {
    updater();
    saveVersionRef.current += 1;
    setDirty(true);
  };

  const addRow = () => updateAndDirty(() => {
    const newTask: PersonalTask = {
      id: generateUUID(),
      name: '',
      description: '',
      hours: 0,
      category: '',
      status: 'Not started',
      sortOrder: tasks.length,
    };
    setTasks(prev => [...prev, newTask]);
  });

  const updateTask = (id: string, patch: Partial<PersonalTask>) => updateAndDirty(() => {
    setTasks(prev => prev.map(task => task.id === id ? { ...task, ...patch } : task));
  });

  const deleteRow = (id: string) => updateAndDirty(() => {
    setTasks(prev => prev.filter(task => task.id !== id));
    if (monthlyTaskId === id) setMonthlyTaskId('');
  });

  const addCategory = async () => {
    const name = newCategory.trim();
    if (!name || categories.some(category => category.name.toLowerCase() === name.toLowerCase())) return;
    try {
      const { data } = await api.post<{ id: string; name: string }>('/personal-timesheet/categories', { name });
      setCategories(prev => [...prev, { id: data.id, name: data.name }]);
      setNewCategory('');
      setMessage(`Категория «${data.name}» добавлена`);
    } catch (error) {
      console.error(error);
      setMessage('Не удалось добавить категорию');
    } finally {
      window.setTimeout(() => setMessage(null), 2200);
    }
  };

  const deleteCategory = async (category: PersonalCategoryOption) => {
    if (!category.id) return;
    if (!window.confirm(`Удалить категорию «${category.name}»? Существующие записи с этой категорией останутся без изменений.`)) return;
    try {
      await api.delete(`/personal-timesheet/categories/${category.id}`);
      setCategories(prev => prev.filter(item => item.id !== category.id));
      setMessage(`Категория «${category.name}» удалена`);
    } catch (error) {
      console.error(error);
      setMessage('Не удалось удалить категорию');
    } finally {
      window.setTimeout(() => setMessage(null), 2200);
    }
  };

  const persist = useCallback(async () => {
    if (!dirty || saving || loading) return;
    const versionAtSave = saveVersionRef.current;
    setSaving(true);
    try {
      await api.put('/personal-timesheet', {
        year,
        month,
        periodStart,
        periodEnd,
        monthlyTaskId: monthlyTaskId || null,
        tasks: tasks.map((task, index) => ({
          id: task.id,
          name: task.name,
          description: task.description,
          hours: Number(task.hours) || 0,
          category: task.category,
          status: task.status || 'Not started',
          sortOrder: index,
        })),
      });
      if (versionAtSave === saveVersionRef.current) {
        setDirty(false);
        setMessage('Сохранено автоматически');
      }
    } catch (error) {
      console.error(error);
      setMessage('Ошибка автосохранения');
    } finally {
      setSaving(false);
      window.setTimeout(() => setMessage(null), 1800);
    }
  }, [dirty, saving, loading, year, month, periodStart, periodEnd, monthlyTaskId, tasks]);

  useEffect(() => {
    if (!dirty || loading) return;
    const timer = window.setTimeout(() => { void persist(); }, 850);
    return () => window.clearTimeout(timer);
  }, [dirty, loading, persist]);

  const shiftMonth = (delta: number) => {
    let nextMonth = month + delta;
    let nextYear = year;
    if (nextMonth < 1) { nextMonth = 12; nextYear -= 1; }
    if (nextMonth > 12) { nextMonth = 1; nextYear += 1; }
    setYear(nextYear);
    setMonth(nextMonth);
  };

  const monthTaskOptions = tasks.filter(task => task.name.trim()).map(task => ({ value: task.id, label: task.name }));
  const categoryOptions = categories.map(category => ({ value: category.name, label: category.name }));

  if (loading) {
    return <div className="flex justify-center py-16"><div className="h-10 w-10 animate-spin rounded-full border-4 border-[#edbfd1] border-t-[#b64b78]" /></div>;
  }

  return (
    <div className="min-h-full bg-[radial-gradient(circle_at_top_left,#fff1f7_0%,#fff8fb_34%,#f8f7fb_100%)] text-slate-800 font-sans">
      <div className="mx-auto w-full max-w-[1440px] px-3 py-1 md:px-5 md:py-2">
        <div className="overflow-visible rounded-[28px] border border-[#efd4e0] bg-white shadow-[0_12px_42px_rgba(110,55,78,0.09)]">
          <div className="relative overflow-hidden rounded-t-[28px] bg-gradient-to-r from-[#c98fab] via-[#dc9db9] to-[#edb3c7] px-5 py-4 md:px-7 md:py-5">
            <div className="absolute -right-10 -top-16 h-40 w-40 rounded-full bg-white/15 blur-2xl" />
            <div className="relative flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
              <div>
                <div className="text-[9px] font-black uppercase tracking-[0.32em] text-[#6f2948]">Персональный табель</div>
                <h1 className="mt-0.5 text-3xl font-black tracking-tight text-[#1a1020] md:text-[42px]">{new Date(year, month - 1, 1).toLocaleDateString('ru-RU', { month: 'long' }).toUpperCase()}</h1>
              </div>
              <div className="flex items-center gap-2 self-start rounded-2xl bg-white/70 p-1 shadow-sm backdrop-blur md:self-auto">
                <button type="button" onClick={() => shiftMonth(-1)} className="grid h-9 w-9 place-items-center rounded-xl text-lg font-bold text-[#6d2949] transition hover:bg-white" aria-label="Предыдущий месяц">‹</button>
                <div className="min-w-[150px] px-1 text-center text-sm font-extrabold capitalize text-[#53233a]">{monthLabel}</div>
                <button type="button" onClick={() => shiftMonth(1)} className="grid h-9 w-9 place-items-center rounded-xl text-lg font-bold text-[#6d2949] transition hover:bg-white" aria-label="Следующий месяц">›</button>
              </div>
            </div>
          </div>

          <div className="space-y-4 p-3 md:p-5">
            <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1fr)_420px]">
              <div className="rounded-3xl border border-slate-200 bg-white p-4 shadow-[0_6px_20px_rgba(15,23,42,0.05)] md:p-5">
                <div className="grid gap-4 md:grid-cols-[minmax(0,1fr)_180px]">
                  <div className="space-y-3">
                    <div>
                      <div className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-slate-400">Отраженный период</div>
                      <div className="mt-1.5 flex flex-col gap-2 sm:flex-row sm:items-center">
                        <input type="date" value={periodStart} onChange={e => updateAndDirty(() => setPeriodStart(e.target.value))} className="h-11 w-full rounded-2xl border border-slate-200 bg-slate-50 px-3 text-sm font-bold text-slate-700 shadow-inner outline-none transition focus:border-[#dc78a2] focus:bg-white focus:ring-4 focus:ring-[#f8dce7]" />
                        <span className="hidden font-bold text-slate-300 sm:block">—</span>
                        <input type="date" value={periodEnd} onChange={e => updateAndDirty(() => setPeriodEnd(e.target.value))} className="h-11 w-full rounded-2xl border border-slate-200 bg-slate-50 px-3 text-sm font-bold text-slate-700 shadow-inner outline-none transition focus:border-[#dc78a2] focus:bg-white focus:ring-4 focus:ring-[#f8dce7]" />
                      </div>
                    </div>
                    <div className="grid gap-3 sm:grid-cols-2">
                      <div className={`rounded-2xl p-3 transition-all ${isMonthTaskCompleted ? 'border border-green-300 bg-green-50 shadow-[0_8px_22px_rgba(34,197,94,0.14)]' : 'border border-[#f0d7e2] bg-[#fffafd]'}`}>
                        <div className="flex items-center justify-between gap-2">
                          <div className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-slate-400">Задача месяца</div>
                          {isMonthTaskCompleted && <span className="rounded-full bg-green-600 px-2 py-1 text-[9px] font-black uppercase tracking-wide text-white">Выполнено</span>}
                        </div>
                        <div className="mt-2">
                          <StyledDropdown
                            value={monthlyTaskId}
                            options={monthTaskOptions}
                            onChange={value => updateAndDirty(() => setMonthlyTaskId(value))}
                            placeholder="Выберите задачу"
                            activeClassName={isMonthTaskCompleted ? 'border-green-300 bg-white' : ''}
                          />
                        </div>
                      </div>
                      <div className="rounded-2xl border border-slate-200 bg-slate-50 p-3">
                        <div className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-slate-400">Потрачено часов</div>
                        <div className="mt-1 text-3xl font-black tracking-tight text-slate-800">{totalHours.toLocaleString('ru-RU')}</div>
                      </div>
                    </div>
                  </div>

                  <div className="flex min-h-[150px] flex-col justify-between rounded-3xl bg-gradient-to-b from-[#dca0ba] to-[#cb87a8] p-4 text-center shadow-[0_10px_26px_rgba(195,102,145,0.20)]">
                    <div className="text-[11px] font-black uppercase tracking-[0.22em] text-[#5e2942]">Всего</div>
                    <div className="text-5xl font-black leading-none tracking-tight text-[#183e4a]">{totalTasks}</div>
                    <div className="text-[10px] font-bold uppercase tracking-widest text-[#6e304a]">задач</div>
                  </div>
                </div>
              </div>

              <PinkStatusChart tasks={normalizedTasks} />
            </div>

            <div className="flex flex-col gap-3 rounded-3xl border border-[#f0d7e2] bg-[#fffafd] p-3 md:flex-row md:items-center md:justify-between md:p-4">
              <div className="flex items-center gap-3">
                <div className="grid h-10 w-10 place-items-center rounded-2xl bg-[#f7d8e6] text-base">≡</div>
                <div>
                  <div className="font-black text-slate-800">Задачи месяца</div>
                  <div className="text-[11px] text-slate-500">Изменения сохраняются автоматически.</div>
                </div>
              </div>
              <div className="flex flex-col gap-2 md:flex-row md:items-center md:justify-end">
                <div className={`rounded-full px-3 py-1.5 text-xs font-bold ${saving ? 'bg-[#fce9f1] text-[#a54873]' : dirty ? 'bg-amber-100 text-amber-700' : 'bg-emerald-50 text-emerald-700'}`}>
                  {saving ? 'Сохраняем…' : dirty ? 'Изменения ожидают сохранения' : 'Сохранено'}
                </div>
                <div className="flex items-center gap-1 rounded-2xl border border-slate-200 bg-white p-1 shadow-sm">
                  <input
                    value={newCategory}
                    onChange={e => setNewCategory(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); void addCategory(); } }}
                    className="w-[180px] rounded-xl border-0 px-3 py-2 text-sm outline-none placeholder:text-slate-400"
                    placeholder="Новая категория"
                    aria-label="Новая категория"
                  />
                  <button type="button" onClick={() => void addCategory()} className="grid h-9 w-9 place-items-center rounded-xl bg-emerald-600 text-lg font-bold text-white shadow-sm transition hover:bg-emerald-700" title="Добавить категорию">＋</button>
                </div>
                <button type="button" onClick={addRow} className="rounded-2xl bg-emerald-600 px-4 py-2.5 text-sm font-extrabold text-white shadow-[0_8px_20px_rgba(16,185,129,0.22)] transition hover:-translate-y-0.5 hover:bg-emerald-700">＋ Добавить строку</button>
              </div>
            </div>

            <div className="overflow-visible rounded-3xl border border-[#e7d3dd] bg-white shadow-[0_8px_26px_rgba(30,20,25,0.05)]">
              <div className="overflow-x-auto">
                <table className="w-full min-w-[1060px] border-collapse text-sm">
                  <thead>
                    <tr className="bg-gradient-to-r from-[#d96f9b] via-[#e17cac] to-[#d96f9b] text-white">
                      <th className="border-r border-white/20 px-4 py-3 text-left text-[10px] font-black uppercase tracking-[0.16em] w-[24%]">Задача</th>
                      <th className="border-r border-white/20 px-4 py-3 text-left text-[10px] font-black uppercase tracking-[0.16em] w-[28%]">Описание</th>
                      <th className="border-r border-white/20 px-4 py-3 text-center text-[10px] font-black uppercase tracking-[0.16em] w-[11%]">Часы</th>
                      <th className="border-r border-white/20 px-4 py-3 text-left text-[10px] font-black uppercase tracking-[0.16em] w-[17%]">Категория</th>
                      <th className="border-r border-white/20 px-4 py-3 text-left text-[10px] font-black uppercase tracking-[0.16em] w-[16%]">Статус</th>
                      <th className="px-3 py-3 w-[4%]"></th>
                    </tr>
                  </thead>
                  <tbody>
                    {tasks.map((task, index) => {
                      const rowStatusClass = statusBorderClass[task.status] || 'border-slate-200 bg-white';
                      const statusClass = statusBadgeClass[task.status] || 'bg-slate-50 text-slate-700 border-slate-200';
                      const rowFillClass: Record<string, string> = {
                        'Not started': 'bg-red-50/90',
                        'In progress': 'bg-orange-50/90',
                        'Completed': 'bg-emerald-50/90',
                        'Blocked': 'bg-slate-100/95',
                      };
                      const rowClass = rowFillClass[task.status] || (index % 2 ? 'bg-[#fffafd]' : 'bg-white');
                      return (
                        <tr key={task.id} className={`${rowClass} h-[82px] transition-colors hover:brightness-[0.985]`}>
                          <td className={`border-b border-slate-200 p-1 align-top ${rowClass}`}>
                            <div className={`rounded-xl border-2 ${rowStatusClass} h-full p-1 transition-shadow focus-within:shadow-[0_6px_16px_rgba(15,23,42,0.06)]`}>
                              <textarea value={task.name} onChange={e => updateTask(task.id, { name: e.target.value })} className="h-[58px] w-full resize-none rounded-xl border-0 bg-white/50 px-3 py-2.5 text-sm font-bold leading-relaxed outline-none placeholder:text-slate-400 focus:bg-white" placeholder="Название задачи" rows={3} />
                            </div>
                          </td>
                          <td className={`border-b border-slate-200 p-1 align-top ${rowClass}`}>
                            <textarea value={task.description} onChange={e => updateTask(task.id, { description: e.target.value })} className="h-[58px] w-full resize-none rounded-2xl border border-slate-200 bg-white/60 px-3 py-2.5 text-sm leading-relaxed outline-none transition focus:border-[#edbfd1] focus:ring-4 focus:ring-[#fae6ef]" placeholder="Что необходимо сделать / результат" />
                          </td>
                          <td className={`border-b border-slate-200 p-1 align-top ${rowClass}`}>
                            <input type="number" min="0" step="0.5" value={task.hours || ''} onChange={e => updateTask(task.id, { hours: Number(e.target.value) || 0 })} className="h-[58px] w-full rounded-2xl border border-slate-200 bg-white/60 px-3 text-center text-lg font-black outline-none transition focus:border-[#edbfd1] focus:bg-white focus:ring-4 focus:ring-[#fae6ef]" placeholder="0" />
                          </td>
                          <td className={`border-b border-slate-200 p-1 align-top ${rowClass}`}>
                            <StyledDropdown
                              value={task.category}
                              options={categoryOptions}
                              onChange={value => updateTask(task.id, { category: value })}
                              placeholder="Категория"
                              renderOptionSuffix={option => {
                                const category = categories.find(item => item.name === option.value);
                                return category?.id ? (
                                  <button
                                    type="button"
                                    onClick={event => { event.stopPropagation(); void deleteCategory(category); }}
                                    className="mr-1 rounded-lg px-2 py-1.5 text-[11px] font-bold text-slate-400 transition hover:bg-red-50 hover:text-red-600"
                                    title={`Удалить категорию «${category.name}»`}
                                  >
                                    Удалить
                                  </button>
                                ) : null;
                              }}
                            />
                          </td>
                          <td className={`border-b border-slate-200 p-1 align-top ${rowClass}`}>
                            <StyledDropdown value={task.status} options={PERSONAL_STATUS_OPTIONS} onChange={value => updateTask(task.id, { status: value })} placeholder="Статус" />
                            <div className={`mt-1.5 inline-flex rounded-full border px-2.5 py-1 text-[10px] font-extrabold ${statusClass}`}>{PERSONAL_STATUS_LABELS[task.status] || task.status}</div>
                          </td>
                          <td className={`border-b border-slate-200 p-1 text-center align-top ${rowClass}`}>
                            <button type="button" onClick={() => deleteRow(task.id)} className="min-w-[34px] rounded-xl border border-red-200 bg-red-50 px-2.5 py-2 text-[10px] font-black uppercase tracking-wide text-red-600 transition hover:bg-red-600 hover:text-white" title="Удалить задачу">Удалить</button>
                          </td>
                        </tr>
                      );
                    })}
                    {tasks.length === 0 && (
                      <tr>
                        <td colSpan={6} className="px-6 py-10 text-center">
                          <div className="mx-auto grid h-12 w-12 place-items-center rounded-2xl bg-[#fff0f6] text-xl text-[#a54873]">＋</div>
                          <div className="mt-2 font-black text-slate-700">Пока нет задач</div>
                          <div className="mt-1 text-sm text-slate-400">Добавьте первую строку, чтобы начать вести табель.</div>
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      </div>
      {message && (
        <div className="fixed bottom-5 right-5 z-50 rounded-2xl bg-[#173f4c] px-5 py-3 text-sm font-bold text-white shadow-2xl">
          {message}
        </div>
      )}
    </div>
  );
}