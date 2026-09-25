import {
  useCallback,
  useEffect,
  useMemo,
  useState,
} from 'react';
import {
  useNavigate,
  useParams,
} from 'react-router-dom';
import api from '../api/client';
import type {
  ExpenseDto,
  IncomeDto,
  ProjectDto,
  TimeEntryDto,
} from '../types';
import {
  formatDate,
  formatMoney,
} from '../lib/utils';
import {
  ProjectActivityList,
  ProjectDetailHero,
  ProjectTabs,
  type ProjectActivityItem,
  type ProjectDetailTab,
} from '../components/projects';
import {
  EmptyState,
  PageSection,
  StatCard,
  StatusBadge,
} from '../components/ui';

const EXPENSE_TYPE_LABELS: Record<string, string> = {
  HOUSEHOLD: 'Хозяйственные нужды',
  CONTRACTORS: 'Подрядчики',
  ROAD: 'Дорога',
  PER_DIEM: 'Суточные',
  PER_DIEM_EXTRA: 'Суточные сверх нормы',
  CASH: 'Наличные',
  CARD: 'Карта',
  OTHER: 'Прочее',
};

const INCOME_TYPE_LABELS: Record<string, string> = {
  HOUSEHOLD: 'Хозяйственный доход',
  CARD: 'Безналичный доход',
  CASH: 'Наличный доход',
  OTHER: 'Прочий доход',
};

function formatHours(value: number) {
  return new Intl.NumberFormat('ru-RU', {
    maximumFractionDigits: 1,
  }).format(value);
}

function formatOptionalDate(value: string | null) {
  return value ? formatDate(value) : 'Не указана';
}

function sumByCurrency(
  items: Array<{ amount: number; currency: string }>,
) {
  return items.reduce<Record<string, number>>(
    (result, item) => {
      const currency =
        item.currency?.trim().toUpperCase() || 'RUB';

      result[currency] =
        (result[currency] ?? 0) + Number(item.amount || 0);

      return result;
    },
    {},
  );
}

function formatCurrencyTotals(
  values: Record<string, number>,
) {
  const entries = Object.entries(values);

  if (entries.length === 0) return '0 ₽';

  return entries
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([currency, amount]) =>
      formatMoney(amount, currency),
    )
    .join(' · ');
}

function getRubTotal(values: Record<string, number>) {
  return values.RUB ?? 0;
}

export function ProjectDetailPage() {
  const { projectId } = useParams<{
    projectId: string;
  }>();
  const navigate = useNavigate();

  const [project, setProject] =
    useState<ProjectDto | null>(null);
  const [entries, setEntries] =
    useState<TimeEntryDto[]>([]);
  const [expenses, setExpenses] =
    useState<ExpenseDto[]>([]);
  const [incomes, setIncomes] =
    useState<IncomeDto[]>([]);

  const [activeTab, setActiveTab] =
    useState<ProjectDetailTab>('overview');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const loadData = useCallback(async () => {
    if (!projectId) {
      setProject(null);
      setError('Идентификатор проекта не указан.');
      setLoading(false);
      return;
    }

    setLoading(true);
    setError('');

    const [
      projectsResult,
      entriesResult,
      expensesResult,
      incomesResult,
    ] = await Promise.allSettled([
      api.get<ProjectDto[]>('/projects'),
      api.get<TimeEntryDto[]>('/entries/all'),
      api.get<ExpenseDto[]>('/expenses/all'),
      api.get<IncomeDto[]>('/incomes/all'),
    ]);

    if (projectsResult.status === 'fulfilled') {
      const selectedProject =
        projectsResult.value.data.find(
          (item) => item.id === projectId,
        );

      setProject(selectedProject ?? null);

      if (!selectedProject) {
        setError('Проект не найден.');
      }
    } else {
      setProject(null);
      setError('Не удалось загрузить карточку проекта.');
    }

    setEntries(
      entriesResult.status === 'fulfilled'
        ? entriesResult.value.data.filter(
            (entry) => entry.projectId === projectId,
          )
        : [],
    );

    setExpenses(
      expensesResult.status === 'fulfilled'
        ? expensesResult.value.data.filter(
            (expense) => expense.projectId === projectId,
          )
        : [],
    );

    setIncomes(
      incomesResult.status === 'fulfilled'
        ? incomesResult.value.data.filter(
            (income) => income.projectId === projectId,
          )
        : [],
    );

    if (
      projectsResult.status === 'fulfilled' &&
      (entriesResult.status === 'rejected' ||
        expensesResult.status === 'rejected' ||
        incomesResult.status === 'rejected')
    ) {
      setError(
        'Карточка проекта загружена, но часть связанных данных недоступна.',
      );
    }

    setLoading(false);
  }, [projectId]);

  useEffect(() => {
    void loadData();
  }, [loadData]);

  const totalHours = useMemo(
    () =>
      entries.reduce(
        (sum, entry) =>
          sum + Number(entry.hours || 0),
        0,
      ),
    [entries],
  );

  const workDays = useMemo(
    () => new Set(entries.map((entry) => entry.date)).size,
    [entries],
  );

  const expensesByCurrency = useMemo(
    () => sumByCurrency(expenses),
    [expenses],
  );

  const incomesByCurrency = useMemo(
    () => sumByCurrency(incomes),
    [incomes],
  );

  const rubBalance =
    getRubTotal(incomesByCurrency) -
    getRubTotal(expensesByCurrency);

  const expenseGroups = useMemo(
    () =>
      expenses.reduce<Record<string, number>>(
        (result, expense) => {
          result[expense.type] =
            (result[expense.type] ?? 0) +
            Number(expense.amount || 0);

          return result;
        },
        {},
      ),
    [expenses],
  );

  const timeItems = useMemo<ProjectActivityItem[]>(
    () =>
      [...entries]
        .sort((left, right) =>
          right.date.localeCompare(left.date),
        )
        .map((entry) => ({
          id: entry.id,
          title: entry.projectName || 'Рабочее время',
          description: entry.comment || undefined,
          meta: `${formatDate(entry.date)} · ${
            entry.country === 'BY'
              ? 'Беларусь'
              : 'Россия'
          }`,
          value: `${formatHours(entry.hours)} ч`,
          badge: entry.synced
            ? 'Синхронизировано'
            : 'Черновик',
          tone: 'indigo',
        })),
    [entries],
  );

  const expenseItems =
    useMemo<ProjectActivityItem[]>(
      () =>
        [...expenses]
          .sort((left, right) =>
            right.date.localeCompare(left.date),
          )
          .map((expense) => ({
            id: expense.id,
            title:
              expense.name ||
              EXPENSE_TYPE_LABELS[expense.type] ||
              'Расход',
            description: expense.comment || undefined,
            meta: formatDate(expense.date),
            value: formatMoney(
              expense.amount,
              expense.currency,
            ),
            badge:
              expense.receiptCount &&
              expense.receiptCount > 0
                ? `Чеков: ${expense.receiptCount}`
                : expense.receiptSubmitted
                  ? 'Чек предоставлен'
                  : 'Без чека',
            tone: 'rose',
          })),
      [expenses],
    );

  const incomeItems =
    useMemo<ProjectActivityItem[]>(
      () =>
        [...incomes]
          .sort((left, right) =>
            right.date.localeCompare(left.date),
          )
          .map((income) => ({
            id: income.id,
            title:
              income.name ||
              INCOME_TYPE_LABELS[income.type] ||
              'Доход',
            meta: formatDate(income.date),
            value: formatMoney(
              income.amount,
              income.currency,
            ),
            badge:
              INCOME_TYPE_LABELS[income.type] ||
              income.type ||
              undefined,
            tone: 'emerald',
          })),
      [incomes],
    );

  if (loading) {
    return (
      <div className="flex min-h-80 items-center justify-center">
        <div
          className="h-10 w-10 animate-spin rounded-full border-4 border-indigo-100 border-t-indigo-600 dark:border-slate-800 dark:border-t-indigo-400"
          aria-label="Загрузка проекта"
        />
      </div>
    );
  }

  if (!project) {
    return (
      <PageSection>
        <EmptyState
          title="Проект недоступен"
          description={
            error ||
            'Карточка проекта не найдена или была удалена.'
          }
          action={
            <button
              type="button"
              onClick={() => navigate('/projects')}
              className="rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700"
            >
              Вернуться к проектам
            </button>
          }
        />
      </PageSection>
    );
  }

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <ProjectDetailHero
        project={project}
        totalHours={totalHours}
        onBack={() => navigate('/projects')}
      />

      {error && (
        <div
          role="alert"
          className="rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm font-medium text-amber-700 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-300"
        >
          {error}
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Рабочее время"
          value={`${formatHours(totalHours)} ч`}
          hint={`${workDays} рабочих дней`}
          tone="indigo"
          icon={<span aria-hidden="true">◷</span>}
        />

        <StatCard
          label="Доходы"
          value={formatCurrencyTotals(incomesByCurrency)}
          hint={`${incomes.length} операций`}
          tone="emerald"
          icon={<span aria-hidden="true">↗</span>}
        />

        <StatCard
          label="Расходы"
          value={formatCurrencyTotals(expensesByCurrency)}
          hint={`${expenses.length} операций`}
          tone="rose"
          icon={<span aria-hidden="true">↘</span>}
        />

        <StatCard
          label="Баланс в RUB"
          value={formatMoney(rubBalance, 'RUB')}
          hint="Без пересчёта других валют"
          tone={rubBalance >= 0 ? 'emerald' : 'rose'}
          icon={<span aria-hidden="true">₽</span>}
        />
      </div>

      <ProjectTabs
        value={activeTab}
        hoursCount={entries.length}
        financeCount={expenses.length + incomes.length}
        onChange={setActiveTab}
      />

      {activeTab === 'overview' && (
        <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_380px]">
          <PageSection
            title="Параметры проекта"
            description="Договорные и производственные сведения"
          >
            <dl className="grid gap-4 sm:grid-cols-2">
              <div className="rounded-xl bg-slate-50 p-4 dark:bg-slate-800/50">
                <dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">
                  Товар или услуга
                </dt>
                <dd className="mt-2 text-sm font-semibold text-slate-900 dark:text-white">
                  {project.productService || 'Не указано'}
                </dd>
              </div>

              <div className="rounded-xl bg-slate-50 p-4 dark:bg-slate-800/50">
                <dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">
                  Количество
                </dt>
                <dd className="mt-2 text-sm font-semibold text-slate-900 dark:text-white">
                  {project.quantity}
                </dd>
              </div>

              <div className="rounded-xl bg-slate-50 p-4 dark:bg-slate-800/50">
                <dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">
                  Срок поставки
                </dt>
                <dd className="mt-2 text-sm font-semibold text-slate-900 dark:text-white">
                  {formatOptionalDate(
                    project.deliveryDate,
                  )}
                </dd>
              </div>

              <div className="rounded-xl bg-slate-50 p-4 dark:bg-slate-800/50">
                <dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">
                  Дата завершения
                </dt>
                <dd className="mt-2 text-sm font-semibold text-slate-900 dark:text-white">
                  {formatOptionalDate(
                    project.completionDate,
                  )}
                </dd>
              </div>

              <div className="rounded-xl bg-slate-50 p-4 dark:bg-slate-800/50">
                <dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">
                  Код проекта
                </dt>
                <dd className="mt-2 text-sm font-semibold text-slate-900 dark:text-white">
                  {project.projectCode || 'Не указан'}
                </dd>
              </div>

              <div className="rounded-xl bg-slate-50 p-4 dark:bg-slate-800/50">
                <dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">
                  Заказчик
                </dt>
                <dd className="mt-2 text-sm font-semibold text-slate-900 dark:text-white">
                  {project.customer ||
                    project.client ||
                    'Не указан'}
                </dd>
              </div>
            </dl>

            {project.notes && (
              <div className="mt-4 rounded-xl border border-amber-200 bg-amber-50 p-4 dark:border-amber-900 dark:bg-amber-950/20">
                <div className="text-xs font-semibold uppercase tracking-wide text-amber-600 dark:text-amber-400">
                  Заметки
                </div>
                <p className="mt-2 whitespace-pre-wrap text-sm leading-6 text-slate-700 dark:text-slate-200">
                  {project.notes}
                </p>
              </div>
            )}
          </PageSection>

          <PageSection
            title="Расходы по типам"
            description="Структура расходов в RUB"
          >
            {Object.keys(expenseGroups).length === 0 ? (
              <EmptyState
                compact
                title="Расходов пока нет"
                description="Операции появятся после добавления расходов по проекту."
              />
            ) : (
              <div className="space-y-4">
                {Object.entries(expenseGroups)
                  .sort((left, right) => right[1] - left[1])
                  .map(([type, amount]) => {
                    const rubExpenses =
                      expensesByCurrency.RUB ?? 0;
                    const percentage =
                      rubExpenses > 0
                        ? Math.min(
                            (amount / rubExpenses) * 100,
                            100,
                          )
                        : 0;

                    return (
                      <div key={type}>
                        <div className="mb-1.5 flex items-center justify-between gap-3">
                          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
                            {EXPENSE_TYPE_LABELS[type] ||
                              type}
                          </span>
                          <span className="text-sm font-bold text-rose-600 dark:text-rose-400">
                            {formatMoney(amount, 'RUB')}
                          </span>
                        </div>

                        <div className="h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
                          <div
                            className="h-full rounded-full bg-gradient-to-r from-rose-500 to-orange-400"
                            style={{
                              width: `${percentage}%`,
                            }}
                          />
                        </div>
                      </div>
                    );
                  })}
              </div>
            )}
          </PageSection>
        </div>
      )}

      {activeTab === 'hours' && (
        <PageSection
          title="Рабочее время"
          description="Все записи табеля по проекту"
          action={
            <StatusBadge tone="info">
              {formatHours(totalHours)} ч
            </StatusBadge>
          }
        >
          <ProjectActivityList
            items={timeItems}
            emptyTitle="Записей рабочего времени нет"
            emptyDescription="Часы появятся после заполнения табеля по этому проекту."
          />
        </PageSection>
      )}

      {activeTab === 'finance' && (
        <div className="grid gap-6 xl:grid-cols-2">
          <PageSection
            title="Доходы"
            description={formatCurrencyTotals(
              incomesByCurrency,
            )}
            action={
              <StatusBadge tone="success">
                {incomes.length}
              </StatusBadge>
            }
          >
            <ProjectActivityList
              items={incomeItems}
              emptyTitle="Доходов пока нет"
              emptyDescription="Доходные операции по проекту отсутствуют."
            />
          </PageSection>

          <PageSection
            title="Расходы"
            description={formatCurrencyTotals(
              expensesByCurrency,
            )}
            action={
              <StatusBadge tone="danger">
                {expenses.length}
              </StatusBadge>
            }
          >
            <ProjectActivityList
              items={expenseItems}
              emptyTitle="Расходов пока нет"
              emptyDescription="Расходные операции по проекту отсутствуют."
            />
          </PageSection>
        </div>
      )}
    </div>
  );
}

export default ProjectDetailPage;