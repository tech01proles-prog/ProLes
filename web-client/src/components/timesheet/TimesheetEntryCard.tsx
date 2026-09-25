import { StatusBadge } from '../ui';

export interface TimesheetEntryCardModel {
  id: string | number;
  date: string;
  weekday?: string;
  projectName: string;
  taskName?: string;
  description?: string;
  hours: number;
  approved?: boolean;
}

interface TimesheetEntryCardProps {
  entry: TimesheetEntryCardModel;
  onEdit?: (entry: TimesheetEntryCardModel) => void;
  onDelete?: (entry: TimesheetEntryCardModel) => void;
}

function formatHours(value: number) {
  return new Intl.NumberFormat('ru-RU', {
    maximumFractionDigits: 1,
  }).format(value);
}

export function TimesheetEntryCard({
  entry,
  onEdit,
  onDelete,
}: TimesheetEntryCardProps) {
  return (
    <article className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm transition-shadow hover:shadow-md dark:border-slate-800 dark:bg-slate-900">
      <div className="flex items-start gap-3">
        <div className="flex w-12 shrink-0 flex-col items-center rounded-xl bg-slate-100 px-2 py-2 dark:bg-slate-800">
          <span className="text-lg font-bold leading-none text-slate-950 dark:text-white">
            {entry.date}
          </span>

          {entry.weekday && (
            <span className="mt-1 text-[10px] font-bold uppercase text-slate-400">
              {entry.weekday}
            </span>
          )}
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <h3 className="truncate text-sm font-bold text-slate-950 dark:text-white">
                {entry.projectName}
              </h3>

              {entry.taskName && (
                <p className="mt-0.5 truncate text-xs text-slate-500 dark:text-slate-400">
                  {entry.taskName}
                </p>
              )}
            </div>

            <div className="shrink-0 rounded-xl bg-indigo-50 px-3 py-2 text-sm font-bold tabular-nums text-indigo-700 dark:bg-indigo-950/50 dark:text-indigo-300">
              {formatHours(entry.hours)} ч
            </div>
          </div>

          {entry.description && (
            <p className="mt-3 line-clamp-2 text-sm leading-5 text-slate-600 dark:text-slate-300">
              {entry.description}
            </p>
          )}

          <div className="mt-4 flex items-center justify-between gap-3">
            <StatusBadge
              tone={entry.approved ? 'success' : 'warning'}
              dot
            >
              {entry.approved ? 'Подтверждено' : 'Черновик'}
            </StatusBadge>

            <div className="flex items-center gap-1">
              {onEdit && (
                <button
                  type="button"
                  onClick={() => onEdit(entry)}
                  className="rounded-lg px-3 py-1.5 text-xs font-semibold text-slate-600 transition-colors hover:bg-slate-100 hover:text-slate-950 dark:text-slate-300 dark:hover:bg-slate-800 dark:hover:text-white"
                >
                  Изменить
                </button>
              )}

              {onDelete && (
                <button
                  type="button"
                  onClick={() => onDelete(entry)}
                  className="rounded-lg px-3 py-1.5 text-xs font-semibold text-rose-600 transition-colors hover:bg-rose-50 dark:text-rose-400 dark:hover:bg-rose-950/30"
                >
                  Удалить
                </button>
              )}
            </div>
          </div>
        </div>
      </div>
    </article>
  );
}