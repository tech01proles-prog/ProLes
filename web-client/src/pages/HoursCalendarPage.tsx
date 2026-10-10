import { useState, useEffect, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import api from '../api/client';
import type { TimeEntryDto, UserDto, ProjectDto, DayOffDto } from '../types';
import { monthName } from '../lib/utils';

// Стабильный цвет для сотрудника, чтобы его легко было узнавать в календаре.
const USER_COLORS = [
  '#ef4444', '#f59e0b', '#eab308', '#84cc16', '#22c55e',
  '#14b8a6', '#06b6d4', '#3b82f6', '#6366f1', '#8b5cf6',
  '#a855f7', '#ec4899', '#f97316',
];

function getUserColor(userId: string): string {
  let hash = 0;
  for (let i = 0; i < userId.length; i++) {
    hash = userId.charCodeAt(i) + ((hash << 5) - hash);
  }
  return USER_COLORS[Math.abs(hash) % USER_COLORS.length];
}

export function HoursCalendarPage() {
  const [searchParams] = useSearchParams();
  const filterUserId = searchParams.get('userId');
  const [entries, setEntries] = useState<TimeEntryDto[]>([]);
  const [users, setUsers] = useState<UserDto[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [dayOffs, setDayOffs] = useState<DayOffDto[]>([]);
  const [loading, setLoading] = useState(true);

  const today = new Date();
  const todayString = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
  const [calYear, setCalYear] = useState(today.getFullYear());
  const [calMonth, setCalMonth] = useState(today.getMonth());
  const [selectedUserIds, setSelectedUserIds] = useState<Set<string>>(() => filterUserId ? new Set([filterUserId]) : new Set());
  const [selectedProjectIds, setSelectedProjectIds] = useState<Set<string>>(new Set());
  const [selectedDate, setSelectedDate] = useState(todayString);

  useEffect(() => {
    let mounted = true;
    (async () => {
      setLoading(true);
      const [entriesResult, usersResult, projectsResult, dayOffsResult] = await Promise.allSettled([
        api.get<TimeEntryDto[]>('/entries/all'),
        api.get<UserDto[]>('/users'),
        api.get<ProjectDto[]>('/projects'),
        api.get<DayOffDto[]>('/dayoffs/all'),
      ]);
      if (!mounted) return;
      setEntries(entriesResult.status === 'fulfilled' ? entriesResult.value.data : []);
      setUsers(usersResult.status === 'fulfilled' ? usersResult.value.data : []);
      setProjects(projectsResult.status === 'fulfilled' ? projectsResult.value.data : []);
      setDayOffs(dayOffsResult.status === 'fulfilled' ? dayOffsResult.value.data : []);
      setLoading(false);
    })();
    return () => { mounted = false; };
  }, []);

  useEffect(() => {
    if (filterUserId) setSelectedUserIds(new Set([filterUserId]));
  }, [filterUserId]);

  const monthKey = `${calYear}-${String(calMonth + 1).padStart(2, '0')}`;
  const selectedUserId = selectedUserIds.size === 1 ? Array.from(selectedUserIds)[0] : '';
  const selectedProjectId = selectedProjectIds.size === 1 ? Array.from(selectedProjectIds)[0] : '';

  const filteredEntries = useMemo(() => entries.filter(entry => {
    if (!entry.date.startsWith(monthKey)) return false;
    if (selectedUserIds.size > 0 && !selectedUserIds.has(entry.userId)) return false;
    if (selectedProjectIds.size > 0 && !selectedProjectIds.has(entry.projectId)) return false;
    return true;
  }), [entries, monthKey, selectedUserIds, selectedProjectIds]);

  const filteredDayOffs = useMemo(() => dayOffs.filter(dayOff => {
    if (!dayOff.date.startsWith(monthKey)) return false;
    if (selectedUserIds.size > 0 && !selectedUserIds.has(dayOff.user_id)) return false;
    return true;
  }), [dayOffs, monthKey, selectedUserIds]);

  const entriesByDate = useMemo(() => {
    const grouped = new Map<string, TimeEntryDto[]>();
    filteredEntries.forEach(entry => {
      const dayEntries = grouped.get(entry.date) || [];
      dayEntries.push(entry);
      grouped.set(entry.date, dayEntries);
    });
    return grouped;
  }, [filteredEntries]);

  const dayOffsByDate = useMemo(() => {
    const grouped = new Map<string, DayOffDto[]>();
    filteredDayOffs.forEach(dayOff => {
      const days = grouped.get(dayOff.date) || [];
      days.push(dayOff);
      grouped.set(dayOff.date, days);
    });
    return grouped;
  }, [filteredDayOffs]);

  const monthHours = filteredEntries.reduce((sum, entry) => sum + entry.hours, 0);
  const monthWorkDays = new Set(filteredEntries.map(entry => entry.date)).size;
  const monthEmployees = new Set(filteredEntries.map(entry => entry.userId)).size;
  const actualDayOffs = filteredDayOffs.filter(dayOff =>
    !filteredEntries.some(entry => entry.userId === dayOff.user_id && entry.date === dayOff.date && entry.hours > 0)
  );

  const selectedDateEntries = entriesByDate.get(selectedDate) || [];
  const selectedDateDayOffs = (dayOffsByDate.get(selectedDate) || []).filter(dayOff =>
    !selectedDateEntries.some(entry => entry.userId === dayOff.user_id && entry.hours > 0)
  );
  const selectedDateHours = selectedDateEntries.reduce((sum, entry) => sum + entry.hours, 0);

  const daysInMonth = new Date(calYear, calMonth + 1, 0).getDate();
  let startWeekday = new Date(calYear, calMonth, 1).getDay() - 1;
  if (startWeekday < 0) startWeekday = 6;
  const weekdays = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];

  const changeMonth = (offset: number) => {
    const target = new Date(calYear, calMonth + offset, 1);
    setCalYear(target.getFullYear());
    setCalMonth(target.getMonth());
    setSelectedDate(`${target.getFullYear()}-${String(target.getMonth() + 1).padStart(2, '0')}-01`);
  };

  const goToToday = () => {
    setCalYear(today.getFullYear());
    setCalMonth(today.getMonth());
    setSelectedDate(todayString);
  };

  const setEmployeeFilter = (userId: string) => setSelectedUserIds(userId ? new Set([userId]) : new Set());
  const setProjectFilter = (projectId: string) => setSelectedProjectIds(projectId ? new Set([projectId]) : new Set());
  const resetFilters = () => {
    setSelectedUserIds(new Set());
    setSelectedProjectIds(new Set());
  };

  if (loading) {
    return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;
  }

  const selectedDateLabel = new Date(`${selectedDate}T12:00:00`).toLocaleDateString('ru-RU', {
    weekday: 'long', day: 'numeric', month: 'long', year: 'numeric',
  });

  return (
    <div className="mx-auto max-w-7xl space-y-5">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">Часы сотрудников</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">Выберите сотрудника или проект, затем день в календаре для просмотра записей.</p>
        </div>
        <button onClick={goToToday} className="btn-outline inline-flex items-center justify-center gap-2 px-4 py-2 text-sm">
          <span aria-hidden="true">◎</span> Сегодня
        </button>
      </div>

      <section className="card grid grid-cols-1 gap-3 p-4 sm:grid-cols-2 lg:grid-cols-[1fr_1fr_auto] lg:items-end">
        <label className="block min-w-0">
          <span className="mb-1.5 block text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">Сотрудник</span>
          <select value={selectedUserId} onChange={event => setEmployeeFilter(event.target.value)} className="input w-full bg-white dark:bg-slate-900">
            <option value="">Все сотрудники</option>
            {users.slice().sort((a, b) => a.name.localeCompare(b.name, 'ru')).map(item => (
              <option key={item.id} value={item.id}>{item.name}</option>
            ))}
          </select>
        </label>
        <label className="block min-w-0">
          <span className="mb-1.5 block text-xs font-bold uppercase tracking-wide text-slate-500 dark:text-slate-400">Проект</span>
          <select value={selectedProjectId} onChange={event => setProjectFilter(event.target.value)} className="input w-full bg-white dark:bg-slate-900">
            <option value="">Все проекты</option>
            {projects.filter(project => project.isActive).slice().sort((a, b) => a.name.localeCompare(b.name, 'ru')).map(project => (
              <option key={project.id} value={project.id}>{project.name}</option>
            ))}
          </select>
        </label>
        <div className="flex items-center justify-between gap-3 lg:justify-end">
          <span className="text-xs text-slate-500 dark:text-slate-400">
            {selectedUserIds.size + selectedProjectIds.size > 0 ? 'Фильтр активен' : 'Показаны все данные'}
          </span>
          {(selectedUserIds.size > 0 || selectedProjectIds.size > 0) && (
            <button onClick={resetFilters} className="rounded-lg px-3 py-2 text-sm font-semibold text-indigo-600 hover:bg-indigo-50 dark:text-indigo-300 dark:hover:bg-indigo-950/30">
              Сбросить
            </button>
          )}
        </div>
      </section>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div className="card flex items-center gap-3 border-blue-100 bg-gradient-to-br from-blue-50 to-indigo-50 p-4 dark:border-blue-900 dark:from-blue-950/30 dark:to-indigo-950/30">
          <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-blue-100 text-blue-700 dark:bg-blue-900/60 dark:text-blue-300">◷</div>
          <div>
            <div className="text-xs font-bold uppercase tracking-wide text-blue-700 dark:text-blue-300">Часов за месяц</div>
            <div className="mt-0.5 text-2xl font-black text-blue-950 dark:text-blue-100">{monthHours.toLocaleString('ru-RU', { maximumFractionDigits: 1 })}</div>
          </div>
        </div>
        <div className="card flex items-center gap-3 border-emerald-100 bg-gradient-to-br from-emerald-50 to-green-50 p-4 dark:border-emerald-900 dark:from-emerald-950/30 dark:to-green-950/30">
          <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-emerald-100 text-emerald-700 dark:bg-emerald-900/60 dark:text-emerald-300">▤</div>
          <div>
            <div className="text-xs font-bold uppercase tracking-wide text-emerald-700 dark:text-emerald-300">Записей</div>
            <div className="mt-0.5 text-2xl font-black text-emerald-950 dark:text-emerald-100">{filteredEntries.length}</div>
          </div>
        </div>
        <div className="card flex items-center gap-3 border-violet-100 bg-gradient-to-br from-violet-50 to-fuchsia-50 p-4 dark:border-violet-900 dark:from-violet-950/30 dark:to-fuchsia-950/30">
          <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-violet-100 text-violet-700 dark:bg-violet-900/60 dark:text-violet-300">◎</div>
          <div>
            <div className="text-xs font-bold uppercase tracking-wide text-violet-700 dark:text-violet-300">Сотрудников</div>
            <div className="mt-0.5 text-2xl font-black text-violet-950 dark:text-violet-100">{monthEmployees}</div>
            <div className="text-[11px] text-violet-700/75 dark:text-violet-300/75">{monthWorkDays} рабочих дней · {actualDayOffs.length} выходных</div>
          </div>
        </div>
      </div>

      <section className="card overflow-hidden p-3 sm:p-5">
        <div className="mb-4 flex items-center justify-between gap-2">
          <button type="button" onClick={() => changeMonth(-1)} className="flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 text-xl text-slate-600 transition hover:bg-slate-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800" aria-label="Предыдущий месяц">‹</button>
          <div className="text-center">
            <h2 className="text-lg font-bold capitalize text-slate-900 dark:text-slate-100">{monthName(calMonth + 1)} {calYear}</h2>
            <p className="text-xs text-slate-500 dark:text-slate-400">Нажмите на день, чтобы увидеть подробности</p>
          </div>
          <button type="button" onClick={() => changeMonth(1)} className="flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 text-xl text-slate-600 transition hover:bg-slate-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800" aria-label="Следующий месяц">›</button>
        </div>

        <div className="grid grid-cols-7 gap-1.5 sm:gap-2">
          {weekdays.map((weekday, index) => (
            <div key={weekday} className={`py-2 text-center text-[10px] font-bold uppercase tracking-wide sm:text-xs ${index >= 5 ? 'text-rose-500' : 'text-slate-400 dark:text-slate-500'}`}>
              {weekday}
            </div>
          ))}
          {Array.from({ length: startWeekday }).map((_, index) => <div key={`empty-${index}`} className="min-h-[72px] sm:min-h-[104px]" />)}
          {Array.from({ length: daysInMonth }).map((_, index) => {
            const day = index + 1;
            const date = `${calYear}-${String(calMonth + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
            const dayEntries = entriesByDate.get(date) || [];
            const rawDayOffs = dayOffsByDate.get(date) || [];
            const dayOffsForCell = rawDayOffs.filter(dayOff =>
              !dayEntries.some(entry => entry.userId === dayOff.user_id && entry.hours > 0)
            );
            const hours = dayEntries.reduce((sum, entry) => sum + entry.hours, 0);
            const employeeCount = new Set(dayEntries.map(entry => entry.userId)).size;
            const isSelected = date === selectedDate;
            const isToday = date === todayString;
            const weekdayIndex = (startWeekday + index) % 7;
            const weekend = weekdayIndex >= 5;
            return (
              <button
                key={date}
                type="button"
                onClick={() => setSelectedDate(date)}
                aria-pressed={isSelected}
                className={`flex min-h-[72px] flex-col rounded-xl border p-1.5 text-left transition sm:min-h-[104px] sm:p-2 ${isSelected ? 'border-indigo-500 bg-indigo-50 ring-2 ring-indigo-200 dark:border-indigo-400 dark:bg-indigo-950/40 dark:ring-indigo-900' : isToday ? 'border-indigo-300 bg-white dark:border-indigo-700 dark:bg-slate-900' : dayOffsForCell.length > 0 ? 'border-rose-100 bg-rose-50/70 dark:border-rose-900 dark:bg-rose-950/20' : weekend ? 'border-slate-100 bg-slate-50 dark:border-slate-800 dark:bg-slate-800/40' : 'border-slate-100 bg-white hover:border-indigo-200 hover:bg-indigo-50/40 dark:border-slate-800 dark:bg-slate-900 dark:hover:border-indigo-800'}`}
              >
                <span className={`flex h-6 w-6 items-center justify-center rounded-lg text-xs font-bold sm:h-7 sm:w-7 sm:text-sm ${isToday ? 'bg-indigo-600 text-white' : isSelected ? 'text-indigo-700 dark:text-indigo-200' : weekend ? 'text-rose-500' : 'text-slate-700 dark:text-slate-200'}`}>{day}</span>
                {hours > 0 ? (
                  <span className="mt-1.5 block text-[10px] font-black leading-tight text-blue-700 dark:text-blue-300 sm:mt-2 sm:text-sm">
                    {hours.toLocaleString('ru-RU', { maximumFractionDigits: 1 })} ч
                  </span>
                ) : <span className="mt-1.5 block text-[10px] text-slate-300 dark:text-slate-700 sm:mt-2">—</span>}
                <span className="mt-auto pt-1 text-[9px] leading-tight text-slate-500 dark:text-slate-400 sm:text-[11px]">
                  {dayEntries.length > 0 ? `${employeeCount} сотр. · ${dayEntries.length} зап.` : dayOffsForCell.length > 0 ? `${dayOffsForCell.length} выходн.` : ' '}
                </span>
              </button>
            );
          })}
        </div>
        <div className="mt-4 flex flex-wrap gap-x-4 gap-y-2 border-t border-slate-100 pt-3 text-xs text-slate-500 dark:border-slate-800 dark:text-slate-400">
          <span className="inline-flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-full bg-indigo-600" /> Выбранный день</span>
          <span className="inline-flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-full bg-blue-500" /> Есть записи часов</span>
          <span className="inline-flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-full bg-rose-400" /> Выходной сотрудника</span>
        </div>
      </section>

      <section className="card overflow-hidden">
        <div className="flex flex-col gap-3 border-b border-slate-100 bg-slate-50/80 px-4 py-4 dark:border-slate-800 dark:bg-slate-800/50 sm:flex-row sm:items-center sm:justify-between sm:px-5">
          <div>
            <h2 className="text-lg font-bold capitalize text-slate-900 dark:text-slate-100">{selectedDateLabel}</h2>
            <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">{selectedDateEntries.length} записей · {selectedDateHours.toLocaleString('ru-RU', { maximumFractionDigits: 1 })} часов</p>
          </div>
          <span className="w-fit rounded-xl bg-blue-100 px-3 py-1.5 text-sm font-black text-blue-800 dark:bg-blue-900/60 dark:text-blue-200">
            {selectedDateHours.toLocaleString('ru-RU', { maximumFractionDigits: 1 })} ч
          </span>
        </div>
        <div className="space-y-2 p-3 sm:p-5">
          {selectedDateEntries.length === 0 && selectedDateDayOffs.length === 0 ? (
            <div className="py-8 text-center">
              <div className="mb-2 text-3xl">◷</div>
              <p className="font-semibold text-slate-700 dark:text-slate-200">За этот день записей нет</p>
              <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">Выберите другую дату или снимите фильтры.</p>
            </div>
          ) : (
            <>
              {selectedDateEntries.map(entry => {
                const employee = users.find(item => item.id === entry.userId);
                const projectName = entry.projectName || projects.find(item => item.id === entry.projectId)?.name || 'Без проекта';
                return (
                  <div key={entry.id} className="flex min-w-0 items-center gap-3 rounded-xl border border-slate-100 bg-white p-3 transition hover:border-blue-200 dark:border-slate-800 dark:bg-slate-900 dark:hover:border-blue-900 sm:gap-4">
                    <span className="h-10 w-1.5 flex-shrink-0 rounded-full" style={{ backgroundColor: getUserColor(entry.userId) }} />
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-sm font-bold text-slate-900 dark:text-slate-100">{projectName}</div>
                      <div className="mt-0.5 truncate text-xs text-slate-500 dark:text-slate-400">{employee?.name || 'Неизвестный сотрудник'}</div>
                      {entry.comment && <div className="mt-1 truncate text-xs italic text-slate-400 dark:text-slate-500">{entry.comment}</div>}
                    </div>
                    <div className="flex-shrink-0 rounded-lg bg-blue-50 px-3 py-2 text-sm font-black tabular-nums text-blue-800 dark:bg-blue-950/40 dark:text-blue-300">{entry.hours.toLocaleString('ru-RU', { maximumFractionDigits: 1 })} ч</div>
                  </div>
                );
              })}
              {selectedDateDayOffs.map(dayOff => (
                <div key={`${dayOff.user_id}-${dayOff.date}`} className="flex items-center gap-3 rounded-xl border border-rose-100 bg-rose-50/70 p-3 dark:border-rose-900 dark:bg-rose-950/20">
                  <span className="h-10 w-1.5 rounded-full bg-rose-400" />
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm font-bold text-slate-900 dark:text-slate-100">{users.find(item => item.id === dayOff.user_id)?.name || 'Неизвестный сотрудник'}</div>
                    <div className="text-xs font-medium text-rose-600 dark:text-rose-300">Выходной день</div>
                  </div>
                </div>
              ))}
            </>
          )}
        </div>
      </section>

      {selectedUserIds.size === 1 && (
        <details className="group">
          <summary className="card flex cursor-pointer list-none items-center justify-between gap-3 p-4 font-semibold text-slate-800 transition hover:border-indigo-200 dark:text-slate-100 dark:hover:border-indigo-800">
            <span>Сводка сотрудника по проектам за месяц</span>
            <span className="text-slate-400 transition group-open:rotate-180">⌄</span>
          </summary>
          <div className="mt-3">
            <SelectedUsersHoursPanel entries={filteredEntries} users={users} projects={projects} year={calYear} month={calMonth} />
          </div>
        </details>
      )}
    </div>
  );
}

function formatHoursDetailed(hours: number): string {
  const totalMinutes = Math.round(hours * 60);
  const h = Math.floor(totalMinutes / 60);
  const m = totalMinutes % 60;
  if (m === 0) return `${h} ч`;
  return `${h} ч ${m} мин`;
}

function shortName(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return 'Неизвестный';
  const surname = parts[0];
  const initials = parts.slice(1, 3).map(p => `${p[0]}.`).join('');
  return initials ? `${surname} ${initials}` : surname;
}

type SelectedUsersHoursPanelProps = {
  entries: TimeEntryDto[];
  users: UserDto[];
  projects: ProjectDto[];
  year: number;
  month: number;
};

function SelectedUsersHoursPanel({
  entries,
  users,
  projects,
  year,
  month,
}: SelectedUsersHoursPanelProps) {
  const byUser = useMemo(() => {
    const userMap = new Map<string, TimeEntryDto[]>();

    entries.forEach(entry => {
      const current = userMap.get(entry.userId) || [];
      current.push(entry);
      userMap.set(entry.userId, current);
    });

    return Array.from(userMap.entries())
      .map(([userId, userEntries]) => {
        const projectMap = new Map<string, TimeEntryDto[]>();

        userEntries.forEach(entry => {
          const key = entry.projectId || '__without_project__';
          const current = projectMap.get(key) || [];
          current.push(entry);
          projectMap.set(key, current);
        });

        const projectGroups = Array.from(projectMap.entries())
          .map(([projectId, projectEntries]) => {
            const first = projectEntries[0];
            const project = projects.find(
              item => item.id === first.projectId,
            );

            return {
              projectId,
              projectName:
                first.projectName ||
                project?.name ||
                'Без проекта',
              total: projectEntries.reduce(
                (sum, entry) => sum + entry.hours,
                0,
              ),
              entries: [...projectEntries].sort((a, b) =>
                `${a.date}-${a.id}`.localeCompare(
                  `${b.date}-${b.id}`,
                ),
              ),
            };
          })
          .sort((a, b) => b.total - a.total);

        return {
          userId,
          user: users.find(item => item.id === userId),
          total: userEntries.reduce(
            (sum, entry) => sum + entry.hours,
            0,
          ),
          days: new Set(userEntries.map(entry => entry.date)).size,
          projectGroups,
        };
      })
      .sort((a, b) => b.total - a.total);
  }, [entries, projects, users]);

  const monthLabel = new Date(year, month, 1).toLocaleDateString(
    'ru-RU',
    {
      month: 'long',
      year: 'numeric',
    },
  );

  const totalHours = entries.reduce(
    (sum, entry) => sum + entry.hours,
    0,
  );

  return (
    <div className="card overflow-hidden border-indigo-200 dark:border-indigo-900">
      <div className="px-5 py-4 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between gap-4">
        <div>
          <h3 className="font-bold text-slate-900 dark:text-slate-100">
            Часы по проектам
          </h3>

          <p className="text-xs text-slate-500 mt-1 capitalize">
            {monthLabel}
          </p>
        </div>

        <div className="text-right">
          <div className="text-xs text-slate-500">Всего</div>
          <div className="text-xl font-black text-indigo-700 dark:text-indigo-300">
            {formatHoursDetailed(totalHours)}
          </div>
        </div>
      </div>

      <div className="p-4 space-y-4">
        {byUser.map(userGroup => (
          <section
            key={userGroup.userId}
            className="rounded-2xl border border-slate-200 dark:border-slate-700 overflow-hidden bg-white dark:bg-slate-900"
          >
            <div className="px-4 py-3 flex items-center justify-between gap-4 bg-slate-50 dark:bg-slate-800">
              <div className="flex items-center gap-3 min-w-0">
                <div
                  className="w-9 h-9 rounded-xl flex items-center justify-center text-white font-bold"
                  style={{
                    backgroundColor: getUserColor(
                      userGroup.userId,
                    ),
                  }}
                >
                  {(userGroup.user?.name || '?')
                    .split(/\s+/)
                    .map(part => part[0])
                    .join('')
                    .slice(0, 2)
                    .toUpperCase()}
                </div>

                <div className="min-w-0">
                  <div className="font-bold truncate">
                    {shortName(
                      userGroup.user?.name || 'Неизвестный',
                    )}
                  </div>

                  <div className="text-xs text-slate-500">
                    {userGroup.days} раб. дн. ·{' '}
                    {userGroup.projectGroups.length} проектов
                  </div>
                </div>
              </div>

              <div className="font-black text-indigo-700 dark:text-indigo-300 whitespace-nowrap">
                {formatHoursDetailed(userGroup.total)}
              </div>
            </div>

            <div className="p-3 space-y-2">
              {userGroup.projectGroups.map(projectGroup => (
                <details
                  key={projectGroup.projectId}
                  className="group rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden"
                >
                  <summary className="list-none cursor-pointer px-4 py-3 flex items-center justify-between gap-4 hover:bg-indigo-50/50 dark:hover:bg-indigo-950/20">
                    <div className="min-w-0">
                      <div className="font-semibold truncate">
                        {projectGroup.projectName}
                      </div>

                      <div className="text-xs text-slate-500">
                        {projectGroup.entries.length} записей
                      </div>
                    </div>

                    <div className="flex items-center gap-3">
                      <span className="font-black text-indigo-700 dark:text-indigo-300 whitespace-nowrap">
                        {formatHoursDetailed(projectGroup.total)}
                      </span>

                      <span className="text-slate-400 group-open:rotate-180 transition-transform">
                        ⌄
                      </span>
                    </div>
                  </summary>

                  <div className="border-t border-slate-200 dark:border-slate-700 overflow-x-auto">
                    <table className="w-full min-w-[620px] text-sm">
                      <thead className="bg-slate-50 dark:bg-slate-800">
                        <tr>
                          <th className="text-left px-4 py-2">
                            Дата
                          </th>
                          <th className="text-left px-4 py-2">
                            Комментарий
                          </th>
                          <th className="text-right px-4 py-2">
                            Часы
                          </th>
                        </tr>
                      </thead>

                      <tbody>
                        {projectGroup.entries.map(entry => (
                          <tr
                            key={entry.id}
                            className="border-t border-slate-100 dark:border-slate-800"
                          >
                            <td className="px-4 py-2 whitespace-nowrap">
                              {new Date(
                                `${entry.date}T00:00:00`,
                              ).toLocaleDateString('ru-RU')}
                            </td>

                            <td className="px-4 py-2 text-slate-500">
                              {entry.comment || '—'}
                            </td>

                            <td className="px-4 py-2 text-right font-black text-indigo-700 dark:text-indigo-300">
                              {formatHoursDetailed(entry.hours)}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </details>
              ))}
            </div>
          </section>
        ))}

        {byUser.length === 0 && (
          <div className="py-8 text-center text-slate-500">
            За выбранный период записи не найдены.
          </div>
        )}
      </div>
    </div>
  );
}

