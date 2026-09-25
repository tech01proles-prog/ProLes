import { EmptyState, StatusBadge } from '../ui';

export interface ProjectActivityItem {
  id: string;
  title: string;
  description?: string;
  meta: string;
  value: string;
  badge?: string;
  tone?: 'indigo' | 'emerald' | 'rose';
}

interface ProjectActivityListProps {
  items: ProjectActivityItem[];
  emptyTitle: string;
  emptyDescription: string;
}

const DOT_CLASSES = {
  indigo: 'bg-indigo-500',
  emerald: 'bg-emerald-500',
  rose: 'bg-rose-500',
};

export function ProjectActivityList({
  items,
  emptyTitle,
  emptyDescription,
}: ProjectActivityListProps) {
  if (items.length === 0) {
    return (
      <EmptyState
        compact
        title={emptyTitle}
        description={emptyDescription}
      />
    );
  }

  return (
    <div className="divide-y divide-slate-200 dark:divide-slate-800">
      {items.map((item) => (
        <article
          key={item.id}
          className="flex items-start gap-3 py-4 first:pt-0 last:pb-0"
        >
          <span
            className={[
              'mt-2 h-2.5 w-2.5 shrink-0 rounded-full',
              DOT_CLASSES[item.tone ?? 'indigo'],
            ].join(' ')}
            aria-hidden="true"
          />

          <div className="min-w-0 flex-1">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <h3 className="truncate text-sm font-bold text-slate-950 dark:text-white">
                  {item.title}
                </h3>

                {item.description && (
                  <p className="mt-1 line-clamp-2 text-sm text-slate-500 dark:text-slate-400">
                    {item.description}
                  </p>
                )}
              </div>

              <span className="shrink-0 text-sm font-bold tabular-nums text-slate-950 dark:text-white">
                {item.value}
              </span>
            </div>

            <div className="mt-2 flex flex-wrap items-center gap-2">
              <span className="text-xs font-medium text-slate-400">
                {item.meta}
              </span>

              {item.badge && (
                <StatusBadge
                  tone={
                    item.tone === 'emerald'
                      ? 'success'
                      : item.tone === 'rose'
                        ? 'danger'
                        : 'info'
                  }
                >
                  {item.badge}
                </StatusBadge>
              )}
            </div>
          </div>
        </article>
      ))}
    </div>
  );
}