import {
  useCallback,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/client';
import type {
  ExpenseDto,
  ProjectDto,
  TimeEntryDto,
} from '../types';
import { generateUUID } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';
import {
  ProjectCard,
  ProjectFilters,
  ProjectForm,
  type ProjectActivityFilter,
  type ProjectFormValue,
  type ProjectStatusFilter,
} from '../components/projects';
import {
  EmptyState,
  Modal,
  StatCard,
} from '../components/ui';

export function ProjectsPage() {
  const navigate = useNavigate();
  const { can } = usePermissions();

  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [entries, setEntries] = useState<TimeEntryDto[]>([]);
  const [expenses, setExpenses] = useState<ExpenseDto[]>([]);

  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [createModalOpen, setCreateModalOpen] = useState(false);

  const [search, setSearch] = useState('');
  const [status, setStatus] =
    useState<ProjectStatusFilter>('all');
  const [activity, setActivity] =
    useState<ProjectActivityFilter>('all');

  const canCreateProjects = can('projects', 'create');

  const loadData = useCallback(async () => {
    setLoading(true);
    setError('');

    const [projectsResult, entriesResult, expensesResult] =
      await Promise.allSettled([
        api.get<ProjectDto[]>('/projects'),
        api.get<TimeEntryDto[]>('/entries/all'),
        api.get<ExpenseDto[]>('/expenses/all'),
      ]);

    setProjects(
      projectsResult.status === 'fulfilled'
        ? projectsResult.value.data
        : [],
    );
    setEntries(
      entriesResult.status === 'fulfilled'
        ? entriesResult.value.data
        : [],
    );
    setExpenses(
      expensesResult.status === 'fulfilled'
        ? expensesResult.value.data
        : [],
    );

    if (projectsResult.status === 'rejected') {
      setError('Не удалось загрузить список проектов.');
    }

    setLoading(false);
  }, []);

  useEffect(() => {
    void loadData();
  }, [loadData]);

  const hoursByProject = useMemo(() => {
    const result = new Map<string, number>();

    entries.forEach((entry) => {
      result.set(
        entry.projectId,
        (result.get(entry.projectId) ?? 0) +
          Number(entry.hours || 0),
      );
    });

    return result;
  }, [entries]);

  const expensesByProject = useMemo(() => {
    const result = new Map<string, number>();

    expenses.forEach((expense) => {
      result.set(
        expense.projectId,
        (result.get(expense.projectId) ?? 0) +
          Number(expense.amount || 0),
      );
    });

    return result;
  }, [expenses]);

  const filteredProjects = useMemo(() => {
    const query = search.trim().toLocaleLowerCase('ru-RU');

    return projects
      .filter((project) => {
        const searchableText = [
          project.name,
          project.client,
          project.location,
          project.contract,
          project.projectNumber,
          project.productService,
        ]
          .filter(Boolean)
          .join(' ')
          .toLocaleLowerCase('ru-RU');

        const matchesSearch =
          query === '' || searchableText.includes(query);
        const matchesStatus =
          status === 'all' || project.status === status;
        const matchesActivity =
          activity === 'all' ||
          (activity === 'active'
            ? project.isActive
            : !project.isActive);

        return (
          matchesSearch &&
          matchesStatus &&
          matchesActivity
        );
      })
      .sort((left, right) => {
        if (left.isActive !== right.isActive) {
          return Number(right.isActive) - Number(left.isActive);
        }

        return left.name.localeCompare(right.name, 'ru');
      });
  }, [activity, projects, search, status]);

  const summary = useMemo(() => {
    const active = projects.filter(
      (project) => project.isActive,
    ).length;
    const inProgress = projects.filter(
      (project) => project.status === 'in_progress',
    ).length;
    const completed = projects.filter(
      (project) => project.status === 'completed',
    ).length;
    const totalHours = entries.reduce(
      (sum, entry) => sum + Number(entry.hours || 0),
      0,
    );

    return {
      active,
      inProgress,
      completed,
      totalHours,
    };
  }, [entries, projects]);

  const createProject = async (
    value: ProjectFormValue,
  ) => {
    setSaving(true);
    setError('');

    try {
      await api.post('/projects', {
        id: generateUUID(),
        name: value.name,
        isActive: value.isActive,
        status: value.status,
        lead: '',
        revenue: 0,
        expenses: 0,
        cost: 0,
        profit: 0,
        projectNumber: '',
        subProjectNumber: '',
        client: value.client,
        location: value.location,
        productService: value.productService,
        quantity: Number(value.quantity),
        deliveryDate: value.deliveryDate || null,
        contract: value.contract,
        notes: '',
        projectCode: '',
        completionDate: null,
        customer: '',
        productionCost: 0,
        transportToClient: 0,
        sellingPrice: 0,
      });

      setCreateModalOpen(false);
      await loadData();
    } catch (requestError) {
      console.error(requestError);
      setError('Не удалось создать проект.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <section className="relative overflow-hidden rounded-3xl bg-slate-950 p-6 text-white shadow-xl md:p-8 dark:bg-slate-900">
        <div className="pointer-events-none absolute -right-24 -top-28 h-80 w-80 rounded-full bg-indigo-500/30 blur-3xl" />
        <div className="pointer-events-none absolute -bottom-32 left-1/3 h-64 w-64 rounded-full bg-violet-500/20 blur-3xl" />

        <div className="relative flex flex-col gap-6 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <p className="text-xs font-bold uppercase tracking-[0.18em] text-indigo-300">
              Портфель компании
            </p>
            <h2 className="mt-3 max-w-2xl text-2xl font-bold tracking-tight md:text-4xl">
              Проекты, показатели и текущая загрузка
            </h2>
            <p className="mt-3 max-w-xl text-sm leading-6 text-slate-300">
              Единый каталог для контроля работ, сроков,
              расходов и трудозатрат.
            </p>
          </div>

          {canCreateProjects && (
            <button
              type="button"
              onClick={() => setCreateModalOpen(true)}
              className="rounded-xl bg-white px-5 py-3 text-sm font-semibold text-slate-950 shadow-lg transition-transform hover:-translate-y-0.5"
            >
              + Новый проект
            </button>
          )}
        </div>
      </section>

      {error && (
        <div
          role="alert"
          className="rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-medium text-rose-700 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-300"
        >
          {error}
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Активные"
          value={summary.active}
          hint={`Всего проектов: ${projects.length}`}
          tone="indigo"
          icon={<span aria-hidden="true">◇</span>}
        />
        <StatCard
          label="В работе"
          value={summary.inProgress}
          hint="Текущие проекты"
          tone="amber"
          icon={<span aria-hidden="true">◷</span>}
        />
        <StatCard
          label="Завершено"
          value={summary.completed}
          hint="В портфеле компании"
          tone="emerald"
          icon={<span aria-hidden="true">✓</span>}
        />
        <StatCard
          label="Учтено часов"
          value={new Intl.NumberFormat('ru-RU', {
            maximumFractionDigits: 1,
          }).format(summary.totalHours)}
          hint="По всем проектам"
          tone="slate"
          icon={<span aria-hidden="true">⌁</span>}
        />
      </div>

      <ProjectFilters
        search={search}
        status={status}
        activity={activity}
        resultCount={filteredProjects.length}
        onSearchChange={setSearch}
        onStatusChange={setStatus}
        onActivityChange={setActivity}
        onReset={() => {
          setSearch('');
          setStatus('all');
          setActivity('all');
        }}
      />

      {loading ? (
        <div className="flex min-h-72 items-center justify-center">
          <div
            className="h-10 w-10 animate-spin rounded-full border-4 border-indigo-100 border-t-indigo-600 dark:border-slate-800 dark:border-t-indigo-400"
            aria-label="Загрузка проектов"
          />
        </div>
      ) : filteredProjects.length === 0 ? (
        <div className="rounded-2xl border border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
          <EmptyState
            title="Проекты не найдены"
            description="Измените параметры поиска или сбросьте фильтры."
            action={
              <button
                type="button"
                onClick={() => {
                  setSearch('');
                  setStatus('all');
                  setActivity('all');
                }}
                className="rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700"
              >
                Сбросить фильтры
              </button>
            }
          />
        </div>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {filteredProjects.map((project) => (
            <ProjectCard
              key={project.id}
              project={project}
              hours={hoursByProject.get(project.id) ?? 0}
              expenses={expensesByProject.get(project.id) ?? 0}
              onOpen={(selectedProject) =>
                navigate(`/projects/${selectedProject.id}`)
              }
            />
          ))}
        </div>
      )}

      <Modal
        open={createModalOpen}
        title="Новый проект"
        description="Создайте карточку и укажите основные параметры проекта."
        size="lg"
        closeOnBackdrop={!saving}
        onClose={() => {
          if (!saving) setCreateModalOpen(false);
        }}
      >
        <ProjectForm
          submitting={saving}
          onCancel={() => setCreateModalOpen(false)}
          onSubmit={createProject}
        />
      </Modal>
    </div>
  );
}