import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDashboardData } from '../hooks/useDashboardData';
import { usePermissions } from '../hooks/usePermissions';
import type { UserDto } from '../types';

// ═══════════════════════════════════════════════════════
// 🔧 Настраиваемые быстрые действия
// ═══════════════════════════════════════════════════════
interface QuickAction {
  id: string;
  label: string;
  desc: string;
  icon: string;
  route: string;
  permission?: string;       // permission key для проверки
  action?: 'create' | 'view'; // какое действие проверяем
  gradient: string;
}

const ALL_QUICK_ACTIONS: QuickAction[] = [
  { id: 'add_hours', label: 'Добавить часы', desc: 'Запись времени', icon: '⏰', route: '/timesheet', permission: 'projects', action: 'create', gradient: 'from-blue-500 to-cyan-400' },
  { id: 'add_expense', label: 'Добавить расход', desc: 'Финансы проекта', icon: '💰', route: '/expenses', permission: 'expenses_all', action: 'create', gradient: 'from-orange-500 to-amber-400' },
  { id: 'add_income', label: 'Добавить доход', desc: 'Поступления', icon: '💵', route: '/incomes', permission: 'expenses_all', action: 'create', gradient: 'from-emerald-500 to-green-400' },
  { id: 'new_trip', label: 'Новая командировка', desc: 'Оформить поездку', icon: '✈️', route: '/trips', permission: 'business_trips_all', action: 'create', gradient: 'from-violet-500 to-purple-400' },
  { id: 'request_vacation', label: 'Запросить отпуск', desc: 'Заявка на отдых', icon: '🏖', route: '/vacations', gradient: 'from-teal-500 to-cyan-400' },
  { id: 'upload_ticket', label: 'Загрузить билет', desc: 'Документы', icon: '🎫', route: '/tickets', permission: 'tickets', action: 'create', gradient: 'from-pink-500 to-rose-400' },
];

const STORAGE_KEY = 'proles_quick_actions';
const DEFAULT_ACTIONS = ['add_hours', 'add_expense', 'new_trip', 'request_vacation'];

export function DashboardPage() {
  const navigate = useNavigate();
  const [user, setUser] = useState<UserDto | null>(null);
  const { can } = usePermissions();
  const { todayHours, todayEntries, activeProjectsCount, totalProjects, unreadNotifications, loading, error, refresh } = useDashboardData();

  // Настройки быстрых действий
  const [enabledIds, setEnabledIds] = useState<string[]>(DEFAULT_ACTIONS);
  const [showSettings, setShowSettings] = useState(false);

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) try { setUser(JSON.parse(stored)); } catch {}

    const savedActions = localStorage.getItem(STORAGE_KEY);
    if (savedActions) {
      try { setEnabledIds(JSON.parse(savedActions)); } catch {}
    }
  }, []);

  const toggleAction = (id: string) => {
    const next = enabledIds.includes(id)
      ? enabledIds.filter(a => a !== id)
      : [...enabledIds, id];
    setEnabledIds(next);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  };

  // Фильтруем действия: только включённые + доступные по правам
  const visibleActions = ALL_QUICK_ACTIONS.filter(a => {
    if (!enabledIds.includes(a.id)) return false;
    if (a.permission && a.action) return can(a.permission, a.action);
    return true;
  });

  const formatHours = (h: number) => {
    const hrs = Math.floor(h);
    const mins = Math.round((h - hrs) * 60);
    return mins > 0 ? `${hrs}ч ${mins}м` : `${hrs}ч`;
  };

  if (loading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;

  return (
    <div className="space-y-6 max-w-5xl mx-auto">
      {/* Welcome Banner */}
      <div className="relative overflow-hidden rounded-2xl bg-gradient-to-r from-indigo-600 to-violet-600 p-6 md:p-8 text-white shadow-xl shadow-indigo-200/50 dark:shadow-none">
        <div className="absolute top-0 right-0 -mt-4 -mr-4 w-32 h-32 bg-white/10 rounded-full blur-2xl"></div>
        <div className="relative z-10">
          <h1 className="text-2xl md:text-3xl font-bold mb-2">
            {user?.firstName ? `Добрый день, ${user.firstName}! 👋` : 'Добро пожаловать!'}
          </h1>
          <p className="text-indigo-100 text-sm md:text-base capitalize">
            {new Date().toLocaleDateString('ru-RU', { weekday: 'long', day: 'numeric', month: 'long' })}
          </p>
        </div>
      </div>

      {error && (
        <div className="p-4 rounded-xl bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 text-red-700 dark:text-red-400 text-sm flex items-center justify-between animate-fade-in">
          <span>❌ {error}</span>
          <button onClick={refresh} className="text-xs font-bold underline hover:text-red-900">Повторить</button>
        </div>
      )}

      {/* KPI Cards (без расходов) */}
      <div className="grid grid-cols-2 md:grid-cols-3 gap-3 md:gap-4">
        {/* Часы сегодня */}
        <div className="card p-4 md:p-5 cursor-default">
          <div className="flex items-center gap-2 mb-3">
            <span className="text-xl">⏱</span>
            <span className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">Часы сегодня</span>
          </div>
          <div className="text-2xl md:text-3xl font-bold text-slate-900 dark:text-slate-100">
            {todayHours > 0 ? formatHours(todayHours) : '—'}
          </div>
          {todayEntries.length > 0 && (
            <div className="text-[10px] text-slate-400 mt-1 truncate">{todayEntries.length} зап.</div>
          )}
        </div>

        {/* Активные проекты (кликабельная!) */}
        <div
          onClick={() => navigate('/projects')}
          className="card p-4 md:p-5 cursor-pointer hover:border-indigo-300 dark:hover:border-indigo-700 hover:shadow-md transition-all group"
        >
          <div className="flex items-center gap-2 mb-3">
            <span className="text-xl">📁</span>
            <span className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">Проекты</span>
          </div>
          <div className="text-2xl md:text-3xl font-bold text-slate-900 dark:text-slate-100 group-hover:text-indigo-600 dark:group-hover:text-indigo-400 transition-colors">
            {activeProjectsCount}
          </div>
          <div className="text-[10px] text-slate-400 mt-1">из {totalProjects} активных →</div>
        </div>

        {/* Уведомления */}
        <div
          onClick={() => navigate('/notifications')}
          className="card p-4 md:p-5 cursor-pointer hover:border-indigo-300 dark:hover:border-indigo-700 hover:shadow-md transition-all relative"
        >
          <div className="flex items-center gap-2 mb-3">
            <span className="text-xl">🔔</span>
            <span className="text-xs font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wide">Уведомления</span>
          </div>
          <div className="text-2xl md:text-3xl font-bold text-slate-900 dark:text-slate-100">{unreadNotifications}</div>
          <div className="text-[10px] text-slate-400 mt-1">непрочитанных</div>
          {unreadNotifications > 0 && (
            <div className="absolute top-3 right-3 w-3 h-3 bg-red-500 rounded-full animate-pulse" />
          )}
        </div>
      </div>

      {/* Быстрые действия */}
      <section>
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-bold text-slate-900 dark:text-slate-100 flex items-center gap-2">
            <span className="w-1 h-4 bg-indigo-500 rounded-full"></span>
            Быстрые действия
          </h3>
          <button
            onClick={() => setShowSettings(!showSettings)}
            className="btn-ghost px-2 py-1 text-xs text-slate-500 dark:text-slate-400"
          >
            ⚙️ Настроить
          </button>
        </div>

        {/* Панель настройки */}
        {showSettings && (
          <div className="card p-4 mb-4 border-indigo-200 dark:border-indigo-900 bg-indigo-50/30 dark:bg-indigo-950/20 animate-fade-in">
            <div className="text-xs font-medium text-slate-600 dark:text-slate-400 mb-3">Выберите кнопки для быстрого доступа:</div>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
              {ALL_QUICK_ACTIONS.map(action => {
                const isAvailable = !action.permission || !action.action || can(action.permission, action.action);
                const isEnabled = enabledIds.includes(action.id);
                return (
                  <label
                    key={action.id}
                    className={`flex items-center gap-3 p-2.5 rounded-lg cursor-pointer transition-all text-sm ${
                      !isAvailable ? 'opacity-40 cursor-not-allowed' :
                      isEnabled ? 'bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300' :
                      'hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-600 dark:text-slate-400'
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={isEnabled}
                      disabled={!isAvailable}
                      onChange={() => toggleAction(action.id)}
                      className="rounded accent-indigo-600"
                    />
                    <span className="text-lg">{action.icon}</span>
                    <div className="min-w-0">
                      <div className="font-medium truncate">{action.label}</div>
                      {!isAvailable && <div className="text-[10px] text-red-500">Нет прав</div>}
                    </div>
                  </label>
                );
              })}
            </div>
            <div className="mt-3 flex justify-end">
              <button onClick={() => setShowSettings(false)} className="btn-primary px-4 py-1.5 text-xs">Готово</button>
            </div>
          </div>
        )}

        {/* Сетка кнопок */}
        {visibleActions.length === 0 ? (
          <div className="card p-8 text-center text-slate-400 dark:text-slate-500 text-sm border-dashed">
            Нет доступных действий. Нажмите ⚙️ чтобы настроить.
          </div>
        ) : (
          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
            {visibleActions.map(action => (
              <button
                key={action.id}
                onClick={() => navigate(action.route)}
                className="group relative overflow-hidden rounded-xl p-4 text-left transition-all hover:shadow-lg hover:-translate-y-0.5 active:scale-[0.99] card"
              >
                <div className={`absolute inset-0 bg-gradient-to-br ${action.gradient} opacity-0 group-hover:opacity-10 dark:group-hover:opacity-20 transition-opacity`} />
                <div className="relative z-10">
                  <div className="text-2xl mb-2">{action.icon}</div>
                  <div className="font-bold text-slate-900 dark:text-slate-100 text-sm">{action.label}</div>
                  <div className="text-xs text-slate-500 dark:text-slate-400">{action.desc}</div>
                </div>
              </button>
            ))}
          </div>
        )}
      </section>

      {/* Записи за сегодня */}
      {todayEntries.length > 0 && (
        <section>
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-sm font-bold text-slate-900 dark:text-slate-100 flex items-center gap-2">
              <span className="w-1 h-4 bg-indigo-500 rounded-full"></span>
              Записи за сегодня
            </h3>
            <button onClick={() => navigate('/timesheet')} className="text-xs text-indigo-600 dark:text-indigo-400 font-medium hover:underline">Все записи →</button>
          </div>
          <div className="space-y-2">
            {todayEntries.map(entry => (
              <div key={entry.id} className="card p-4 flex items-center justify-between group hover:border-indigo-200 dark:hover:border-indigo-800">
                <div className="min-w-0 flex-1">
                  <div className="font-semibold text-slate-900 dark:text-slate-100 truncate text-sm">{entry.projectName || 'Без проекта'}</div>
                  {entry.comment && <div className="text-xs text-slate-500 dark:text-slate-400 truncate mt-0.5">{entry.comment}</div>}
                </div>
                <div className="flex-shrink-0 ml-4 px-3 py-1.5 bg-indigo-50 dark:bg-indigo-950/30 text-indigo-700 dark:text-indigo-400 rounded-lg text-sm font-bold tabular-nums group-hover:bg-indigo-100 dark:group-hover:bg-indigo-900/40 transition-colors">
                  {formatHours(entry.hours)}
                </div>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}