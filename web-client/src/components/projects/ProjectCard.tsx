import type { ProjectDto } from '../../types';
import { formatMoney } from '../../lib/utils';
import { StatusBadge, type StatusBadgeTone } from '../ui';

interface ProjectCardProps {
  project: ProjectDto;
  hours: number;
  expenses: number;
  onOpen: (project: ProjectDto) => void;
}

const STATUS_CONFIG: Record<
  string,
  { label: string; tone: StatusBadgeTone }
> = {
  new: { label: 'Новый', tone: 'info' },
  in_progress: { label: 'В работе', tone: 'warning' },
  completed: { label: 'Завершён', tone: 'success' },
  cancelled: { label: 'Отменён', tone: 'danger' },
};

function formatHours(value: number) {
  return new Intl.NumberFormat('ru-RU', {
    maximumFractionDigits: 1,
  }).format(value);
}

export function ProjectCard({
  project,
  hours,
  expenses,
  onOpen,
}: ProjectCardProps) {
  const status = STATUS_CONFIG[project.status] ?? STATUS_CONFIG.new;

  return (
    <article className="group flex h-full flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm transition-all duration-300 hover:-translate-y-1 hover:border-indigo-200 hover:shadow-xl dark:border-slate-800 dark:bg-slate-900 dark:hover:border-indigo-800">
      <button
        type="button"
        onClick={() => onOpen(project)}
        className="flex h-full flex-col text-left"
      >
        <div className="flex items-start justify-between gap-4 p-5">
          <div className="min-w-0">
            <div className="mb-2 flex items-center gap-2 text-[11px] font-bold uppercase tracking-[0.14em] text-slate-400">
              <span>{project.projectNumber || 'Проект'}</span>
              {!project.isActive && (
                <span className="text-rose-500">Архив</span>
              )}
            </div>

            <h3 className="line-clamp-2 text-base font-bold leading-6 text-slate-950 transition-colors group-hover:text-indigo-600 dark:text-white dark:group-hover:text-indigo-400">
              {project.name}
            </h3>

            <p className="mt-2 truncate text-sm text-slate-500 dark:text-slate-400">
              {project.client || 'Клиент не указан'}
            </p>
          </div>

          <StatusBadge tone={status.tone} dot>
            {status.label}
          </StatusBadge>
        </div>

        <div className="mx-5 grid grid-cols-2 overflow-hidden rounded-xl border border-slate-200 dark:border-slate-700">
          <div className="border-r border-slate-200 p-3 dark:border-slate-700">
            <div className="text-[10px] font-bold uppercase tracking-wider text-slate-400">
              Часы
            </div>
            <div className="mt-1 text-base font-bold text-slate-950 dark:text-white">
              {hours > 0 ? `${formatHours(hours)} ч` : '—'}
            </div>
          </div>

          <div className="p-3">
            <div className="text-[10px] font-bold uppercase tracking-wider text-slate-400">
              Расходы
            </div>
            <div className="mt-1 truncate text-base font-bold text-slate-950 dark:text-white">
              {expenses > 0 ? formatMoney(expenses) : '—'}
            </div>
          </div>
        </div>

        <div className="flex-1 space-y-2 px-5 py-4 text-sm text-slate-500 dark:text-slate-400">
          {project.productService && (
            <p className="line-clamp-2">
              <span className="font-semibold text-slate-700 dark:text-slate-300">
                Результат:
              </span>{' '}
              {project.productService}
            </p>
          )}

          {project.location && (
            <p className="truncate">
              <span className="font-semibold text-slate-700 dark:text-slate-300">
                Локация:
              </span>{' '}
              {project.location}
            </p>
          )}

          {project.deliveryDate && (
            <p>
              <span className="font-semibold text-slate-700 dark:text-slate-300">
                Срок:
              </span>{' '}
              {new Date(
                `${project.deliveryDate}T00:00:00`,
              ).toLocaleDateString('ru-RU')}
            </p>
          )}
        </div>

        <div className="flex items-center justify-between border-t border-slate-200 px-5 py-3 text-sm dark:border-slate-800">
          <span className="truncate text-slate-400">
            {project.lead
              ? `Ответственный: ${project.lead}`
              : 'Ответственный не назначен'}
          </span>

          <span className="ml-3 shrink-0 font-bold text-indigo-600 dark:text-indigo-400">
            Открыть →
          </span>
        </div>
      </button>
    </article>
  );
}