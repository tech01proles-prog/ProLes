import type { ReactNode } from 'react';

export type StatusBadgeTone =
  | 'neutral'
  | 'success'
  | 'warning'
  | 'danger'
  | 'info'
  | 'violet';

interface StatusBadgeProps {
  children: ReactNode;
  tone?: StatusBadgeTone;
  dot?: boolean;
  className?: string;
}

const TONE_CLASSES: Record<StatusBadgeTone, string> = {
  neutral:
    'bg-slate-100 text-slate-700 ring-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:ring-slate-700',
  success:
    'bg-emerald-50 text-emerald-700 ring-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-300 dark:ring-emerald-900',
  warning:
    'bg-amber-50 text-amber-700 ring-amber-200 dark:bg-amber-950/40 dark:text-amber-300 dark:ring-amber-900',
  danger:
    'bg-rose-50 text-rose-700 ring-rose-200 dark:bg-rose-950/40 dark:text-rose-300 dark:ring-rose-900',
  info:
    'bg-sky-50 text-sky-700 ring-sky-200 dark:bg-sky-950/40 dark:text-sky-300 dark:ring-sky-900',
  violet:
    'bg-violet-50 text-violet-700 ring-violet-200 dark:bg-violet-950/40 dark:text-violet-300 dark:ring-violet-900',
};

const DOT_CLASSES: Record<StatusBadgeTone, string> = {
  neutral: 'bg-slate-400',
  success: 'bg-emerald-500',
  warning: 'bg-amber-500',
  danger: 'bg-rose-500',
  info: 'bg-sky-500',
  violet: 'bg-violet-500',
};

export function StatusBadge({
  children,
  tone = 'neutral',
  dot = false,
  className = '',
}: StatusBadgeProps) {
  return (
    <span
      className={[
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1',
        'text-xs font-semibold ring-1 ring-inset',
        TONE_CLASSES[tone],
        className,
      ].join(' ')}
    >
      {dot && (
        <span
          className={['h-1.5 w-1.5 rounded-full', DOT_CLASSES[tone]].join(' ')}
          aria-hidden="true"
        />
      )}

      {children}
    </span>
  );
}