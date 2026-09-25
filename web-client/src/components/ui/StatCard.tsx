import type { ReactNode } from 'react';

type StatCardTone = 'slate' | 'indigo' | 'emerald' | 'amber' | 'rose';

interface StatCardProps {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  icon?: ReactNode;
  tone?: StatCardTone;
}

const ICON_CLASSES: Record<StatCardTone, string> = {
  slate:
    'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300',
  indigo:
    'bg-indigo-50 text-indigo-600 dark:bg-indigo-950/50 dark:text-indigo-300',
  emerald:
    'bg-emerald-50 text-emerald-600 dark:bg-emerald-950/50 dark:text-emerald-300',
  amber:
    'bg-amber-50 text-amber-600 dark:bg-amber-950/50 dark:text-amber-300',
  rose:
    'bg-rose-50 text-rose-600 dark:bg-rose-950/50 dark:text-rose-300',
};

export function StatCard({
  label,
  value,
  hint,
  icon,
  tone = 'slate',
}: StatCardProps) {
  return (
    <article className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm shadow-slate-200/40 dark:border-slate-800 dark:bg-slate-900 dark:shadow-none">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="truncate text-xs font-semibold uppercase tracking-[0.12em] text-slate-400">
            {label}
          </p>

          <div className="mt-2 text-2xl font-bold tracking-tight text-slate-950 dark:text-white">
            {value}
          </div>
        </div>

        {icon && (
          <div
            className={[
              'flex h-10 w-10 shrink-0 items-center justify-center rounded-xl',
              ICON_CLASSES[tone],
            ].join(' ')}
          >
            {icon}
          </div>
        )}
      </div>

      {hint && (
        <div className="mt-3 text-xs text-slate-500 dark:text-slate-400">
          {hint}
        </div>
      )}
    </article>
  );
}