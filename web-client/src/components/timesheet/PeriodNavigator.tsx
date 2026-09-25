interface PeriodNavigatorProps {
  label: string;
  view: 'calendar' | 'list';
  onPrevious: () => void;
  onNext: () => void;
  onToday: () => void;
  onViewChange: (view: 'calendar' | 'list') => void;
}

export function PeriodNavigator({
  label,
  view,
  onPrevious,
  onNext,
  onToday,
  onViewChange,
}: PeriodNavigatorProps) {
  return (
    <div className="flex flex-col gap-3 rounded-2xl border border-slate-200 bg-white p-3 shadow-sm dark:border-slate-800 dark:bg-slate-900 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={onPrevious}
          className="flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 text-slate-600 transition-colors hover:bg-slate-50 hover:text-slate-950 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white"
          aria-label="Предыдущий период"
        >
          ‹
        </button>

        <button
          type="button"
          onClick={onToday}
          className="h-10 rounded-xl border border-slate-200 px-4 text-sm font-semibold text-slate-700 transition-colors hover:bg-slate-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
        >
          Сегодня
        </button>

        <button
          type="button"
          onClick={onNext}
          className="flex h-10 w-10 items-center justify-center rounded-xl border border-slate-200 text-slate-600 transition-colors hover:bg-slate-50 hover:text-slate-950 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white"
          aria-label="Следующий период"
        >
          ›
        </button>

        <h2 className="ml-1 truncate text-base font-bold capitalize text-slate-950 dark:text-white sm:ml-3 sm:text-lg">
          {label}
        </h2>
      </div>

      <div
        className="grid grid-cols-2 rounded-xl bg-slate-100 p-1 dark:bg-slate-800"
        role="group"
        aria-label="Вид табеля"
      >
        <button
          type="button"
          onClick={() => onViewChange('calendar')}
          aria-pressed={view === 'calendar'}
          className={[
            'rounded-lg px-4 py-2 text-sm font-semibold transition-all',
            view === 'calendar'
              ? 'bg-white text-slate-950 shadow-sm dark:bg-slate-700 dark:text-white'
              : 'text-slate-500 hover:text-slate-950 dark:text-slate-400 dark:hover:text-white',
          ].join(' ')}
        >
          Календарь
        </button>

        <button
          type="button"
          onClick={() => onViewChange('list')}
          aria-pressed={view === 'list'}
          className={[
            'rounded-lg px-4 py-2 text-sm font-semibold transition-all',
            view === 'list'
              ? 'bg-white text-slate-950 shadow-sm dark:bg-slate-700 dark:text-white'
              : 'text-slate-500 hover:text-slate-950 dark:text-slate-400 dark:hover:text-white',
          ].join(' ')}
        >
          Список
        </button>
      </div>
    </div>
  );
}