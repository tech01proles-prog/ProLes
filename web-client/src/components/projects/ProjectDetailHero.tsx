import type { ProjectDto } from '../../types';
import {
  StatusBadge,
  type StatusBadgeTone,
} from '../ui';

interface ProjectDetailHeroProps {
  project: ProjectDto;
  totalHours: number;
  onBack: () => void;
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

export function ProjectDetailHero({
  project,
  totalHours,
  onBack,
}: ProjectDetailHeroProps) {
  const status =
    STATUS_CONFIG[project.status] ?? STATUS_CONFIG.new;

  return (
    <section className="relative overflow-hidden rounded-3xl bg-slate-950 p-6 text-white shadow-xl md:p-8 dark:bg-slate-900">
      <div className="pointer-events-none absolute -right-20 -top-24 h-80 w-80 rounded-full bg-indigo-500/30 blur-3xl" />
      <div className="pointer-events-none absolute -bottom-36 left-1/3 h-72 w-72 rounded-full bg-violet-500/20 blur-3xl" />

      <div className="relative">
        <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
          <button
            type="button"
            onClick={onBack}
            className="rounded-xl border border-white/10 bg-white/5 px-3 py-2 text-sm font-semibold text-slate-200 transition-colors hover:bg-white/10"
          >
            ← Все проекты
          </button>

          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge tone={status.tone} dot>
              {status.label}
            </StatusBadge>

            {!project.isActive && (
              <StatusBadge tone="danger">Архив</StatusBadge>
            )}
          </div>
        </div>

        <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_300px] lg:items-end">
          <div>
            <p className="text-xs font-bold uppercase tracking-[0.18em] text-indigo-300">
              {project.projectNumber ||
                project.projectCode ||
                'Карточка проекта'}
            </p>

            <h2 className="mt-3 max-w-4xl text-2xl font-bold tracking-tight md:text-4xl">
              {project.name}
            </h2>

            <p className="mt-3 max-w-3xl text-sm leading-6 text-slate-300 md:text-base">
              {project.productService ||
                'Описание результата проекта пока не заполнено.'}
            </p>

            <div className="mt-6 flex flex-wrap gap-x-6 gap-y-2 text-sm text-slate-300">
              <span>
                Клиент:{' '}
                <strong className="font-semibold text-white">
                  {project.client || 'Не указан'}
                </strong>
              </span>

              <span>
                Локация:{' '}
                <strong className="font-semibold text-white">
                  {project.location || 'Не указана'}
                </strong>
              </span>

              <span>
                Ответственный:{' '}
                <strong className="font-semibold text-white">
                  {project.lead || 'Не назначен'}
                </strong>
              </span>
            </div>
          </div>

          <div className="rounded-2xl border border-white/10 bg-white/[0.07] p-5 backdrop-blur">
            <div className="text-[10px] font-bold uppercase tracking-[0.15em] text-slate-400">
              Учтено времени
            </div>

            <div className="mt-2 text-3xl font-bold tabular-nums">
              {formatHours(totalHours)} ч
            </div>

            <div className="mt-4 border-t border-white/10 pt-4">
              <div className="text-[10px] font-bold uppercase tracking-[0.15em] text-slate-400">
                Договор
              </div>

              <div className="mt-2 text-sm font-semibold text-slate-200">
                {project.contract || 'Не указан'}
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}