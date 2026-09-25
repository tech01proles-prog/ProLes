import type { ReactNode } from 'react';

interface PageSectionProps {
  title?: string;
  description?: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
  contentClassName?: string;
  noPadding?: boolean;
}

export function PageSection({
  title,
  description,
  action,
  children,
  className = '',
  contentClassName = '',
  noPadding = false,
}: PageSectionProps) {
  const hasHeader = Boolean(title || description || action);

  return (
    <section
      className={[
        'overflow-hidden rounded-2xl border border-slate-200',
        'bg-white shadow-sm shadow-slate-200/40',
        'dark:border-slate-800 dark:bg-slate-900 dark:shadow-none',
        className,
      ].join(' ')}
    >
      {hasHeader && (
        <header className="flex flex-col gap-4 border-b border-slate-200 px-5 py-4 sm:flex-row sm:items-center sm:justify-between dark:border-slate-800">
          <div className="min-w-0">
            {title && (
              <h2 className="text-base font-bold tracking-tight text-slate-950 dark:text-white">
                {title}
              </h2>
            )}

            {description && (
              <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                {description}
              </p>
            )}
          </div>

          {action && <div className="shrink-0">{action}</div>}
        </header>
      )}

      <div
        className={[
          noPadding ? '' : 'p-5',
          contentClassName,
        ].join(' ')}
      >
        {children}
      </div>
    </section>
  );
}