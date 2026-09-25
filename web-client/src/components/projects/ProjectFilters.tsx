export type ProjectStatusFilter =
  | 'all'
  | 'new'
  | 'in_progress'
  | 'completed'
  | 'cancelled';

export type ProjectActivityFilter =
  | 'all'
  | 'active'
  | 'archived';

interface ProjectFiltersProps {
  search: string;
  status: ProjectStatusFilter;
  activity: ProjectActivityFilter;
  resultCount: number;
  onSearchChange: (value: string) => void;
  onStatusChange: (value: ProjectStatusFilter) => void;
  onActivityChange: (value: ProjectActivityFilter) => void;
  onReset: () => void;
}

const CONTROL_CLASS_NAME = [
  'h-11 rounded-xl border border-slate-200 bg-white px-3.5',
  'text-sm text-slate-700 outline-none transition',
  'focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/10',
  'dark:border-slate-700 dark:bg-slate-950 dark:text-slate-200',
].join(' ');

export function ProjectFilters({
  search,
  status,
  activity,
  resultCount,
  onSearchChange,
  onStatusChange,
  onActivityChange,
  onReset,
}: ProjectFiltersProps) {
  const hasFilters =
    search.trim() !== '' || status !== 'all' || activity !== 'all';

  return (
    <div className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <div className="grid gap-3 lg:grid-cols-[minmax(280px,1fr)_200px_180px_auto]">
        <label className="relative">
          <span className="sr-only">Поиск проектов</span>
          <span
            className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-slate-400"
            aria-hidden="true"
          >
            ⌕
          </span>
          <input
            type="search"
            value={search}
            onChange={(event) => onSearchChange(event.target.value)}
            placeholder="Название, клиент, договор или номер…"
            className={`${CONTROL_CLASS_NAME} w-full pl-10`}
          />
        </label>

        <select
          value={status}
          onChange={(event) =>
            onStatusChange(event.target.value as ProjectStatusFilter)
          }
          className={CONTROL_CLASS_NAME}
          aria-label="Фильтр по статусу"
        >
          <option value="all">Все статусы</option>
          <option value="new">Новые</option>
          <option value="in_progress">В работе</option>
          <option value="completed">Завершённые</option>
          <option value="cancelled">Отменённые</option>
        </select>

        <select
          value={activity}
          onChange={(event) =>
            onActivityChange(event.target.value as ProjectActivityFilter)
          }
          className={CONTROL_CLASS_NAME}
          aria-label="Фильтр по активности"
        >
          <option value="all">Все проекты</option>
          <option value="active">Активные</option>
          <option value="archived">Архивные</option>
        </select>

        <button
          type="button"
          onClick={onReset}
          disabled={!hasFilters}
          className="h-11 rounded-xl border border-slate-200 px-4 text-sm font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
        >
          Сбросить
        </button>
      </div>

      <div className="mt-3 text-xs font-medium text-slate-400">
        Найдено проектов: {resultCount}
      </div>
    </div>
  );
}