interface ScopeTabsProps {
  scope: 'my' | 'all';
  onChange: (scope: 'my' | 'all') => void;
  canViewAll: boolean;
  myCount?: number;
  allCount?: number;
}

/**
 * Единый переключатель области видимости: «Мои» / «Все сотрудники».
 * Вкладка «Все» появляется только при наличии права *_all.view.
 */
export function ScopeTabs({ scope, onChange, canViewAll, myCount, allCount }: ScopeTabsProps) {
  const activeCls = 'bg-white dark:bg-slate-700 text-slate-900 dark:text-white shadow-sm';
  const inactiveCls = 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200';

  const badge = (active: boolean, count?: number) =>
    count === undefined ? null : (
      <span className={`text-[10px] px-1.5 py-0.5 rounded-md font-bold tabular-nums ${
        active ? 'bg-indigo-100 dark:bg-indigo-900/60 text-indigo-700 dark:text-indigo-300'
               : 'bg-slate-200 dark:bg-slate-700 text-slate-500 dark:text-slate-400'
      }`}>
        {count}
      </span>
    );

  return (
    <div className="inline-flex bg-slate-100 dark:bg-slate-800 rounded-xl p-1 gap-1">
      <button
        onClick={() => onChange('my')}
        className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-semibold transition-all duration-200 ${scope === 'my' ? activeCls : inactiveCls}`}
      >
        <span>👤 Мои</span>
        {badge(scope === 'my', myCount)}
      </button>

      {canViewAll && (
        <button
          onClick={() => onChange('all')}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-semibold transition-all duration-200 ${scope === 'all' ? activeCls : inactiveCls}`}
        >
          <span>👥 Все сотрудники</span>
          {badge(scope === 'all', allCount)}
        </button>
      )}
    </div>
  );
}