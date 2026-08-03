import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useState, useEffect } from 'react';
import api from '../api/client';
import type { UserDto, NotificationDto } from '../types';
import { ThemeToggle } from './ThemeToggle';
import { NotificationBell } from './NotificationBell';
import { usePermissions } from '../hooks/usePermissions';

export function Layout() {
  const { can } = usePermissions();
  const navigate = useNavigate();
  const [user, setUser] = useState<UserDto | null>(null);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [unreadCount, setUnreadCount] = useState(0);
  const [sidebarOpen, setSidebarOpen] = useState(true);

  useEffect(() => {
    const checkScreenSize = () => setSidebarOpen(window.innerWidth >= 768);
    checkScreenSize();
    window.addEventListener('resize', checkScreenSize);
    return () => window.removeEventListener('resize', checkScreenSize);
  }, []);

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) try { setUser(JSON.parse(stored)); } catch {}
  }, []);

  useEffect(() => {
    let interval: any;
    const loadUnread = async () => {
      try {
        const { data } = await api.get<NotificationDto[]>('/notifications');
        setUnreadCount(data.filter(n => !n.isRead).length);
      } catch {}
    };
    loadUnread();
    interval = setInterval(loadUnread, 30000);
    return () => clearInterval(interval);
  }, []);

  const handleLogout = () => {
    localStorage.removeItem('proles_token');
    localStorage.removeItem('proles_user');
    navigate('/login');
  };

  // 🎯 Базовый набор — виден ВСЕМ (контент адаптируется под права внутри экранов)
  const MAIN_ITEMS = [
    { to: '/', icon: '🏠', label: 'Главная' },
    { to: '/timesheet', icon: '⏱', label: 'Табель' },
    { to: '/trips', icon: '✈️', label: 'Командировки' },
    { to: '/incomes', icon: '💵', label: 'Доходы' },
    { to: '/expenses', icon: '💸', label: 'Расходы' },
    { to: '/tickets', icon: '🎫', label: 'Билеты' },
    { to: '/vacations', icon: '🏖', label: 'Отпуск' },
    { to: '/notifications', icon: '🔔', label: 'Уведомления' },
  ];

  // 🔐 «Управление» — показываем, если есть хоть одно админское право
  const hasManagement =
    can('projects', 'view') ||
    can('dayoffs_all', 'view') ||
    can('payroll', 'view') ||
    can('analytics', 'view') ||
    can('permissions', 'view');

  const renderNavItem = (item: { to: string; icon: string; label: string }, mobile = false) => (
    <NavLink
      key={item.to}
      to={item.to}
      end={item.to === '/'}
      onClick={() => mobile && setMobileMenuOpen(false)}
      className={({ isActive }) =>
        mobile
          ? `flex items-center justify-between gap-3 px-4 py-3 rounded-xl text-base font-medium ${isActive ? 'bg-indigo-50 dark:bg-indigo-950/40 text-indigo-700 dark:text-indigo-300' : 'text-slate-600 dark:text-slate-400'}`
          : `flex items-center justify-between gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-all ${isActive ? 'bg-indigo-50 dark:bg-indigo-950/40 text-indigo-700 dark:text-indigo-300 shadow-sm ring-1 ring-indigo-100 dark:ring-indigo-900' : 'text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800 hover:text-slate-900 dark:hover:text-slate-100'}`
      }
    >
      <span className="flex items-center gap-3">
        <span className={mobile ? 'text-xl' : 'text-lg w-6 text-center'}>{item.icon}</span>
        {item.label}
      </span>
      {item.to === '/notifications' && unreadCount > 0 && (
        <span className="min-w-[20px] h-5 px-1.5 rounded-full bg-red-500 text-white text-[10px] font-bold flex items-center justify-center">
          {unreadCount > 99 ? '99+' : unreadCount}
        </span>
      )}
    </NavLink>
  );

  const sectionDivider = (label: string) => (
    <div className="flex items-center gap-2 px-3 mb-2">
      <div className="h-px flex-1 bg-slate-200 dark:bg-slate-800"></div>
      <span className="text-[10px] font-bold text-slate-400 dark:text-slate-500 uppercase tracking-wider">{label}</span>
      <div className="h-px flex-1 bg-slate-200 dark:bg-slate-800"></div>
    </div>
  );

  return (
    <div className="min-h-dvh bg-slate-50 dark:bg-slate-950 flex">
      {/* Desktop Sidebar */}
      <aside className={`hidden md:flex flex-col bg-white dark:bg-slate-900 border-r border-slate-200 dark:border-slate-800 fixed top-14 h-[calc(100vh-3.5rem)] z-30 transition-all duration-300 ${sidebarOpen ? 'w-64' : 'w-0 overflow-hidden'}`}>
        <nav className="flex-1 p-4 space-y-1 overflow-y-auto">
          <div className="mb-3">
            <div className="text-[10px] font-bold text-slate-400 dark:text-slate-500 uppercase tracking-wider px-3 mb-2">Основное</div>
            <div className="space-y-0.5">
              {MAIN_ITEMS.map(item => renderNavItem(item))}
            </div>
          </div>

          {hasManagement && (
            <div className="mb-3">
              {sectionDivider('Администрирование')}
              <div className="space-y-0.5">
                {renderNavItem({ to: '/management', icon: '⚙️', label: 'Управление' })}
              </div>
            </div>
          )}
        </nav>

        <div className="p-4 border-t border-slate-100 dark:border-slate-800 space-y-1">
          <NavLink to="/profile" className="flex items-center gap-3 px-2 py-2 hover:bg-slate-50 dark:hover:bg-slate-800 rounded-lg transition-colors">
            <div className="w-8 h-8 rounded-full bg-gradient-to-br from-indigo-400 to-purple-500 flex items-center justify-center text-white text-xs font-bold shadow-sm">
              {user?.name?.charAt(0) || '?'}
            </div>
            <div className="min-w-0 flex-1">
              <div className="text-sm font-medium text-slate-900 dark:text-slate-100 truncate">{user?.name}</div>
              <div className="text-xs text-slate-500 dark:text-slate-400 truncate capitalize">{user?.role}</div>
            </div>
          </NavLink>
          <ThemeToggle />
          <button onClick={handleLogout} className="w-full btn-ghost text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/40 hover:text-red-700 justify-start gap-2 text-xs">
            🚪 Выйти из системы
          </button>
        </div>
      </aside>

      {/* Mobile Header */}
      <div className="md:hidden fixed top-0 left-0 right-0 bg-white/80 dark:bg-slate-900/80 backdrop-blur-md border-b border-slate-200 dark:border-slate-800 z-40 px-4 h-14 flex items-center justify-between">
        <NavLink to="/" className="flex items-center gap-2 hover:opacity-80 active:scale-95 transition-all cursor-pointer">
          <div className="w-7 h-7 bg-indigo-600 rounded-md flex items-center justify-center text-white font-bold text-sm">P</div>
          <span className="font-bold text-slate-900 dark:text-slate-100">Proles Sys</span>
        </NavLink>
        <div className="flex items-center gap-2">
          <NotificationBell />
          <ThemeToggle />
          <button onClick={() => setMobileMenuOpen(!mobileMenuOpen)} className="p-2 text-slate-600 dark:text-slate-400 relative">
            {mobileMenuOpen ? '✕' : '☰'}
            {!mobileMenuOpen && unreadCount > 0 && (
              <span className="absolute top-1 right-1 w-2 h-2 rounded-full bg-red-500"></span>
            )}
          </button>
        </div>
      </div>

      {/* Desktop Header */}
      <div className="hidden md:flex fixed top-0 left-0 right-0 bg-white/80 dark:bg-slate-900/80 backdrop-blur-md border-b border-slate-200 dark:border-slate-800 z-30 h-14 items-center justify-between px-4">
        <div className="flex items-center gap-4">
          <button
            onClick={() => setSidebarOpen(!sidebarOpen)}
            className="p-2 text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-lg transition-colors"
            title={sidebarOpen ? 'Скрыть меню' : 'Показать меню'}
          >
            <svg className="w-5 h-5 transition-transform" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={1.8}>
              <rect x="3" y="4" width="18" height="16" rx="2" strokeLinecap="round" strokeLinejoin="round" />
              <line x1={sidebarOpen ? "9" : "15"} y1="4" x2={sidebarOpen ? "9" : "15"} y2="20" strokeLinecap="round" className="transition-all duration-300" />
            </svg>
          </button>
          <NavLink to="/" className="flex items-center gap-2 hover:opacity-80 active:scale-95 transition-all cursor-pointer">
            <div className="w-7 h-7 bg-indigo-600 rounded-md flex items-center justify-center text-white font-bold text-sm">P</div>
            <span className="font-bold text-slate-900 dark:text-slate-100">Proles Sys</span>
          </NavLink>
        </div>
        <div className="flex items-center gap-3">
          <NotificationBell />
          <ThemeToggle />
        </div>
      </div>

      {/* Mobile Menu */}
      {mobileMenuOpen && (
        <div className="md:hidden fixed inset-0 top-14 bg-white dark:bg-slate-900 z-30 animate-fade-in overflow-y-auto pb-20">
          <nav className="p-4 space-y-1">
            <div className="mb-3">
              <div className="text-[10px] font-bold text-slate-400 uppercase tracking-wider px-3 mb-2">Основное</div>
              <div className="space-y-0.5">
                {MAIN_ITEMS.map(item => renderNavItem(item, true))}
              </div>
            </div>
            {hasManagement && (
              <div className="mb-3">
                {sectionDivider('Администрирование')}
                <div className="space-y-0.5">
                  {renderNavItem({ to: '/management', icon: '⚙️', label: 'Управление' }, true)}
                </div>
              </div>
            )}
            <button onClick={handleLogout} className="w-full flex items-center gap-3 px-4 py-3 rounded-xl text-base font-medium text-red-600 mt-4">
              🚪 Выйти
            </button>
          </nav>
        </div>
      )}

      {/* Mobile Bottom Nav — адаптивный */}
      <nav className="md:hidden fixed bottom-0 left-0 right-0 bg-white dark:bg-slate-900 border-t border-slate-200 dark:border-slate-800 z-40 pb-safe">
        <div className={`flex justify-around items-center h-16 ${hasManagement ? 'px-2' : 'px-6'}`}>
          <NavLink to="/" end className={({ isActive }) => `flex flex-col items-center gap-0.5 px-2 py-1 text-[10px] font-medium transition-colors ${isActive ? 'text-indigo-600 dark:text-indigo-400' : 'text-slate-400'}`}>
            <span className="text-xl leading-none">🏠</span>
            <span>Главная</span>
          </NavLink>
          <NavLink to="/timesheet" className={({ isActive }) => `flex flex-col items-center gap-0.5 px-2 py-1 text-[10px] font-medium transition-colors ${isActive ? 'text-indigo-600 dark:text-indigo-400' : 'text-slate-400'}`}>
            <span className="text-xl leading-none">⏱</span>
            <span>Табель</span>
          </NavLink>
          <NavLink to="/profile" className={({ isActive }) => `flex flex-col items-center gap-0.5 px-2 py-1 text-[10px] font-medium transition-colors relative ${isActive ? 'text-indigo-600 dark:text-indigo-400' : 'text-slate-400'}`}>
            <span className="text-xl leading-none">👤</span>
            <span>Профиль</span>
          </NavLink>
          {hasManagement && (
            <NavLink to="/management" className={({ isActive }) => `flex flex-col items-center gap-0.5 px-2 py-1 text-[10px] font-medium transition-colors ${isActive ? 'text-indigo-600 dark:text-indigo-400' : 'text-slate-400'}`}>
              <span className="text-xl leading-none">⚙️</span>
              <span>Управление</span>
            </NavLink>
          )}
        </div>
      </nav>

      <main className={`flex-1 pt-14 pb-20 md:pb-8 min-h-dvh transition-all duration-300 ${sidebarOpen ? 'md:ml-64' : 'md:ml-0'}`}>
        <div className="max-w-7xl mx-auto px-4 py-6 md:py-8 animate-fade-in">
          <Outlet />
        </div>
      </main>
    </div>
  );
}