import { StatCard } from '../ui';

interface TimesheetSummaryProps {
  totalHours: number;
  plannedHours: number;
  workDays: number;
  projectCount: number;
}

function formatHours(value: number) {
  return new Intl.NumberFormat('ru-RU', {
    maximumFractionDigits: 1,
  }).format(value);
}

export function TimesheetSummary({
  totalHours,
  plannedHours,
  workDays,
  projectCount,
}: TimesheetSummaryProps) {
  const difference = totalHours - plannedHours;

  return (
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <StatCard
        label="Отработано"
        value={`${formatHours(totalHours)} ч`}
        hint="За выбранный период"
        tone="indigo"
        icon={<span aria-hidden="true">◷</span>}
      />

      <StatCard
        label="План"
        value={`${formatHours(plannedHours)} ч`}
        hint={
          difference === 0
            ? 'План выполнен'
            : difference > 0
              ? `Сверх плана: ${formatHours(difference)} ч`
              : `Осталось: ${formatHours(Math.abs(difference))} ч`
        }
        tone={difference >= 0 ? 'emerald' : 'amber'}
        icon={<span aria-hidden="true">✓</span>}
      />

      <StatCard
        label="Рабочие дни"
        value={workDays}
        hint="Дней с учтённым временем"
        tone="slate"
        icon={<span aria-hidden="true">□</span>}
      />

      <StatCard
        label="Проекты"
        value={projectCount}
        hint="Проектов в табеле"
        tone="rose"
        icon={<span aria-hidden="true">◇</span>}
      />
    </div>
  );
}