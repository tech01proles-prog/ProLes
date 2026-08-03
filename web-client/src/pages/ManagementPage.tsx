import { useNavigate } from 'react-router-dom';
import { usePermissions } from '../hooks/usePermissions';

interface ManagementCard {
  to: string;
  icon: string;
  title: string;
  description: string;
  gradient: string;
  permission: string;
  action: 'view' | 'create' | 'edit' | 'delete';
}

interface ManagementSection {
  label: string;
  hint: string;
  cards: ManagementCard[];
}

const SECTIONS: ManagementSection[] = [
  {
    label: 'Проекты и финансы',
    hint: 'Управление проектами, сотрудниками и расчётами',
    cards: [
      { to: '/projects', icon: '📁', title: 'Проекты', description: 'Реестр проектов и статусы', gradient: 'from-blue-500 to-indigo-500', permission: 'projects', action: 'view' },
      { to: '/employees', icon: '👥', title: 'Сотрудники', description: 'Профили и данные команды', gradient: 'from-emerald-500 to-teal-500', permission: 'employees', action: 'view' },
      { to: '/cost-calculation', icon: '🧮', title: 'Себестоимость', description: 'Расчёт маржи по проектам', gradient: 'from-pink-500 to-rose-500', permission: 'cost_calculation', action: 'view' },
    ],
  },
  {
    label: 'Операционная деятельность',
    hint: 'Поездки, график работы и документы',
    cards: [
      { to: '/trips', icon: '✈️', title: 'Командировки', description: 'Оформление поездок', gradient: 'from-violet-500 to-purple-500', permission: 'business_trips_all', action: 'view' },
      { to: '/hours-calendar', icon: '📅', title: 'Часы', description: 'Календарь рабочего времени', gradient: 'from-cyan-500 to-blue-500', permission: 'projects', action: 'view' },
      { to: '/tickets', icon: '🎫', title: 'Билеты', description: 'Документы и чеки', gradient: 'from-orange-500 to-amber-500', permission: 'tickets', action: 'view' },
    ],
  },
  {
    label: 'Финансы и аналитика',
    hint: 'Зарплаты, доходы и эффективность',
    cards: [
      { to: '/payroll', icon: '💰', title: 'Зарплата', description: 'Компоненты и расчёты', gradient: 'from-emerald-500 to-green-500', permission: 'payroll', action: 'view' },
      { to: '/analytics', icon: '📊', title: 'Аналитика', description: 'Эффективность команды', gradient: 'from-indigo-500 to-violet-500', permission: 'analytics', action: 'view' },
    ],
  },
  {
    label: 'Система',
    hint: 'Доступы и уведомления',
    cards: [
      { to: '/admin', icon: '🔐', title: 'Доступы', description: 'Роли и права', gradient: 'from-rose-500 to-red-500', permission: 'permissions', action: 'view' },
      { to: '/notification-settings', icon: '🔔', title: 'Уведомления', description: 'Push и email', gradient: 'from-slate-500 to-slate-600', permission: 'notifications', action: 'view' },
    ],
  },
];

export function ManagementPage() {
  const navigate = useNavigate();
  const { can, loading } = usePermissions();

  if (loading) {
    return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;
  }

  const visibleSections = SECTIONS
    .map(section => ({ ...section, cards: section.cards.filter(c => can(c.permission, c.action)) }))
    .filter(section => section.cards.length > 0);

  return (
    <div className="space-y-5 max-w-7xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100 flex items-center gap-3">
          <span className="w-9 h-9 rounded-xl bg-gradient-to-br from-indigo-500 to-purple-500 flex items-center justify-center text-white text-lg shadow-md shadow-indigo-200 dark:shadow-indigo-900">⚙️</span>
          Управление
        </h1>
        <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
          Административные инструменты
        </p>
      </div>

      {visibleSections.length === 0 ? (
        <div className="card p-10 text-center border-dashed border-2 border-slate-200 dark:border-slate-700">
          <div className="text-5xl mb-3 opacity-50">🔒</div>
          <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Нет доступных разделов</h3>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">Обратитесь к администратору</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {visibleSections.map(section => (
            <section key={section.label} className="card p-4">
              <div className="mb-3">
                <h2 className="text-xs font-bold text-slate-900 dark:text-slate-100 uppercase tracking-wide">{section.label}</h2>
                <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">{section.hint}</p>
              </div>
              <div className="space-y-2">
                {section.cards.map(card => (
                  <button
                    key={card.to}
                    onClick={() => navigate(card.to)}
                    className="group relative overflow-hidden rounded-xl w-full text-left transition-all duration-200 hover:shadow-md hover:scale-[1.01] active:scale-[0.99]"
                  >
                    <div className={`absolute inset-0 bg-gradient-to-br ${card.gradient} opacity-0 group-hover:opacity-10 dark:group-hover:opacity-20 transition-opacity duration-200`} />
                    <div className="relative z-10 flex items-center gap-3 p-3">
                      <div className={`w-10 h-10 rounded-lg bg-gradient-to-br ${card.gradient} flex items-center justify-center text-xl shadow-sm flex-shrink-0 group-hover:scale-110 transition-transform duration-200`}>
                        {card.icon}
                      </div>
                      <div className="min-w-0 flex-1">
                        <h3 className="text-sm font-bold text-slate-900 dark:text-slate-100 group-hover:text-indigo-600 dark:group-hover:text-indigo-400 transition-colors">
                          {card.title}
                        </h3>
                        <p className="text-[11px] text-slate-500 dark:text-slate-400 leading-tight">{card.description}</p>
                      </div>
                      <svg className="w-4 h-4 text-slate-400 group-hover:text-indigo-600 dark:group-hover:text-indigo-400 group-hover:translate-x-1 transition-all flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
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