import { useState, useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { createPortal } from 'react-dom';
import api from '../api/client';
import type { UserDto, ExpenseDto, IncomeDto, BusinessTripDto, TimeEntryDto } from '../types';
import { generateUUID, formatMoney } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';

export function EmployeesPage() {
  const navigate = useNavigate();
  const { can, loading: permLoading } = usePermissions();
  const canView = !permLoading && can('employees', 'view');
  const canCreate = !permLoading && can('employees', 'create');
  const canEdit = !permLoading && can('employees', 'edit');
  const canDelete = !permLoading && can('employees', 'delete');

  const [users, setUsers] = useState<UserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [showForm, setShowForm] = useState(false);
  const [editingUser, setEditingUser] = useState<UserDto | null>(null);
  const [saving, setSaving] = useState(false);
  const [profileUser, setProfileUser] = useState<UserDto | null>(null);
  const [profileStats, setProfileStats] = useState<{ hours: number; expenses: number; incomes: number; trips: number } | null>(null);
  const [statsLoading, setStatsLoading] = useState(false);
  const [statsMonth, setStatsMonth] = useState(() => {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  });

  const [form, setForm] = useState({
    firstName: '',
    lastName: '',
    middleName: '',
    login: '',
    position: '',
    role: 'employee',
    newPassword: '',
  });

  useEffect(() => {
    if (!canView) return;
    loadUsers();
  }, [canView]);

  const loadUsers = async () => {
    setLoading(true);
    try {
      const { data } = await api.get<UserDto[]>('/users');
      setUsers(data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const openCreateForm = () => {
    setEditingUser(null);
    setForm({
      firstName: '',
      lastName: '',
      middleName: '',
      login: '',
      position: '',
      role: 'employee',
      newPassword: '',
    });
    setShowForm(true);
  };

  const openEditForm = (user: UserDto) => {
    setEditingUser(user);
    setForm({
      firstName: user.firstName || '',
      lastName: user.lastName || '',
      middleName: user.middleName || '',
      login: user.login || '',
      position: user.position || '',
      role: user.role || 'employee',
      newPassword: '',
    });
    setShowForm(true);
  };

  const handleSave = async () => {
    if (!form.lastName || !form.login) {
      alert('Фамилия и логин обязательны');
      return;
    }
    setSaving(true);
    try {
      if (editingUser) {
        // Редактирование
        const updated = {
          ...editingUser,
          ...form,
          name: [form.lastName, form.firstName, form.middleName].filter(Boolean).join(' '),
          newPassword: form.newPassword || undefined,
        };
        await api.put('/users', updated);
      } else {
        // Создание
        await api.post('/users', {
          id: generateUUID(),
          ...form,
          name: [form.lastName, form.firstName, form.middleName].filter(Boolean).join(' '),
          newPassword: form.newPassword || 'password123',
          defaultRateType: 'HOURLY',
          defaultRate: 0,
          defaultCurrency: 'RUB',
        });
      }
      setShowForm(false);
      await loadUsers();
    } catch (err) {
      alert('Ошибка сохранения');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (userId: string) => {
    if (!confirm('Удалить сотрудника? ВСЕ данные будут удалены!')) return;
    try {
      await api.delete('/users', { params: { userId } });
      await loadUsers();
    } catch (err) {
      alert('Ошибка удаления');
    }
  };

  const filtered = users.filter(u =>
    u.name.toLowerCase().includes(search.toLowerCase()) ||
    u.login.toLowerCase().includes(search.toLowerCase()) ||
    u.position.toLowerCase().includes(search.toLowerCase())
  );

  // Загрузка статистики для профиля
  useEffect(() => {
    if (!profileUser) return;

    const loadProfileStats = async () => {
      setStatsLoading(true);
      try {
        const year = parseInt(statsMonth.split('-')[0]);
        const month = parseInt(statsMonth.split('-')[1]);
        const daysInMonth = new Date(year, month, 0).getDate();
        
        const dateFrom = `${statsMonth}-01`;
        const dateTo = `${statsMonth}-${String(daysInMonth).padStart(2, '0')}`;

        const [timesheetRes, expensesRes, incomesRes, tripsRes] = await Promise.all([
          api.get(`/entries?userId=${profileUser.id}&dateFrom=${dateFrom}&dateTo=${dateTo}`),
          api.get(`/expenses?userId=${profileUser.id}&dateFrom=${dateFrom}&dateTo=${dateTo}`),
          api.get(`/incomes?userId=${profileUser.id}&dateFrom=${dateFrom}&dateTo=${dateTo}`),
          api.get(`/business-trips?userId=${profileUser.id}&dateFrom=${dateFrom}&dateTo=${dateTo}`),
        ]);

        const timesheetData = Array.isArray(timesheetRes.data) ? timesheetRes.data : [];
        const expensesData = Array.isArray(expensesRes.data) ? expensesRes.data : [];
        const incomesData = Array.isArray(incomesRes.data) ? incomesRes.data : [];
        const tripsData = Array.isArray(tripsRes.data) ? tripsRes.data : [];

        const totalHours = timesheetData.reduce((sum, e) => sum + (e.hours || 0), 0);
        const totalExpenses = expensesData.reduce((sum, e) => sum + (e.amountRub || 0), 0);
        const totalIncomes = incomesData.reduce((sum, e) => sum + (e.amountRub || 0), 0);
        const totalTrips = tripsData.length;

        setProfileStats({
          hours: Math.round(totalHours),
          expenses: totalExpenses,
          incomes: totalIncomes,
          trips: totalTrips,
        });
      } catch (err) {
        console.error('Ошибка загрузки статистики профиля:', err);
        setProfileStats(null);
      } finally {
        setStatsLoading(false);
      }
    };

    loadProfileStats();
  }, [profileUser, statsMonth]);

  const ROLE_COLORS: Record<string, string> = {
    superadmin: 'bg-purple-100 text-purple-700 border-purple-200 dark:bg-purple-950/40 dark:text-purple-400 dark:border-purple-900',
    admin: 'bg-blue-100 text-blue-700 border-blue-200 dark:bg-blue-950/40 dark:text-blue-400 dark:border-blue-900',
    director: 'bg-green-100 text-green-700 border-green-200 dark:bg-green-950/40 dark:text-green-400 dark:border-green-900',
    manager: 'bg-amber-100 text-amber-700 border-amber-200 dark:bg-amber-950/40 dark:text-amber-400 dark:border-amber-900',
    employee: 'bg-slate-100 text-slate-700 border-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:border-slate-700',
  };

  if (permLoading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;
  if (!canView) {
    return (
      <div className="card p-12 text-center">
        <div className="text-5xl mb-4 opacity-50">🔒</div>
        <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Нет доступа</h3>
        <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">У вас нет прав на просмотр списка сотрудников</p>
      </div>
    );
  }

  return (
    <div className="space-y-6 max-w-6xl mx-auto">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">👥 Сотрудники</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            Всего: {users.length} • Найдено: {filtered.length}
          </p>
        </div>
        {canCreate && (
          <button onClick={openCreateForm} className="btn-primary px-5 py-2.5 shadow-indigo-200 shadow-md">
            ＋ Сотрудник
          </button>
        )}
      </div>

      {/* Поиск */}
      <div className="relative">
        <span className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400">🔍</span>
        <input
          type="text"
          placeholder="Поиск по имени, логину или должности..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="input pl-10"
        />
      </div>

      {/* Список сотрудников */}
      {loading ? (
        <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>
      ) : filtered.length === 0 ? (
        <div className="card p-12 text-center border-dashed border-2 border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-900/50">
          <div className="text-5xl mb-4 opacity-50">👥</div>
          <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Сотрудники не найдены</h3>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {filtered.map(user => (
            <div 
              key={user.id} 
              className="card p-5 group hover:shadow-md transition-all cursor-pointer"
              onClick={() => setProfileUser(user)}
            >
              <div className="flex items-start gap-3 mb-3">
                <div className="w-12 h-12 rounded-full bg-gradient-to-br from-indigo-400 to-purple-500 flex items-center justify-center text-white text-lg font-bold flex-shrink-0">
                  {user.name.charAt(0)}
                </div>
                <div className="min-w-0 flex-1">
                  <div className="font-bold text-slate-900 dark:text-slate-100 truncate">{user.name}</div>
                  <div className="text-xs text-slate-500 dark:text-slate-400">@{user.login}</div>
                  {user.position && (
                    <div className="text-sm text-slate-600 dark:text-slate-400 mt-1 truncate">{user.position}</div>
                  )}
                </div>
              </div>
              <div className="flex items-center justify-between pt-3 border-t border-slate-100 dark:border-slate-800" onClick={(e) => e.stopPropagation()}>
                <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold border ${ROLE_COLORS[user.role] || ROLE_COLORS.employee}`}>
                  {user.role}
                </span>
                <div className="flex gap-2">
                  {canEdit && (
                    <button
                      onClick={() => openEditForm(user)}
                      className="text-xs font-medium text-indigo-600 dark:text-indigo-400 hover:bg-indigo-50 dark:hover:bg-indigo-950/30 px-3 py-1.5 rounded-lg transition-colors"
                    >
                      ✏️ Изменить
                    </button>
                  )}
                  {canDelete && user.role !== 'superadmin' && (
                    <button
                      onClick={() => handleDelete(user.id)}
                      className="text-xs font-medium text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30 px-3 py-1.5 rounded-lg transition-colors"
                    >
                      🗑
                    </button>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* PROLES модалка */}
      {showForm && createPortal(
        <div className="proles-modal-backdrop" onClick={() => !saving && setShowForm(false)}>
          <div className="proles-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '36rem' }}>
            <div className="proles-modal-header">
              <div className="proles-modal-title">
                <div className="proles-modal-icon">👤</div>
                <div>
                  <div>{editingUser ? 'Редактировать сотрудника' : 'Новый сотрудник'}</div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                    {editingUser ? 'Изменение данных' : 'Создание учётной записи'}
                  </div>
                </div>
              </div>
              <button onClick={() => setShowForm(false)} className="proles-modal-close">✕</button>
            </div>
            <div className="proles-modal-body">
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Персональные данные</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group">
                    <label>Фамилия *</label>
                    <input
                      type="text"
                      placeholder="Иванов"
                      value={form.lastName}
                      onChange={(e) => setForm({ ...form, lastName: e.target.value })}
                      className="input"
                    />
                  </div>
                  <div className="proles-input-group">
                    <label>Имя</label>
                    <input
                      type="text"
                      placeholder="Иван"
                      value={form.firstName}
                      onChange={(e) => setForm({ ...form, firstName: e.target.value })}
                      className="input"
                    />
                  </div>
                  <div className="proles-input-group">
                    <label>Отчество</label>
                    <input
                      type="text"
                      placeholder="Иванович"
                      value={form.middleName}
                      onChange={(e) => setForm({ ...form, middleName: e.target.value })}
                      className="input"
                    />
                  </div>
                  <div className="proles-input-group">
                    <label>Должность</label>
                    <input
                      type="text"
                      placeholder="Разработчик"
                      value={form.position}
                      onChange={(e) => setForm({ ...form, position: e.target.value })}
                      className="input"
                    />
                  </div>
                </div>
              </div>
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Учётные данные</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group">
                    <label>Логин *</label>
                    <input
                      type="text"
                      placeholder="ivanov"
                      value={form.login}
                      onChange={(e) => setForm({ ...form, login: e.target.value })}
                      className="input"
                    />
                  </div>
                  <div className="proles-input-group">
                    <label>Роль</label>
                    <select
                      value={form.role}
                      onChange={(e) => setForm({ ...form, role: e.target.value })}
                      className="input bg-white dark:bg-slate-900"
                    >
                      <option value="employee">Сотрудник</option>
                      <option value="manager">Менеджер</option>
                      <option value="director">Директор</option>
                      <option value="admin">Админ</option>
                      <option value="superadmin">Супер-админ</option>
                    </select>
                  </div>
                </div>
              </div>
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Безопасность</div>
                <div
                  style={{
                    padding: '1rem',
                    background: 'linear-gradient(135deg, rgba(251, 191, 36, 0.08) 0%, rgba(245, 158, 11, 0.08) 100%)',
                    border: '1px solid rgba(251, 191, 36, 0.2)',
                    borderRadius: '0.75rem'
                  }}
                >
                  <div className="proles-input-group">
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                      <span>🔑</span>
                      <span>{editingUser ? 'Новый пароль' : 'Пароль'}</span>
                      <span style={{ fontSize: '0.7rem', color: '#94a3b8', fontWeight: 500 }}>
                        ({editingUser ? 'оставьте пустым, чтобы не менять' : 'по умолчанию: password123'})
                      </span>
                    </label>
                    <input
                      type="password"
                      placeholder="Минимум 6 символов"
                      value={form.newPassword}
                      onChange={(e) => setForm({ ...form, newPassword: e.target.value })}
                      className="input"
                    />
                  </div>
                </div>
              </div>
            </div>
            <div className="proles-modal-footer">
              <button onClick={() => setShowForm(false)} className="proles-btn-cancel">Отмена</button>
              <button
                onClick={handleSave}
                disabled={saving || !form.lastName || !form.login}
                className="proles-btn-save"
              >
                {saving ? '⏳ Сохранение...' : '💾 Сохранить'}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* ═══════════ МОДАЛКА: МИНИ-ПРОФИЛЬ (как в AdminPage) ═══════════ */}
      {profileUser && createPortal(
        <div className="fixed inset-0 top-0 left-0 z-[9999] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={() => setProfileUser(null)} />
          <div className="relative bg-white dark:bg-slate-900 rounded-2xl shadow-2xl w-full max-w-2xl max-h-[90vh] overflow-y-auto p-6 animate-fade-in border border-slate-200 dark:border-slate-700">
            <button onClick={() => setProfileUser(null)} className="absolute top-4 right-4 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 text-xl">✕</button>

            {/* Заголовок профиля */}
            <div className="flex items-center gap-4 mb-6">
              <div className="w-16 h-16 rounded-2xl bg-gradient-to-br from-indigo-400 to-purple-500 flex items-center justify-center text-white text-2xl font-bold shadow-lg">
                {profileUser.name.charAt(0)}
              </div>
              <div>
                <h2 className="text-xl font-bold text-slate-900 dark:text-slate-100">{profileUser.name}</h2>
                <div className="text-sm text-slate-500 dark:text-slate-400">{profileUser.position || 'Без должности'}</div>
                <span className={`inline-block mt-1 px-2 py-0.5 rounded-full text-[10px] font-bold border ${ROLE_COLORS[profileUser.role] || ROLE_COLORS.employee}`}>
                  {profileUser.role}
                </span>
              </div>
            </div>

            {/* Подробная статистика сотрудника */}
            <div className="space-y-4 mb-6">
              <div className="flex items-center justify-between">
                <h3 className="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wide">Статистика</h3>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => {
                      const [year, month] = statsMonth.split('-').map(Number);
                      const prevMonth = new Date(year, month - 2, 1);
                      setStatsMonth(`${prevMonth.getFullYear()}-${String(prevMonth.getMonth() + 1).padStart(2, '0')}`);
                    }}
                    className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800"
                    disabled={statsLoading}
                  >
                    ◀
                  </button>
                  <span className="text-sm font-medium text-slate-700 dark:text-slate-300">
                    {new Date(statsMonth + '-01').toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' })}
                  </span>
                  <button
                    onClick={() => {
                      const [year, month] = statsMonth.split('-').map(Number);
                      const nextMonth = new Date(year, month, 1);
                      const now = new Date();
                      if (nextMonth <= now) {
                        setStatsMonth(`${nextMonth.getFullYear()}-${String(nextMonth.getMonth() + 1).padStart(2, '0')}`);
                      }
                    }}
                    className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800"
                    disabled={statsLoading || new Date(statsMonth + '-01') >= new Date(new Date().getFullYear(), new Date().getMonth(), 1)}
                  >
                    ▶
                  </button>
                </div>
              </div>
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                <div className="card p-3 text-center">
                  <div className="text-2xl mb-1">⏱</div>
                  <div className="text-xs text-slate-500">Часы</div>
                  <div className="font-bold text-slate-900 dark:text-slate-100">
                    {statsLoading ? '⏳' : profileStats ? profileStats.hours : '—'}
                  </div>
                </div>
                <div className="card p-3 text-center">
                  <div className="text-2xl mb-1">💸</div>
                  <div className="text-xs text-slate-500">Расходы</div>
                  <div className="font-bold text-slate-900 dark:text-slate-100">
                    {statsLoading ? '⏳' : profileStats ? formatMoney(profileStats.expenses) : '—'}
                  </div>
                </div>
                <div className="card p-3 text-center">
                  <div className="text-2xl mb-1">💵</div>
                  <div className="text-xs text-slate-500">Доходы</div>
                  <div className="font-bold text-slate-900 dark:text-slate-100">
                    {statsLoading ? '⏳' : profileStats ? formatMoney(profileStats.incomes) : '—'}
                  </div>
                </div>
                <div className="card p-3 text-center">
                  <div className="text-2xl mb-1">🚆</div>
                  <div className="text-xs text-slate-500">Командировки</div>
                  <div className="font-bold text-slate-900 dark:text-slate-100">
                    {statsLoading ? '⏳' : profileStats ? profileStats.trips : '—'}
                  </div>
                </div>
              </div>
            </div>

            {/* Кнопки быстрого перехода - компактный вид */}
            <div className="space-y-2 mb-4">
              <h3 className="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wide mb-2">Переходы</h3>
              <div className="grid grid-cols-2 gap-2">
                <button
                  onClick={() => {
                    const now = new Date();
                    const year = now.getFullYear();
                    const month = String(now.getMonth() + 1).padStart(2, '0');
                    const daysInMonth = new Date(year, parseInt(month), 0).getDate();
                    setProfileUser(null);
                    navigate(`/hours-calendar?userId=${profileUser.id}&dateFrom=${year}-${month}-01&dateTo=${year}-${month}-${String(daysInMonth).padStart(2, '0')}`);
                  }}
                  className="flex items-center gap-2 p-2 rounded-lg bg-indigo-50 dark:bg-indigo-950/30 border border-indigo-200 dark:border-indigo-800 hover:border-indigo-400 dark:hover:border-indigo-600 transition-all text-left"
                >
                  <span className="text-lg">⏱</span>
                  <div className="text-xs">
                    <div className="font-bold text-slate-900 dark:text-slate-100">Часы</div>
                    <div className="text-[10px] text-slate-500">Календарь</div>
                  </div>
                </button>
                <button
                  onClick={() => { 
                    const now = new Date();
                    const year = now.getFullYear();
                    const month = String(now.getMonth() + 1).padStart(2, '0');
                    setProfileUser(null); 
                    navigate(`/expenses?userId=${profileUser.id}&dateFrom=${year}-${month}-01&dateTo=${year}-${month}-31`); 
                  }}
                  className="flex items-center gap-2 p-2 rounded-lg bg-orange-50 dark:bg-orange-950/30 border border-orange-200 dark:border-orange-800 hover:border-orange-400 dark:hover:border-orange-600 transition-all text-left"
                >
                  <span className="text-lg">💸</span>
                  <div className="text-xs">
                    <div className="font-bold text-slate-900 dark:text-slate-100">Расходы</div>
                    <div className="text-[10px] text-slate-500">Текущий месяц</div>
                  </div>
                </button>
                <button
                  onClick={() => { 
                    const now = new Date();
                    const year = now.getFullYear();
                    const month = String(now.getMonth() + 1).padStart(2, '0');
                    setProfileUser(null); 
                    navigate(`/incomes?userId=${profileUser.id}&dateFrom=${year}-${month}-01&dateTo=${year}-${month}-31`); 
                  }}
                  className="flex items-center gap-2 p-2 rounded-lg bg-emerald-50 dark:bg-emerald-950/30 border border-emerald-200 dark:border-emerald-800 hover:border-emerald-400 dark:hover:border-emerald-600 transition-all text-left"
                >
                  <span className="text-lg">💵</span>
                  <div className="text-xs">
                    <div className="font-bold text-slate-900 dark:text-slate-100">Доходы</div>
                    <div className="text-[10px] text-slate-500">Текущий месяц</div>
                  </div>
                </button>
                <button
                  onClick={() => {
                    const now = new Date();
                    const year = now.getFullYear();
                    const month = String(now.getMonth() + 1).padStart(2, '0');
                    const daysInMonth = new Date(year, parseInt(month), 0).getDate();
                    setProfileUser(null);
                    navigate(`/trips?userId=${profileUser.id}&dateFrom=${year}-${month}-01&dateTo=${year}-${month}-${String(daysInMonth).padStart(2, '0')}`);
                  }}
                  className="flex items-center gap-2 p-2 rounded-lg bg-cyan-50 dark:bg-cyan-950/30 border border-cyan-200 dark:border-cyan-800 hover:border-cyan-400 dark:hover:border-cyan-600 transition-all text-left"
                >
                  <span className="text-lg">🚆</span>
                  <div className="text-xs">
                    <div className="font-bold text-slate-900 dark:text-slate-100">Команд.</div>
                    <div className="text-[10px] text-slate-500">Поездки</div>
                  </div>
                </button>
              </div>
            </div>
          </div>
        </div>
      , document.body)}
    </div>
  );
}