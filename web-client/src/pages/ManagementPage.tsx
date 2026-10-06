import { useNavigate } from 'react-router-dom';
import { usePermissions } from '../hooks/usePermissions';

interface ManagementCard {
  to: string;
  icon: string;
  title: string;
  description: string;
  gradient: string;
  permission?: string;
  action?: 'view' | 'create' | 'edit' | 'delete';
  directorOnly?: boolean;
}

interface ManagementSection {
  label: string;
  hint: string;
  cards: ManagementCard[];
}

const SECTIONS: ManagementSection[] = [
  {
    label: 'Проекты',
    hint: 'Проекты, подпроекты и сертифицированная документация',
    cards: [
      {
        to: '/projects',
        icon: '▣',
        title: 'Проекты',
        description: 'Реестр проектов и подпроектов',
        gradient: 'from-blue-500 to-indigo-500',
        permission: 'projects',
        action: 'view',
      },
      {
        to: '/projects?view=tnpa',
        icon: '▤',
        title: 'ТНПА',
        description: 'Документация проектов и подпроектов',
        gradient: 'from-cyan-500 to-blue-500',
        permission: 'projects',
        action: 'view',
      },
    ],
  },
  {
    label: 'Операционная деятельность',
    hint: 'Поездки и сопроводительные документы',
    cards: [
      {
        to: '/trips',
        icon: '✈',
        title: 'Командировки',
        description: 'Оформление и завершение поездок',
        gradient: 'from-violet-500 to-purple-500',
        permission: 'business_trips_all',
        action: 'view',
      },
      {
        to: '/tickets',
        icon: '▣',
        title: 'Билеты',
        description: 'Билеты и прикреплённые чеки',
        gradient: 'from-orange-500 to-amber-500',
        permission: 'tickets',
        action: 'view',
      },
    ],
  },
  {
    label: 'Финансы и аналитика',
    hint: 'Расчёты, расходы и показатели компании',
    cards: [
      {
        to: '/analytics',
        icon: '▥',
        title: 'Аналитика',
        description: 'Показатели работы компании',
        gradient: 'from-indigo-500 to-violet-500',
        permission: 'analytics',
        action: 'view',
      },
      {
        to: '/payroll',
        icon: '₽',
        title: 'Зарплата',
        description: 'Начисления, удержания и выплаты',
        gradient: 'from-emerald-500 to-green-500',
        permission: 'payroll',
        action: 'view',
      },
      {
        to: '/expenses',
        icon: '↕',
        title: 'Расходы / Доходы',
        description: 'Финансовые операции по проектам',
        gradient: 'from-sky-500 to-cyan-500',
      },
      {
        to: '/director-expenses',
        icon: '₽',
        title: 'Расходы учредителей',
        description: 'Отдельные расходы директоров',
        gradient: 'from-amber-500 to-orange-500',
        directorOnly: true,
      },
      {
        to: '/cost-calculation',
        icon: '∑',
        title: 'Себестоимость',
        description: 'Затраты по проектам',
        gradient: 'from-pink-500 to-rose-500',
        permission: 'cost_calculation',
        action: 'view',
      },
    ],
  },
  {
    label: 'Штат',
    hint: 'Команда, рабочее время и права',
    cards: [
      {
        to: '/employees',
        icon: '◎',
        title: 'Сотрудники',
        description: 'Профили и данные команды',
        gradient: 'from-emerald-500 to-teal-500',
        permission: 'employees',
        action: 'view',
      },
      {
        to: '/hours-calendar',
        icon: '◷',
        title: 'Часы',
        description: 'Часы по сотрудникам и проектам',
        gradient: 'from-cyan-500 to-blue-500',
      },
      {
        to: '/positions',
        icon: '◇',
        title: 'Должности',
        description: 'Организационная структура',
        gradient: 'from-violet-500 to-fuchsia-500',
        permission: 'employees',
        action: 'view',
      },
      {
        to: '/admin',
        icon: '◆',
        title: 'Доступы',
        description: 'Роли и разрешения',
        gradient: 'from-rose-500 to-red-500',
        permission: 'permissions',
        action: 'view',
      },
    ],
  },
  {
    label: 'Настройки',
    hint: 'Личные данные и каналы уведомлений',
    cards: [
      {
        to: '/profile',
        icon: '○',
        title: 'Профиль',
        description: 'Личные данные и учётная запись',
        gradient: 'from-slate-500 to-slate-700',
      },
      {
        to: '/notification-settings',
        icon: '◉',
        title: 'Уведомления',
        description: 'Каналы и правила доставки',
        gradient: 'from-amber-500 to-orange-500',
        permission: 'notifications',
        action: 'view',
      },
    ],
  },
];

export function ManagementPage() {
  const navigate = useNavigate();
  const { can, loading } = usePermissions();
  const storedUser = localStorage.getItem('proles_user');
  const isDirector = storedUser ? JSON.parse(storedUser).role?.trim().toLowerCase() === 'director' : false;
  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <div className="animate-spin text-3xl">◌</div>
      </div>
    );
  }

  const visibleSections = SECTIONS
    .map(section => ({
      ...section,
      cards: section.cards.filter(card => {
        if (card.directorOnly && !isDirector) return false;
        return card.permission ? can(card.permission, card.action || 'view') : true;
      }),
    }))
    .filter(section => section.cards.length > 0);

  return (
    <div className="space-y-5 max-w-7xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">
          Управление
        </h1>

      </div>

      {visibleSections.length === 0 ? (
        <div className="card p-10 text-center border-dashed border-2 border-slate-200 dark:border-slate-700">
          <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">
            Нет доступных разделов
          </h3>

          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            Обратитесь к администратору
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {visibleSections.map(section => (
            <section key={section.label} className="card p-4">
              <div className="mb-3">
                <h2 className="text-xs font-bold text-slate-900 dark:text-slate-100 uppercase tracking-wide">
                  {section.label}
                </h2>

                <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">
                  {section.hint}
                </p>
              </div>

              <div className="space-y-2">
                {section.cards.map(card => (
                  <button
                    key={`${card.to}-${card.title}`}
                    type="button"
                    onClick={() => navigate(card.to)}
                    className="group relative overflow-hidden rounded-xl w-full text-left transition-all duration-200 hover:shadow-md hover:scale-[1.01] active:scale-[0.99]"
                  >
                    <div
                      className={`absolute inset-0 bg-gradient-to-br ${card.gradient} opacity-0 group-hover:opacity-10 dark:group-hover:opacity-20 transition-opacity duration-200`}
                    />

                    <div className="relative z-10 flex items-center gap-3 p-3">
                      <div
                        className={`w-10 h-10 rounded-lg bg-gradient-to-br ${card.gradient} flex items-center justify-center text-xl text-white shadow-sm flex-shrink-0 group-hover:scale-110 transition-transform duration-200`}
                      >
                        {card.icon}
                      </div>

                      <div className="min-w-0 flex-1">
                        <h3 className="text-sm font-bold text-slate-900 dark:text-slate-100 group-hover:text-indigo-600 dark:group-hover:text-indigo-400 transition-colors">
                          {card.title}
                        </h3>

                        <p className="text-[11px] text-slate-500 dark:text-slate-400 leading-tight">
                          {card.description}
                        </p>
                      </div>

                      <svg
                        className="w-4 h-4 text-slate-400 group-hover:text-indigo-600 group-hover:translate-x-1 transition-all flex-shrink-0"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M9 5l7 7-7 7"
                        />
                      </svg>
                    </div>
                  </button>
                ))}
              </div>
            </section>
          ))}
        </div>
      )}
    </div>
  );
}