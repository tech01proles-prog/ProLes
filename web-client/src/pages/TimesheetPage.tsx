import {
  useCallback,
  useEffect,
  useMemo,
  useState,
} from 'react';
import api from '../api/client';
import type {
  DayOffDto,
  ProjectDto,
  TimeEntryDto,
  UserDto,
} from '../types';
import { generateUUID, isWeekend } from '../lib/utils';
import {
  PeriodNavigator,
  TimesheetCalendar,
  TimesheetEntryCard,
  TimesheetEntryForm,
  TimesheetSummary,
  type TimesheetCalendarDay,
  type TimesheetEntryCardModel,
  type TimesheetEntryFormValue,
} from '../components/timesheet';
import {
  EmptyState,
  Modal,
  PageSection,
  StatusBadge,
} from '../components/ui';

import { PersonalTimesheetPage } from '../components/timesheet/PersonalTimesheetPage';


const PERSONAL_TIMESHEET_LOGIN = 'a.ermashkevich';

const COUNTRIES = [
  { code: 'RF', label: 'Россия' },
  { code: 'BY', label: 'Беларусь' },
] as const;

const PROJECT_COLORS = [
  '#3b82f6',
  '#10b981',
  '#8b5cf6',
  '#f97316',
  '#06b6d4',
  '#ec4899',
  '#14b8a6',
  '#6366f1',
];

type TimesheetView = 'calendar' | 'list';

interface CalendarSourceDay {
  date: string;
  day: number;
  currentMonth: boolean;
  weekend: boolean;
}

interface TimeEntryPayload {
  projectId: string;
  date: string;
  hours: number;
  country: string;
  comment: string;
}

function formatDateKey(date: Date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');

  return `${year}-${month}-${day}`;
}

function parseDate(date: string) {
  return new Date(`${date}T00:00:00`);
}

function getMonthPrefix(year: number, month: number) {
  return `${year}-${String(month + 1).padStart(2, '0')}`;
}

function getCalendarDays(
  year: number,
  month: number,
): CalendarSourceDay[] {
  const firstDay = new Date(year, month, 1);
  const lastDay = new Date(year, month + 1, 0);

  const mondayIndex = (firstDay.getDay() + 6) % 7;
  const gridStart = new Date(year, month, 1 - mondayIndex);

  const trailingDays =
    (7 - ((mondayIndex + lastDay.getDate()) % 7)) % 7;
  const totalCells = mondayIndex + lastDay.getDate() + trailingDays;

  return Array.from({ length: totalCells }, (_, index) => {
    const date = new Date(gridStart);
    date.setDate(gridStart.getDate() + index);

    const dayOfWeek = date.getDay();

    return {
      date: formatDateKey(date),
      day: date.getDate(),
      currentMonth: date.getMonth() === month,
      weekend: dayOfWeek === 0 || dayOfWeek === 6,
    };
  });
}

function getProjectColor(projectId: string) {
  let hash = 0;

  for (let index = 0; index < projectId.length; index += 1) {
    hash =
      projectId.charCodeAt(index) +
      ((hash << 5) - hash);
  }

  return PROJECT_COLORS[
    Math.abs(hash) % PROJECT_COLORS.length
  ];
}

function formatHours(value: number) {
  const hours = Math.floor(value);
  const minutes = Math.round((value - hours) * 60);

  return minutes > 0
    ? `${hours} ч ${minutes} мин`
    : `${hours} ч`;
}

function getStoredUser(): UserDto | null {
  const stored = localStorage.getItem('proles_user');

  if (!stored) return null;

  try {
    return JSON.parse(stored) as UserDto;
  } catch {
    localStorage.removeItem('proles_user');
    return null;
  }
}

function getEntryComment(entry: TimeEntryDto) {
  const compatibleEntry = entry as TimeEntryDto & {
    description?: string;
  };

  return compatibleEntry.comment || compatibleEntry.description || '';
}

function getEntryCountry(entry: TimeEntryDto) {
  return entry.country || 'RF';
}

function getProjectName(
  entry: TimeEntryDto,
  projects: ProjectDto[],
) {
  return (
    entry.projectName ||
    projects.find((project) => project.id === entry.projectId)
      ?.name ||
    'Без проекта'
  );
}

function StandardTimesheetPage({
  user,
}: {
  user: UserDto;
}) {
  const now = useMemo(() => new Date(), []);

  const [entries, setEntries] = useState<TimeEntryDto[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [dayOffs, setDayOffs] = useState<DayOffDto[]>([]);

  const [loading, setLoading] = useState(true);
  const [savingEntry, setSavingEntry] = useState(false);
  const [error, setError] = useState('');

  const [viewYear, setViewYear] = useState(now.getFullYear());
  const [viewMonth, setViewMonth] = useState(now.getMonth());
  const [view, setView] = useState<TimesheetView>('calendar');

  const [selectedDate, setSelectedDate] = useState(
    formatDateKey(now),
  );
  const [entryModalOpen, setEntryModalOpen] = useState(false);
  const [editingEntry, setEditingEntry] =
    useState<TimeEntryDto | null>(null);
  const [formCountry, setFormCountry] = useState('RF');

  const loadData = useCallback(async () => {
    setLoading(true);
    setError('');

    try {
      const [entriesResult, projectsResult, dayOffsResult] =
        await Promise.allSettled([
          api.get<TimeEntryDto[]>('/entries', {
            params: { userId: user.id },
          }),
          api.get<ProjectDto[]>('/projects'),
          api.get<DayOffDto[]>('/dayoffs', {
            params: { userId: user.id },
          }),
        ]);

      if (entriesResult.status === 'fulfilled') {
        setEntries(entriesResult.value.data);
      } else {
        setEntries([]);
      }

      if (projectsResult.status === 'fulfilled') {
        setProjects(
          projectsResult.value.data.filter(
            (project) => project.isActive,
          ),
        );
      } else {
        setProjects([]);
      }

      if (dayOffsResult.status === 'fulfilled') {
        setDayOffs(dayOffsResult.value.data);
      } else {
        setDayOffs([]);
      }

      if (
        entriesResult.status === 'rejected' &&
        projectsResult.status === 'rejected' &&
        dayOffsResult.status === 'rejected'
      ) {
        setError('Не удалось загрузить данные табеля.');
      }
    } catch (requestError) {
      console.error(requestError);
      setError('Не удалось загрузить данные табеля.');
    } finally {
      setLoading(false);
    }
  }, [user.id]);

  useEffect(() => {
    void loadData();
  }, [loadData]);

  const monthPrefix = getMonthPrefix(viewYear, viewMonth);

  const periodEntries = useMemo(
    () =>
      entries
        .filter((entry) =>
          entry.date.startsWith(monthPrefix),
        )
        .sort((left, right) =>
          right.date.localeCompare(left.date),
        ),
    [entries, monthPrefix],
  );

  const selectedDayEntries = useMemo(
    () =>
      entries.filter(
        (entry) => entry.date === selectedDate,
      ),
    [entries, selectedDate],
  );

  const selectedDayTotal = selectedDayEntries.reduce(
    (sum, entry) => sum + Number(entry.hours || 0),
    0,
  );

  const selectedIsDayOff = dayOffs.some(
    (dayOff) => dayOff.date === selectedDate,
  );

  const selectedIsWeekend = isWeekend(selectedDate);

  const totalHours = periodEntries.reduce(
    (sum, entry) => sum + Number(entry.hours || 0),
    0,
  );

  const activeDays = new Set(
    periodEntries.map((entry) => entry.date),
  ).size;

  const activeProjects = new Set(
    periodEntries.map((entry) => entry.projectId),
  ).size;

  const plannedHours = useMemo(() => {
    const daysInMonth = new Date(
      viewYear,
      viewMonth + 1,
      0,
    ).getDate();

    let workingDays = 0;

    for (let day = 1; day <= daysInMonth; day += 1) {
      const date = new Date(viewYear, viewMonth, day);
      const dateKey = formatDateKey(date);
      const dayOfWeek = date.getDay();
      const weekend = dayOfWeek === 0 || dayOfWeek === 6;
      const dayOff = dayOffs.some(
        (item) => item.date === dateKey,
      );

      if (!weekend && !dayOff) {
        workingDays += 1;
      }
    }

    return workingDays * 8;
  }, [dayOffs, viewMonth, viewYear]);

  const calendarDays = useMemo<TimesheetCalendarDay[]>(
    () =>
      getCalendarDays(viewYear, viewMonth).map((day) => ({
        key: day.date,
        dayNumber: day.day,
        dateLabel: parseDate(day.date).toLocaleDateString(
          'ru-RU',
          {
            weekday: 'long',
            day: 'numeric',
            month: 'long',
          },
        ),
        currentMonth: day.currentMonth,
        today: day.date === formatDateKey(now),
        weekend: day.weekend,
        entries: entries
          .filter((entry) => entry.date === day.date)
          .map((entry) => ({
            id: entry.id,
            projectName: getProjectName(entry, projects),
            hours: Number(entry.hours || 0),
            color: getProjectColor(entry.projectId),
          })),
      })),
    [entries, now, projects, viewMonth, viewYear],
  );

  const entryCards = useMemo<TimesheetEntryCardModel[]>(
    () =>
      periodEntries.map((entry) => {
        const date = parseDate(entry.date);

        return {
          id: entry.id,
          date: String(date.getDate()).padStart(2, '0'),
          weekday: date
            .toLocaleDateString('ru-RU', {
              weekday: 'short',
            })
            .replace('.', ''),
          projectName: getProjectName(entry, projects),
          description: getEntryComment(entry),
          hours: Number(entry.hours || 0),
          approved: Boolean(entry.synced),
        };
      }),
    [periodEntries, projects],
  );

  const selectedDateLabel = parseDate(
    selectedDate,
  ).toLocaleDateString('ru-RU', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });

  const monthLabel = new Date(
    viewYear,
    viewMonth,
    1,
  ).toLocaleDateString('ru-RU', {
    month: 'long',
    year: 'numeric',
  });

  const showPreviousMonth = () => {
    if (viewMonth === 0) {
      setViewMonth(11);
      setViewYear((current) => current - 1);
      return;
    }

    setViewMonth((current) => current - 1);
  };

  const showNextMonth = () => {
    if (viewMonth === 11) {
      setViewMonth(0);
      setViewYear((current) => current + 1);
      return;
    }

    setViewMonth((current) => current + 1);
  };

  const showCurrentMonth = () => {
    const currentDate = new Date();

    setViewYear(currentDate.getFullYear());
    setViewMonth(currentDate.getMonth());
    setSelectedDate(formatDateKey(currentDate));
  };

  const openCreateEntry = (date = selectedDate) => {
    setEditingEntry(null);
    setSelectedDate(date);
    setFormCountry('RF');
    setEntryModalOpen(true);
  };

  const openEditEntry = (
    card: TimesheetEntryCardModel,
  ) => {
    const source = entries.find(
      (entry) => entry.id === card.id,
    );

    if (!source) return;

    setEditingEntry(source);
    setSelectedDate(source.date);
    setFormCountry(getEntryCountry(source));
    setEntryModalOpen(true);
  };

  const closeEntryModal = () => {
    if (savingEntry) return;

    setEntryModalOpen(false);
    setEditingEntry(null);
    setFormCountry('RF');
  };

  const createEntry = async (
    payload: TimeEntryPayload,
  ) => {
    const project = projects.find(
      (item) => item.id === payload.projectId,
    );

    await api.post('/entries', {
      id: generateUUID(),
      userId: user.id,
      projectId: payload.projectId,
      projectName: project?.name || '',
      date: payload.date,
      hours: payload.hours,
      country: payload.country,
      comment: payload.comment,
      synced: false,
    });
  };

  const updateEntry = async (
    entryId: string,
    payload: TimeEntryPayload,
  ) => {
    const project = projects.find(
      (item) => item.id === payload.projectId,
    );

    await api.put('/entries', {
      id: entryId,
      userId: user.id,
      projectId: payload.projectId,
      projectName: project?.name || '',
      date: payload.date,
      hours: payload.hours,
      country: payload.country,
      comment: payload.comment,
      synced: false,
    });
  };

  const saveEntry = async (
    value: TimesheetEntryFormValue,
  ) => {
    setSavingEntry(true);
    setError('');

    try {
      const payload: TimeEntryPayload = {
        projectId: value.projectId,
        date: value.date,
        hours: Number(value.hours),
        country: formCountry,
        comment: value.description,
      };

      if (editingEntry) {
        await updateEntry(editingEntry.id, payload);
      } else {
        await createEntry(payload);
      }

      setEntryModalOpen(false);
      setEditingEntry(null);
      setFormCountry('RF');

      await loadData();
    } catch (requestError) {
      console.error(requestError);
      setError('Не удалось сохранить запись.');
    } finally {
      setSavingEntry(false);
    }
  };

  const deleteEntry = async (
    entryId: string | number,
  ) => {
    if (!window.confirm('Удалить запись рабочего времени?')) {
      return;
    }

    setError('');

    try {
      await api.delete('/entries', {
        params: { entryId },
      });

      await loadData();
    } catch (requestError) {
      console.error(requestError);
      setError('Не удалось удалить запись.');
    }
  };

  const addDayOff = async () => {
    setError('');

    try {
      await api.post('/dayoffs', {
        user_id: user.id,
        date: selectedDate,
      });

      await loadData();
    } catch (requestError) {
      console.error(requestError);
      setError('Не удалось добавить выходной.');
    }
  };

  const removeDayOff = async () => {
    if (!window.confirm('Убрать выбранный выходной?')) {
      return;
    }

    setError('');

    try {
      await api.delete('/dayoffs', {
        params: {
          userId: user.id,
          date: selectedDate,
        },
      });

      await loadData();
    } catch (requestError) {
      console.error(requestError);
      setError('Не удалось убрать выходной.');
    }
  };

  if (loading && entries.length === 0) {
    return (
      <div className="flex min-h-80 items-center justify-center">
        <div
          className="h-10 w-10 animate-spin rounded-full border-4 border-indigo-100 border-t-indigo-600 dark:border-slate-800 dark:border-t-indigo-400"
          aria-label="Загрузка табеля"
        />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <section className="relative overflow-hidden rounded-3xl bg-slate-950 p-6 text-white shadow-xl md:p-8 dark:bg-slate-900">
        <div className="pointer-events-none absolute -right-20 -top-24 h-72 w-72 rounded-full bg-indigo-500/30 blur-3xl" />

        <div className="relative flex flex-col gap-6 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <StatusBadge tone="violet" dot>
              Выбранный день
            </StatusBadge>

            <h2 className="mt-4 text-2xl font-bold capitalize tracking-tight md:text-3xl">
              {selectedDateLabel}
            </h2>

            <div className="mt-3 flex flex-wrap items-center gap-2 text-sm text-slate-300">
              <span>
                Учтено: {formatHours(selectedDayTotal)}
              </span>

              {selectedIsDayOff && (
                <span className="rounded-full bg-rose-500/15 px-2.5 py-1 text-rose-200">
                  Выходной
                </span>
              )}

              {selectedIsWeekend && (
                <span className="rounded-full bg-white/10 px-2.5 py-1">
                  Календарный выходной
                </span>
              )}
            </div>
          </div>

          <div className="flex flex-wrap gap-2">
            {selectedIsDayOff && selectedDayTotal === 0 && (
              <button
                type="button"
                onClick={() => void removeDayOff()}
                className="rounded-xl border border-rose-300/30 bg-rose-500/10 px-4 py-2.5 text-sm font-semibold text-rose-100 transition-colors hover:bg-rose-500/20"
              >
                Убрать выходной
              </button>
            )}

            {!selectedIsDayOff &&
              !selectedIsWeekend &&
              selectedDayEntries.length === 0 && (
                <button
                  type="button"
                  onClick={() => void addDayOff()}
                  className="rounded-xl border border-white/15 bg-white/5 px-4 py-2.5 text-sm font-semibold transition-colors hover:bg-white/10"
                >
                  Отметить выходным
                </button>
              )}

            <button
              type="button"
              onClick={() => openCreateEntry()}
              className="rounded-xl bg-white px-5 py-2.5 text-sm font-semibold text-slate-950 shadow-lg transition-transform hover:-translate-y-0.5"
            >
              Добавить часы
            </button>
          </div>
        </div>
      </section>

      {error && (
        <div
          role="alert"
          className="rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-medium text-rose-700 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-300"
        >
          {error}
        </div>
      )}

      <TimesheetSummary
        totalHours={totalHours}
        plannedHours={plannedHours}
        workDays={activeDays}
        projectCount={activeProjects}
      />

      <PeriodNavigator
        label={monthLabel}
        view={view}
        onPrevious={showPreviousMonth}
        onNext={showNextMonth}
        onToday={showCurrentMonth}
        onViewChange={setView}
      />

      {view === 'calendar' ? (
        <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
          <div>
            <TimesheetCalendar
              days={calendarDays}
              onSelectDay={(day) => {
                setSelectedDate(day.key);
              }}
              onSelectEntry={(calendarEntry) => {
                const card = entryCards.find(
                  (entry) => entry.id === calendarEntry.id,
                );

                if (card) {
                  openEditEntry(card);
                }
              }}
            />

            <div className="space-y-3 md:hidden">
              {selectedDayEntries.length === 0 ? (
                <PageSection>
                  <EmptyState
                    compact
                    title="За выбранный день записей нет"
                    description="Добавьте часы или выберите другой день."
                    action={
                      <button
                        type="button"
                        onClick={() => openCreateEntry()}
                        className="rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700"
                      >
                        Добавить часы
                      </button>
                    }
                  />
                </PageSection>
              ) : (
                selectedDayEntries.map((entry) => {
                  const card = entryCards.find(
                    (item) => item.id === entry.id,
                  );

                  return card ? (
                    <TimesheetEntryCard
                      key={card.id}
                      entry={card}
                      onEdit={openEditEntry}
                      onDelete={(item) =>
                        void deleteEntry(item.id)
                      }
                    />
                  ) : null;
                })
              )}
            </div>
          </div>

          <PageSection
            title="Записи выбранного дня"
            description={selectedDateLabel}
            action={
              <StatusBadge
                tone={
                  selectedDayTotal >= 8
                    ? 'success'
                    : 'warning'
                }
                dot
              >
                {formatHours(selectedDayTotal)}
              </StatusBadge>
            }
            contentClassName="space-y-3"
          >
            {selectedDayEntries.length === 0 ? (
              <EmptyState
                compact
                title="Записей пока нет"
                description="Для выбранной даты рабочее время не добавлено."
                action={
                  <button
                    type="button"
                    onClick={() => openCreateEntry()}
                    className="rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700"
                  >
                    Добавить запись
                  </button>
                }
              />
            ) : (
              selectedDayEntries.map((entry) => {
                const card = entryCards.find(
                  (item) => item.id === entry.id,
                );

                return card ? (
                  <TimesheetEntryCard
                    key={card.id}
                    entry={card}
                    onEdit={openEditEntry}
                    onDelete={(item) =>
                      void deleteEntry(item.id)
                    }
                  />
                ) : null;
              })
            )}
          </PageSection>
        </div>
      ) : (
        <PageSection
          title="Записи рабочего времени"
          description={`Все записи за ${monthLabel}`}
          action={
            <StatusBadge
              tone={
                totalHours >= plannedHours
                  ? 'success'
                  : 'warning'
              }
              dot
            >
              {totalHours >= plannedHours
                ? 'План выполнен'
                : 'Период не заполнен'}
            </StatusBadge>
          }
          contentClassName="space-y-3"
        >
          {entryCards.length === 0 ? (
            <EmptyState
              title="За выбранный период записей нет"
              description="Добавьте рабочие часы, чтобы сформировать табель."
              action={
                <button
                  type="button"
                  onClick={() => openCreateEntry()}
                  className="rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700"
                >
                  Добавить часы
                </button>
              }
            />
          ) : (
            entryCards.map((entry) => (
              <TimesheetEntryCard
                key={entry.id}
                entry={entry}
                onEdit={openEditEntry}
                onDelete={(item) =>
                  void deleteEntry(item.id)
                }
              />
            ))
          )}
        </PageSection>
      )}

      <Modal
        open={entryModalOpen}
        title={
          editingEntry
            ? 'Изменение записи'
            : 'Новая запись рабочего времени'
        }
        description="Укажите проект, дату и количество отработанных часов."
        onClose={closeEntryModal}
      >
        <TimesheetEntryForm
          projects={projects.map((project) => ({
            id: project.id,
            name: project.name,
          }))}
          initialValue={{
            projectId: editingEntry?.projectId || '',
            date: editingEntry?.date || selectedDate,
            hours:
              editingEntry?.hours !== undefined
                ? String(editingEntry.hours)
                : '',
            description: editingEntry
              ? getEntryComment(editingEntry)
              : '',
          }}
          submitting={savingEntry}
          submitLabel={
            editingEntry
              ? 'Сохранить изменения'
              : 'Добавить часы'
          }
          onCancel={closeEntryModal}
          onSubmit={saveEntry}
        />

        <div className="mt-5 border-t border-slate-200 pt-5 dark:border-slate-800">
          <label>
            <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
              Страна выполнения работы
            </span>

            <select
              value={formCountry}
              onChange={(event) =>
                setFormCountry(event.target.value)
              }
              disabled={savingEntry}
              className="mt-1.5 w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm text-slate-950 outline-none transition focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/10 dark:border-slate-700 dark:bg-slate-950 dark:text-white"
            >
              {COUNTRIES.map((country) => (
                <option
                  key={country.code}
                  value={country.code}
                >
                  {country.label}
                </option>
              ))}
            </select>
          </label>
        </div>
      </Modal>
    </div>
  );
}

function PersonalTimesheetPage({
  user,
}: {
  user: UserDto;
}) {
  return (
    <PageSection
      title="Персональный табель"
      description={`Пользователь: ${
        user.login || user.id
      }`}
    >
      <EmptyState
        title="Персональный режим временно сохранён отдельно"
        description="Расширенный персональный табель из прежней версии нужно вынести в отдельный компонент PersonalTimesheetPage.tsx. Стандартный табель уже полностью переведён на новый интерфейс."
      />
    </PageSection>
  );
}

export function TimesheetPage() {
  const [user] = useState<UserDto | null>(() =>
    getStoredUser(),
  );

  if (!user) {
    return (
      <PageSection>
        <EmptyState
          title="Пользователь не определён"
          description="В локальной сессии отсутствуют данные пользователя. Выполните повторный вход в систему."
        />
      </PageSection>
    );
  }

  if (user.login === PERSONAL_TIMESHEET_LOGIN) {
    return <PersonalTimesheetPage user={user} />;
  }

  return <StandardTimesheetPage user={user} />;
}

export default TimesheetPage;