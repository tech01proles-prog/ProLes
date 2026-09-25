import type { SubprojectDto } from '../../types';
import {
  EmptyState,
  StatusBadge,
} from '../ui';

interface SubprojectListProps {
  items: SubprojectDto[];
  canManage?: boolean;
  busyId?: string | null;
  onCreate?: () => void;
  onEdit?: (item: SubprojectDto) => void;
  onToggleActive?: (item: SubprojectDto) => void;
}

function formatTimestamp(value: number) {
  if (!value) return 'Дата не указана';

  return new Intl.DateTimeFormat('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(new Date(value));
}

export function SubprojectList({
  items,
  canManage = false,
  busyId = null,
  onCreate,
  onEdit,
  onToggleActive,
}: SubprojectListProps) {
  if (items.length === 0) {
    return (
      <EmptyState
        compact
        title="Подпроектов пока нет"
        description="Разделите проект на этапы или направления работ."
        action={
          canManage && onCreate ? (
            <button
              type="button"
              onClick={onCreate}
              className="rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700"
            >
              Создать подпроект
            </button>
          ) : undefined
        }
      />
    );
  }

  return (
    <div className="grid gap-4 md:grid-cols-2">
      {items.map((item) => {
        const busy = busyId === item.id;

        return (
          <article
            key={item.id}
            className={[
              'rounded-2xl border p-5 transition-colors',
              item.isActive
                ? 'border-slate-200 bg-white dark:border-slate-700 dark:bg-slate-900'
                : 'border-slate-200 bg-slate-50 opacity-75 dark:border-slate-800 dark:bg-slate-950/40',
            ].join(' ')}
          >
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <h3 className="truncate text-base font-bold text-slate-950 dark:text-white">
                    {item.name}
                  </h3>

                  <StatusBadge
                    tone={item.isActive ? 'success' : 'neutral'}
                    dot
                  >
                    {item.isActive ? 'Активен' : 'Архив'}
                  </StatusBadge>
                </div>

                <p className="mt-1 text-xs font-semibold uppercase tracking-wide text-slate-400">
                  {item.code || 'Без кода'}
                </p>
              </div>

              <span className="rounded-lg bg-slate-100 px-2 py-1 text-xs font-bold text-slate-500 dark:bg-slate-800 dark:text-slate-300">
                {item.sortOrder}
              </span>
            </div>

            <p className="mt-4 min-h-10 text-sm leading-5 text-slate-500 dark:text-slate-400">
              {item.description || 'Описание не заполнено.'}
            </p>

            <div className="mt-4 border-t border-slate-200 pt-3 text-xs text-slate-400 dark:border-slate-800">
              Обновлён {formatTimestamp(item.updatedAt)}
            </div>

            {canManage && (
              <div className="mt-4 flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={() => onEdit?.(item)}
                  disabled={busy}
                  className="rounded-lg border border-slate-200 px-3 py-2 text-xs font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
                >
                  Изменить
                </button>

                <button
                  type="button"
                  onClick={() => onToggleActive?.(item)}
                  disabled={busy}
                  className={[
                    'rounded-lg px-3 py-2 text-xs font-semibold disabled:opacity-50',
                    item.isActive
                      ? 'bg-rose-50 text-rose-600 hover:bg-rose-100 dark:bg-rose-950/30 dark:text-rose-400'
                      : 'bg-emerald-50 text-emerald-700 hover:bg-emerald-100 dark:bg-emerald-950/30 dark:text-emerald-300',
                  ].join(' ')}
                >
                  {busy
                    ? 'Сохранение…'
                    : item.isActive
                      ? 'В архив'
                      : 'Восстановить'}
                </button>
              </div>
            )}
          </article>
        );
      })}
    </div>
  );
}