export type ProjectDetailTab =
  | 'overview'
  | 'hours'
  | 'finance';

interface ProjectTabsProps {
  value: ProjectDetailTab;
  hoursCount: number;
  financeCount: number;
  onChange: (tab: ProjectDetailTab) => void;
}

const TABS: Array<{
  value: ProjectDetailTab;
  label: string;
}> = [
  { value: 'overview', label: 'Обзор' },
  { value: 'hours', label: 'Рабочее время' },
  { value: 'finance', label: 'Финансы' },
];

export function ProjectTabs({
  value,
  hoursCount,
  financeCount,
  onChange,
}: ProjectTabsProps) {
  const countByTab: Partial<
    Record<ProjectDetailTab, number>
  > = {
    hours: hoursCount,
    finance: financeCount,
  };

  return (
    <div className="overflow-x-auto rounded-2xl border border-slate-200 bg-white p-1.5 shadow-sm dark:border-slate-800 dark:bg-slate-900">
      <div className="flex min-w-max gap-1" role="tablist">
        {TABS.map((tab) => {
          const selected = value === tab.value;
          const count = countByTab[tab.value];

          return (
            <button
              key={tab.value}
              type="button"
              role="tab"
              aria-selected={selected}
              onClick={() => onChange(tab.value)}
              className={[
                'flex h-10 items-center gap-2 rounded-xl px-4 text-sm font-semibold transition-all',
                selected
                  ? 'bg-indigo-600 text-white shadow-md shadow-indigo-500/20'
                  : 'text-slate-500 hover:bg-slate-100 hover:text-slate-950 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-white',
              ].join(' ')}
            >
              {tab.label}

              {typeof count === 'number' && (
                <span
                  className={[
                    'rounded-full px-2 py-0.5 text-[10px] font-bold',
                    selected
                      ? 'bg-white/15 text-white'
                      : 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-300',
                  ].join(' ')}
                >
                  {count}
                </span>
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}