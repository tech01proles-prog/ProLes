import { formatMoney } from '../../lib/utils';

interface CurrencySummaryProps {
  title: string;
  values: Record<string, number>;
  tone?: 'indigo' | 'emerald' | 'rose';
  emptyText?: string;
}

const TONE_CLASSES = {
  indigo:
    'from-indigo-500/10 to-violet-500/10 border-indigo-200 dark:border-indigo-900',
  emerald:
    'from-emerald-500/10 to-teal-500/10 border-emerald-200 dark:border-emerald-900',
  rose:
    'from-rose-500/10 to-orange-500/10 border-rose-200 dark:border-rose-900',
};

export function CurrencySummary({
  title,
  values,
  tone = 'indigo',
  emptyText = 'Нет операций',
}: CurrencySummaryProps) {
  const entries = Object.entries(values).sort(([left], [right]) =>
    left.localeCompare(right),
  );

  return (
    <section
      className={[
        'rounded-2xl border bg-gradient-to-br p-5',
        TONE_CLASSES[tone],
      ].join(' ')}
    >
      <h3 className="text-xs font-bold uppercase tracking-[0.14em] text-slate-500 dark:text-slate-400">
        {title}
      </h3>

      {entries.length === 0 ? (
        <p className="mt-3 text-sm text-slate-400">{emptyText}</p>
      ) : (
        <div className="mt-3 flex flex-wrap gap-x-6 gap-y-2">
          {entries.map(([currency, amount]) => (
            <div key={currency}>
              <div className="text-xs font-semibold text-slate-400">
                {currency}
              </div>
              <div className="mt-0.5 text-xl font-bold tabular-nums text-slate-950 dark:text-white">
                {formatMoney(amount, currency)}
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}