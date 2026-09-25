import { useEffect, useMemo, useState } from 'react';
import {
  NavLink,
  Outlet,
  useLocation,
  useNavigate,
} from 'react-router-dom';
import api from '../api/client';
import type { NotificationDto, UserDto } from '../types';
import {
  NAVIGATION_ITEMS,
  type NavigationItem,
} from '../config/navigation';
import { usePermissions } from '../hooks/usePermissions';
import { NotificationBell } from './NotificationBell';
import { PageHeader } from './PageHeader';
import { ThemeToggle } from './ThemeToggle';

const SIDEBAR_STORAGE_KEY = 'proles_sidebar_expanded';

function getInitials(user: UserDto | null) {
  const source = user?.name || user?.firstName || user?.login || '?';

  return source
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('');
}

export function Layout() {
  const { can, loading: permissionsLoading } = usePermissions();
  const navigate = useNavigate();
  const location = useLocation();

  const [user, setUser] = useState<UserDto | null>(null);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [unreadCount, setUnreadCount] = useState(0);
  const [sidebarExpanded, setSidebarExpanded] = useState(() => {
    return localStorage.getItem(SIDEBAR_STORAGE_KEY) !== 'false';
  });

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');

    if (!stored) return;

    try {
      setUser(JSON.parse(stored));
    } catch {
      localStorage.removeItem('proles_user');
    }
  }, []);

  useEffect(() => {
    let active = true;

    const loadUnread = async () => {
      try {
        const { data } = await api.get<NotificationDto[]>('/notifications');

        if (active) {
          setUnreadCount(data.filter((notification) => !notification.isRead).length);
        }
      } catch {
        // Сбой уведомлений не должен блокировать оболочку приложения.
      }
    };

    void loadUnread();
    const interval = window.setInterval(loadUnread, 30_000);

    return () => {
      active = false;
      window.clearInterval(interval);
    };
  }, []);

  useEffect(() => {
    setMobileMenuOpen(false);
  }, [location.pathname]);

  const hasManagement =
    can('projects', 'view') ||
    can('dayoffs_all', 'view') ||
    can('payroll', 'view') ||
    can('analytics', 'view') ||
    can('permissions', 'view');

  const navigation = useMemo(() => {
    return NAVIGATION_ITEMS.filter((item) => {
      if (item.section === 'management') return hasManagement;
      if (!item.permission || !item.action) return true;

      const timesheetOverride =
        item.to === '/timesheet' && user?.login === 'a.ermashkevich';

      return (
        timesheetOverride ||
        (!permissionsLoading && can(item.permission, item.action))
      );
    });
  }, [can, hasManagement, permissionsLoading, user?.login]);

  const workspaceItems = navigation.filter(
    (item) => item.section === 'workspace',
  );
  const managementItems = navigation.filter(
    (item) => item.section === 'management',
  );

  const toggleSidebar = () => {
    setSidebarExpanded((current) => {
      const next = !current;
      localStorage.setItem(SIDEBAR_STORAGE_KEY, String(next));
      return next;
    });
  };

  const handleLogout = () => {
    localStorage.removeItem('proles_token');
    localStorage.removeItem('proles_user');
    navigate('/login');
  };

  const renderNavItem = (
    item: NavigationItem,
    options: { compact?: boolean; mobile?: boolean } = {},
  ) => {
    const { compact = false, mobile = false } = options;
    const showNotificationCount =
      item.to === '/notifications' && unreadCount > 0;

    return (
      <NavLink
        key={item.to}
        to={item.to}
        end={item.to === '/'}
        title={compact ? item.label : undefined}
        className={({ isActive }) => [
          'group relative flex min-h-11 items-center rounded-xl font-medium transition-all',
          compact ? 'justify-center px-2' : 'gap-3 px-3',
          mobile ? 'py-3 text-base' : 'py-2.5 text-sm',
          isActive
            ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-500/20'
            : 'text-slate-600 hover:bg-slate-100 hover:text-slate-950 dark:text-slate-400 dark:hover:bg-slate-800 dark:hover:text-white',
        ].join(' ')}
      >
        <span
          className={[
            'flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-base',
            'bg-slate-100 group-hover:bg-white dark:bg-slate-800 dark:group-hover:bg-slate-700',
            'group-[.active]:bg-white/15',
          ].join(' ')}
          aria-hidden="true"
        >
          {item.icon}
        </span>

        {!compact && (
          <span className="min-w-0 flex-1">
            <span className="block truncate">{item.label}</span>
            {mobile && (
              <span className="mt-0.5 block truncate text-xs font-normal opacity-70">
                {item.description}
              </span>
            )}
          </span>
        )}

        {showNotificationCount && (
          <span
            className={[
              'flex h-5 min-w-5 items-center justify-center rounded-full px-1.5',
              'bg-rose-500 text-[10px] font-bold text-white',
              compact ? 'absolute -right-1 -top-1' : '',
            ].join(' ')}
          >
            {unreadCount > 99 ? '99+' : unreadCount}
          </span>
        )}
      </NavLink>
    );
  };

  const renderNavigation = (mobile = false) => (
    <nav className={mobile ? 'space-y-6 p-4' : 'space-y-6 p-3'}>
      <section>
        {!sidebarExpanded || mobile ? (
          mobile && (
            <div className="mb-2 px-3 text-[10px] font-bold uppercase tracking-[0.16em] text-slate-400">
              Рабочее пространство
            </div>
          )
        ) : (
          <div className="mb-2 px-3 text-[10px] font-bold uppercase tracking-[0.16em] text-slate-400">
            Рабочее пространство
          </div>
        )}

        <div className="space-y-1">
          {workspaceItems.map((item) =>
            renderNavItem(item, {
              compact: !mobile && !sidebarExpanded,
              mobile,
            }),
          )}
        </div>
      </section>

      {managementItems.length > 0 && (
        <section>
          {(sidebarExpanded || mobile) && (
            <div className="mb-2 px-3 text-[10px] font-bold uppercase tracking-[0.16em] text-slate-400">
              Компания
            </div>
          )}

          <div className="space-y-1">
            {managementItems.map((item) =>
              renderNavItem(item, {
                compact: !mobile && !sidebarExpanded,
                mobile,
              }),
            )}
          </div>
        </section>
      )}
    </nav>
  );

  return (
    <div className="min-h-dvh bg-slate-50 text-slate-950 dark:bg-slate-950 dark:text-slate-100">
      <header className="fixed inset-x-0 top-0 z-50 flex h-[calc(4rem+var(--safe-area-inset-top))] items-center border-b border-slate-200/80 bg-white/90 px-4 pt-[var(--safe-area-inset-top)] backdrop-blur-xl dark:border-slate-800 dark:bg-slate-900/90">
        <div className="flex w-full items-center justify-between">
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => setMobileMenuOpen((current) => !current)}
              className="flex h-10 w-10 items-center justify-center rounded-xl text-slate-600 transition-colors hover:bg-slate-100 md:hidden dark:text-slate-300 dark:hover:bg-slate-800"
              aria-label={mobileMenuOpen ? 'Закрыть меню' : 'Открыть меню'}
            >
              {mobileMenuOpen ? '×' : '☰'}
            </button>

            <button
              type="button"
              onClick={toggleSidebar}
              className="hidden h-10 w-10 items-center justify-center rounded-xl text-slate-600 transition-colors hover:bg-slate-100 md:flex dark:text-slate-300 dark:hover:bg-slate-800"
              aria-label={
                sidebarExpanded ? 'Свернуть меню' : 'Развернуть меню'
              }
            >
              <span className="text-lg">{sidebarExpanded ? '‹' : '›'}</span>
            </button>

            <NavLink to="/" className="flex items-center gap-3">
              <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-violet-700 text-sm font-black text-white shadow-lg shadow-indigo-500/20">
                PL
              </div>

              <div className="hidden leading-tight sm:block">
                <div className="font-bold tracking-tight text-slate-950 dark:text-white">
                  ProLes
                </div>
                <div className="text-[10px] font-medium uppercase tracking-[0.14em] text-slate-400">
                  Workspace
                </div>
              </div>
            </NavLink>
          </div>

          <div className="flex items-center gap-1 sm:gap-2">
            <NotificationBell />
            <ThemeToggle />

            <NavLink
              to="/profile"
              className="ml-1 flex items-center gap-2 rounded-xl p-1.5 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800"
            >
              <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-slate-700 to-slate-950 text-xs font-bold text-white dark:from-indigo-500 dark:to-violet-700">
                {getInitials(user)}
              </div>
              <div className="hidden max-w-40 text-left lg:block">
                <div className="truncate text-xs font-semibold text-slate-800 dark:text-slate-100">
                  {user?.name || user?.login || 'Пользователь'}
                </div>
                <div className="truncate text-[10px] capitalize text-slate-400">
                  {user?.role || 'Сотрудник'}
                </div>
              </div>
            </NavLink>
          </div>
        </div>
      </header>

      <aside
        className={[
          'fixed bottom-0 left-0 top-[calc(4rem+var(--safe-area-inset-top))] z-40 hidden flex-col border-r border-slate-200 bg-white transition-[width] duration-300 md:flex dark:border-slate-800 dark:bg-slate-900',
          sidebarExpanded ? 'w-64' : 'w-20',
        ].join(' ')}
      >
        <div className="flex-1 overflow-y-auto">
          {renderNavigation()}
        </div>

        <div className="border-t border-slate-200 p-3 dark:border-slate-800">
          {sidebarExpanded && (
            <NavLink
              to="/profile"
              className="mb-2 flex items-center gap-3 rounded-xl p-2 transition-colors hover:bg-slate-100 dark:hover:bg-slate-800"
            >
              <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-indigo-100 text-xs font-bold text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">
                {getInitials(user)}
              </div>
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-semibold">
                  {user?.name || user?.login || 'Пользователь'}
                </div>
                <div className="truncate text-xs capitalize text-slate-400">
                  {user?.role || 'Сотрудник'}
                </div>
              </div>
            </NavLink>
          )}

          <button
            type="button"
            onClick={handleLogout}
            title={!sidebarExpanded ? 'Выйти' : undefined}
            className={[
              'flex min-h-10 w-full items-center rounded-xl text-sm font-medium text-rose-600 transition-colors hover:bg-rose-50 dark:text-rose-400 dark:hover:bg-rose-950/30',
              sidebarExpanded ? 'gap-3 px-3' : 'justify-center',
            ].join(' ')}
          >
            <span aria-hidden="true">↪</span>
            {sidebarExpanded && <span>Выйти из системы</span>}
          </button>
        </div>
      </aside>

      {mobileMenuOpen && (
        <>
          <button
            type="button"
            aria-label="Закрыть меню"
            onClick={() => setMobileMenuOpen(false)}
            className="fixed inset-0 top-[calc(4rem+var(--safe-area-inset-top))] z-30 bg-slate-950/40 backdrop-blur-sm md:hidden"
          />

          <aside className="fixed bottom-0 left-0 top-[calc(4rem+var(--safe-area-inset-top))] z-40 flex w-[min(22rem,88vw)] flex-col bg-white shadow-2xl md:hidden dark:bg-slate-900">
            <div className="flex-1 overflow-y-auto">
              {renderNavigation(true)}
            </div>

            <div className="border-t border-slate-200 p-4 pb-[max(1rem,var(--safe-area-inset-bottom))] dark:border-slate-800">
              <button
                type="button"
                onClick={handleLogout}
                className="flex w-full items-center justify-center gap-2 rounded-xl bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-600 dark:bg-rose-950/30 dark:text-rose-400"
              >
                <span aria-hidden="true">↪</span>
                Выйти из системы
              </button>
            </div>
          </aside>
        </>
      )}

      <main
        className={[
          'min-h-dvh pt-[calc(4rem+var(--safe-area-inset-top))] transition-[margin] duration-300',
          sidebarExpanded ? 'md:ml-64' : 'md:ml-20',
        ].join(' ')}
      >
        <div className="mx-auto w-full max-w-[1600px] px-4 py-6 sm:px-6 md:px-8 md:py-8">
          <PageHeader />
          <div className="animate-fade-in">
            <Outlet />
          </div>
        </div>
      </main>
    </div>
  );
}