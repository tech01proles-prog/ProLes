export interface TimesheetCalendarEntry {
  id: string | number;
  projectName: string;
  hours: number;
  color?: string;
}

export interface TimesheetCalendarDay {
  key: string;
  dayNumber: number;
  dateLabel: string;
  currentMonth: boolean;
  today: boolean;
  weekend: boolean;
  entries: TimesheetCalendarEntry[];
}

interface TimesheetCalendarProps {
  days: TimesheetCalendarDay[];
  onSelectDay: (day: TimesheetCalendarDay) => void;
  onSelectEntry?: (
    entry: TimesheetCalendarEntry,
    day: TimesheetCalendarDay,
  ) => void;
}

const WEEK_DAYS = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];

function formatHours(value: number) {
  return new Intl.NumberFormat('ru-RU', {
    maximumFractionDigits: 1,
  }).format(value);
}

export function TimesheetCalendar({
  days,
  onSelectDay,
  onSelectEntry,
}: TimesheetCalendarProps) {
  return (
    <div className="hidden overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm dark:border-slate-800 dark:bg-slate-900 md:block">
      <div className="grid grid-cols-7 border-b border-slate-200 bg-slate-50 dark:border-slate-800 dark:bg-slate-900">
        {WEEK_DAYS.map((day, index) => (
          <div
            key={day}
            className={[
              'px-3 py-3 text-center text-[11px] font-bold uppercase tracking-[0.14em]',
              index >= 5
                ? 'text-rose-500 dark:text-rose-400'
                : 'text-slate-400',
            ].join(' ')}
          >
            {day}
          </div>
        ))}
      </div>

      <div className="grid grid-cols-7">
        {days.map((day) => {
          const totalHours = day.entries.reduce(
            (sum, entry) => sum + Number(entry.hours || 0),
            0,
          );

          return (
            <article
              key={day.key}
              className={[
                'group relative min-h-36 border-b border-r border-slate-200 p-2 transition-colors',
                'dark:border-slate-800',
                day.currentMonth
                  ? 'bg-white hover:bg-slate-50/80 dark:bg-slate-900 dark:hover:bg-slate-800/50'
                  : 'bg-slate-50/60 dark:bg-slate-950/30',
                day.today
                  ? 'ring-2 ring-inset ring-indigo-500'
                  : '',
              ].join(' ')}
            >
              <button
                type="button"
                onClick={() => onSelectDay(day)}
                className="flex w-full items-center justify-between rounded-lg p-1 text-left"
                aria-label={`Добавить часы: ${day.dateLabel}`}
              >
                <span
                  className={[
                    'flex h-7 w-7 items-center justify-center rounded-lg text-xs font-bold',
                    day.today
                      ? 'bg-indigo-600 text-white'
                      : day.currentMonth
                        ? day.weekend
                          ? 'text-rose-500 dark:text-rose-400'
                          : 'text-slate-700 dark:text-slate-200'
                        : 'text-slate-300 dark:text-slate-600',
                  ].join(' ')}
                >
                  {day.dayNumber}
                </span>

                {totalHours > 0 && (
                  <span
                    className={[
                      'rounded-full px-2 py-0.5 text-[10px] font-bold',
                      totalHours >= 8
                        ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/50 dark:text-emerald-300'
                        : 'bg-amber-50 text-amber-700 dark:bg-amber-950/50 dark:text-amber-300',
                    ].join(' ')}
                  >
                    {formatHours(totalHours)} ч
                  </span>
                )}
              </button>

              <div className="mt-1 space-y-1">
                {day.entries.slice(0, 3).map((entry) => (
                  <button
                    key={entry.id}
                    type="button"
                    onClick={() => onSelectEntry?.(entry, day)}
                    className="flex w-full items-center gap-2 rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-left shadow-sm transition-all hover:-translate-y-0.5 hover:border-indigo-200 hover:shadow dark:border-slate-700 dark:bg-slate-800 dark:hover:border-indigo-700"
                  >
                    <span
                      className="h-2 w-2 shrink-0 rounded-full"
                      style={{ backgroundColor: entry.color || '#6366f1' }}
                      aria-hidden="true"
                    />

                    <span className="min-w-0 flex-1 truncate text-[11px] font-medium text-slate-600 dark:text-slate-300">
                      {entry.projectName}
                    </span>

                    <span className="shrink-0 text-[11px] font-bold tabular-nums text-slate-950 dark:text-white">
                      {formatHours(entry.hours)}
                    </span>
                  </button>
                ))}

                {day.entries.length > 3 && (
                  <div className="px-2 pt-1 text-[10px] font-semibold text-slate-400">
                    Ещё {day.entries.length - 3}
                  </div>
                )}
              </div>

              <button
                type="button"
                onClick={() => onSelectDay(day)}
                className="absolute bottom-2 right-2 flex h-7 w-7 items-center justify-center rounded-lg bg-indigo-600 text-base text-white opacity-0 shadow-lg transition-opacity group-hover:opacity-100 focus:opacity-100"
                aria-label={`Добавить часы: ${day.dateLabel}`}
              >
                +
              </button>
            </article>
          );
        })}
      </div>
    </div>
  );
}