import { useState, useEffect, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import api from '../api/client';
import type { UserDto, RoleDto, RolePermissionDto, UserEffectivePermissionDto, ExpenseDto, IncomeDto, BusinessTripDto, TimeEntryDto } from '../types';
import { generateUUID, formatMoney } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';
import { createPortal } from 'react-dom';

// ═══════════════════════════════════════════════════════
// 🔑 Константы
// ═══════════════════════════════════════════════════════
const PERMISSION_LABELS: Record<string, string> = {
  projects: '📁 Проекты',
  employees: '👥 Сотрудники',
  payroll: '💰 Зарплата',
  expenses_all: '💸 Все расходы',
  business_trips_all: '🚆 Все командировки',
  vacations_all: '🏖 Все отпуска',
  dayoffs_all: '🌞 Все выходные',
  notifications: '🔔 Уведомления',
  analytics: '📊 Аналитика',
  cost_calculation: '🧮 Себестоимость',
  tickets: '🎫 Билеты',
  permissions: '🔐 Права доступа',
};

const ROLE_COLORS: Record<string, string> = {
  superadmin: 'bg-purple-100 text-purple-700 border-purple-200 dark:bg-purple-950/40 dark:text-purple-400 dark:border-purple-900',
  admin: 'bg-blue-100 text-blue-700 border-blue-200 dark:bg-blue-950/40 dark:text-blue-400 dark:border-blue-900',
  director: 'bg-green-100 text-green-700 border-green-200 dark:bg-green-950/40 dark:text-green-400 dark:border-green-900',
  manager: 'bg-amber-100 text-amber-700 border-amber-200 dark:bg-amber-950/40 dark:text-amber-400 dark:border-amber-900',
  employee: 'bg-slate-100 text-slate-700 border-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:border-slate-700',
};

export function AdminPage() {
  const navigate = useNavigate();
  const { can, refresh: refreshPerms, loading: permLoading } = usePermissions();

  // 🔍 Отладка
  console.log('permLoading:', permLoading);
  console.log('can(employees, view):', can('employees', 'view'));
  console.log('canViewEmployees:', !permLoading && can('employees', 'view'));

  const [tab, setTab] = useState<'users' | 'roles'>('users');
  const [users, setUsers] = useState<UserDto[]>([]);
  const [roles, setRoles] = useState<RoleDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');

  // Модальные окна
  const [editingRole, setEditingRole] = useState<RoleDto | null>(null);
  const [overrideUser, setOverrideUser] = useState<UserDto | null>(null);
  const [profileUser, setProfileUser] = useState<UserDto | null>(null);

  // Статистика для модального окна профиля
  const [profileStats, setProfileStats] = useState<{ hours: number; expenses: number; incomes: number; trips: number } | null>(null);
  const [statsLoading, setStatsLoading] = useState(false);
  const [statsMonth, setStatsMonth] = useState(() => {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  });

  // Форма создания сотрудника
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [createForm, setCreateForm] = useState({
    firstName: '', lastName: '', middleName: '', login: '',
    position: '', role: 'employee', newPassword: '',
  });
  const [saving, setSaving] = useState(false);

  // 🆕 Состояние для модалки редактирования данных сотрудника
  const [editingEmployee, setEditingEmployee] = useState<UserDto | null>(null);
  const [editForm, setEditForm] = useState({
    lastName: '', firstName: '', middleName: '',
    login: '', position: '', role: 'employee', newPassword: '',
  });
  const [editSaving, setEditSaving] = useState(false);

  // Override state
  const [overrides, setOverrides] = useState<UserEffectivePermissionDto[]>([]);
  const [overrideLoading, setOverrideLoading] = useState(false);
  const [overrideSaving, setOverrideSaving] = useState(false);

  // Role edit state
  const [rolePermissions, setRolePermissions] = useState<RolePermissionDto[]>([]);
  const [roleSaving, setRoleSaving] = useState(false);

  // 🔐 Права текущего пользователя
  const isSuperAdmin = !permLoading && can('permissions', 'delete');
  const canEditPermissions = !permLoading && can('permissions', 'edit');
  const canViewEmployees = !permLoading && can('employees', 'view');
  const canCreateEmployees = !permLoading && can('employees', 'create');
  const canEditEmployees = !permLoading && can('employees', 'edit');

  // ═══════════════════════════════════════════════════════
  // 📥 Загрузка данных
  // ═══════════════════════════════════════════════════════
  const loadUsers = useCallback(async () => {
    if (!canViewEmployees) return;
    try {
      const { data } = await api.get<UserDto[]>('/users');
      setUsers(data);
    } catch (err) { console.error(err); }
  }, [canViewEmployees]);

  const loadRoles = useCallback(async () => {
    try {
      const { data } = await api.get<RoleDto[]>('/rbac/roles');
      setRoles(data);
    } catch (err) { console.error(err); }
  }, []);

  // 🔄 Загружаем ОБА набора данных при первой загрузке (чтобы счетчики были актуальны)
  useEffect(() => {
    setLoading(true);
    Promise.allSettled([
      canViewEmployees ? loadUsers() : Promise.resolve(),
      loadRoles()
    ]).finally(() => setLoading(false));
  }, [canViewEmployees, loadUsers, loadRoles]);

  // 🔄 Перезагружаем при переключении вкладок (для актуальности данных)
  useEffect(() => {
    if (tab === 'users' && canViewEmployees) loadUsers();
    else if (tab === 'roles') loadRoles();
  }, [tab, loadUsers, loadRoles, canViewEmployees]);

  // Загрузка override'ов при открытии диалога
  useEffect(() => {
    if (!overrideUser) return;
    setOverrideLoading(true);
    api.get<UserEffectivePermissionDto[]>(`/rbac/users/${overrideUser.id}/permissions`)
      .then(res => setOverrides(res.data))
      .catch(err => console.error(err))
      .finally(() => setOverrideLoading(false));
  }, [overrideUser]);

  // Загрузка статистики профиля при открытии модального окна или смене месяца
  useEffect(() => {
    if (!profileUser) return;
    
    const loadStats = async () => {
      setStatsLoading(true);
      try {
        const year = parseInt(statsMonth.split('-')[0]);
        const month = parseInt(statsMonth.split('-')[1]);
        const daysInMonth = new Date(year, month, 0).getDate();
        const dateFrom = `${statsMonth}-01`;
        const dateTo = `${statsMonth}-${String(daysInMonth).padStart(2, '0')}`;

        const [hoursRes, expensesRes, incomesRes, tripsRes] = await Promise.allSettled([
          api.get<TimeEntryDto[]>(`/timesheet?userId=${profileUser.id}&dateFrom=${dateFrom}&dateTo=${dateTo}`),
          api.get<ExpenseDto[]>(`/expenses?userId=${profileUser.id}&dateFrom=${dateFrom}&dateTo=${dateTo}`),
          api.get<IncomeDto[]>(`/incomes?userId=${profileUser.id}&dateFrom=${dateFrom}&dateTo=${dateTo}`),
          api.get<BusinessTripDto[]>(`/business-trips?userId=${profileUser.id}&dateFrom=${dateFrom}&dateTo=${dateTo}`),
        ]);

        const hours = hoursRes.status === 'fulfilled' ? hoursRes.value.data.reduce((sum, h) => sum + h.hours, 0) : 0;
        const expenses = expensesRes.status === 'fulfilled'
          ? expensesRes.value.data.reduce((sum, e) => sum + e.amount, 0)
          : 0;
        const incomes = incomesRes.status === 'fulfilled'
          ? incomesRes.value.data.reduce((sum, i) => sum + i.amount, 0)
          : 0;
        const trips = tripsRes.status === 'fulfilled' ? tripsRes.value.data.length : 0;

        setProfileStats({ hours, expenses, incomes, trips });
      } catch (err) {
        console.error('Ошибка загрузки статистики:', err);
        setProfileStats({ hours: 0, expenses: 0, incomes: 0, trips: 0 });
      } finally {
        setStatsLoading(false);
      }
    };

    loadStats();
  }, [profileUser, statsMonth, can]);

  // ═══════════════════════════════════════════════════════
  // ✏️ Действия
  // ═══════════════════════════════════════════════════════
  const handleCreateUser = async () => {
    if (!createForm.lastName || !createForm.login) { alert('Фамилия и логин обязательны'); return; }
    setSaving(true);
    try {
      await api.post('/users', {
        id: generateUUID(),
        ...createForm,
        name: [createForm.lastName, createForm.firstName, createForm.middleName].filter(Boolean).join(' '),
        newPassword: createForm.newPassword || 'password123',
        defaultRateType: 'HOURLY',
        defaultRate: 0,
        defaultCurrency: 'RUB',
      });
      setShowCreateForm(false);
      setCreateForm({ firstName: '', lastName: '', middleName: '', login: '', position: '', role: 'employee', newPassword: '' });
      await loadUsers();
    } catch (err) { alert('Ошибка создания'); }
    finally { setSaving(false); }
  };

  // 🆕 Открыть диалог редактирования с предзаполненными данными
  const openEditEmployee = (user: UserDto) => {
    setEditingEmployee(user);
    setEditForm({
      lastName: user.lastName || '',
      firstName: user.firstName || '',
      middleName: user.middleName || '',
      login: user.login || '',
      position: user.position || '',
      role: user.role || 'employee',
      newPassword: '',
    });
  };

  // 🆕 Сохранить изменения сотрудника
  const handleSaveEmployee = async () => {
    if (!editingEmployee) return;
    if (!editForm.lastName || !editForm.login) {
      alert('Фамилия и логин обязательны');
      return;
    }
    setEditSaving(true);
    try {
      const payload: any = {
        ...editingEmployee,
        ...editForm,
        name: [editForm.lastName, editForm.firstName, editForm.middleName].filter(Boolean).join(' '),
      };
      // Пароль отправляем только если он заполнен
      if (editForm.newPassword) {
        payload.newPassword = editForm.newPassword;
      }
      await api.put('/users', payload);
      setEditingEmployee(null);
      await loadUsers();
    } catch (err) {
      alert('Ошибка сохранения');
    } finally {
      setEditSaving(false);
    }
  };

  const handleDeleteUser = async (id: string) => {
    if (!isSuperAdmin) return;
    if (!confirm('Удалить сотрудника? ВСЕ данные будут удалены!')) return;
    await api.delete('/users', { params: { userId: id } });
    await loadUsers();
  };

  const handleSaveOverrides = async () => {
    if (!overrideUser) return;
    setOverrideSaving(true);
    try {
      const dataToSave = overrides
        .filter(o => o.isOverride)
        .map(o => ({
          permission: o.permission,
          canView: o.canView,
          canCreate: o.canCreate,
          canEdit: o.canEdit,
          canDelete: o.canDelete,
        }));
      await api.put(`/rbac/users/${overrideUser.id}/permissions`, dataToSave);
      await refreshPerms();
      setOverrideUser(null);
    } catch (err) { alert('Ошибка сохранения'); }
    finally { setOverrideSaving(false); }
  };

  const handleResetOverrides = () => {
    setOverrides(prev => prev.map(o => ({ ...o, isOverride: false, canView: false, canCreate: false, canEdit: false, canDelete: false })));
  };

  const toggleOverride = (permKey: string, action: 'canView' | 'canCreate' | 'canEdit' | 'canDelete') => {
    setOverrides(prev => prev.map(o => {
      if (o.permission !== permKey) return o;
      // Цикл: роль → true → false → роль
      const currentVal = o[action];
      const nextVal = !o.isOverride ? true : currentVal === true ? false : null;
      const nextIsOverride = nextVal !== null;
      return { ...o, [action]: nextVal ?? false, isOverride: nextIsOverride };
    }));
  };

  const handleSaveRole = async () => {
    if (!editingRole) return;
    setRoleSaving(true);
    try {
      await api.put(`/rbac/roles/${editingRole.id}`, rolePermissions);
      await refreshPerms();
      setEditingRole(null);
      await loadRoles();
    } catch (err) { alert('Ошибка сохранения роли'); }
    finally { setRoleSaving(false); }
  };

  const openRoleEdit = (role: RoleDto) => {
    setEditingRole(role);
    setRolePermissions([...role.permissions]);
  };

  const filteredUsers = users.filter(u =>
    u.name.toLowerCase().includes(search.toLowerCase()) ||
    u.login.toLowerCase().includes(search.toLowerCase()) ||
    u.role.toLowerCase().includes(search.toLowerCase())
  );

  // ═══════════════════════════════════════════════════════
  // 🎨 Render
  // ═══════════════════════════════════════════════════════
  // Показываем loading только если данные реально загружаются
  const isLoadingData = loading && (
    (tab === 'users' && canViewEmployees && users.length === 0) ||
    (tab === 'roles' && roles.length === 0)
  );

  if (isLoadingData) {
    return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;
  }

  // Если нет прав на просмотр
  if (tab === 'users' && !canViewEmployees) {
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
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">🔐 Доступы</h1>
        <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
          Управление сотрудниками и правами доступа
          {isSuperAdmin && <span className="ml-2 badge-indigo">Superadmin</span>}
        </p>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 bg-white dark:bg-slate-900 rounded-xl p-1 border border-slate-200 dark:border-slate-700 w-fit">
        <button onClick={() => setTab('users')} className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${tab === 'users' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'text-slate-600 dark:text-slate-400'}`}>
          👥 Сотрудники ({users.length})
        </button>
        <button onClick={() => setTab('roles')} className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${tab === 'roles' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'text-slate-600 dark:text-slate-400'}`}>
          🛡 Роли ({roles.length})
        </button>
      </div>

      {/* ═══════════ ВКЛАДКА СОТРУДНИКИ ═══════════ */}
      {tab === 'users' && (
        <>
          <div className="flex flex-col sm:flex-row gap-3">
            <div className="relative flex-1">
              <span className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400">🔍</span>
              <input type="text" placeholder="Поиск..." value={search} onChange={(e) => setSearch(e.target.value)} className="input pl-10" />
            </div>
            {canCreateEmployees && (
              <button onClick={() => setShowCreateForm(!showCreateForm)} className="btn-primary px-5 py-2.5">
                {showCreateForm ? '✕' : '＋ Сотрудник'}
              </button>
            )}
          </div>

          {/* 🌲 PROLES MODAL: Новый сотрудник */}
          {showCreateForm && createPortal(
            <div className="proles-modal-backdrop" onClick={() => !saving && setShowCreateForm(false)}>
              <div className="proles-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '36rem' }}>
                <div className="proles-modal-header">
                  <div className="proles-modal-title">
                    <div className="proles-modal-icon">👤</div>
                    <div>
                      <div>Новый сотрудник</div>
                      <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                        Создание учётной записи
                      </div>
                    </div>
                  </div>
                  <button onClick={() => setShowCreateForm(false)} className="proles-modal-close">✕</button>
                </div>
                <div className="proles-modal-body">
                  <div className="proles-modal-section">
                    <div className="proles-modal-section-title">Персональные данные</div>
                    <div className="proles-modal-grid">
                      <div className="proles-input-group">
                        <label>Фамилия *</label>
                        <input type="text" placeholder="Иванов" value={createForm.lastName} onChange={(e) => setCreateForm({ ...createForm, lastName: e.target.value })} className="input" />
                      </div>
                      <div className="proles-input-group">
                        <label>Имя</label>
                        <input type="text" placeholder="Иван" value={createForm.firstName} onChange={(e) => setCreateForm({ ...createForm, firstName: e.target.value })} className="input" />
                      </div>
                      <div className="proles-input-group">
                        <label>Отчество</label>
                        <input type="text" placeholder="Иванович" value={createForm.middleName} onChange={(e) => setCreateForm({ ...createForm, middleName: e.target.value })} className="input" />
                      </div>
                      <div className="proles-input-group">
                        <label>Должность</label>
                        <input type="text" placeholder="Разработчик" value={createForm.position} onChange={(e) => setCreateForm({ ...createForm, position: e.target.value })} className="input" />
                      </div>
                    </div>
                  </div>
                  <div className="proles-modal-section">
                    <div className="proles-modal-section-title">Учётные данные</div>
                    <div className="proles-modal-grid">
                      <div className="proles-input-group">
                        <label>Логин *</label>
                        <input type="text" placeholder="ivanov" value={createForm.login} onChange={(e) => setCreateForm({ ...createForm, login: e.target.value })} className="input" />
                      </div>
                      <div className="proles-input-group">
                        <label>Пароль</label>
                        <input type="password" placeholder="password123" value={createForm.newPassword} onChange={(e) => setCreateForm({ ...createForm, newPassword: e.target.value })} className="input" />
                      </div>
                      <div className="proles-input-group" style={{ gridColumn: 'span 2' }}>
                        <label>Роль</label>
                        <select value={createForm.role} onChange={(e) => setCreateForm({ ...createForm, role: e.target.value })} className="input bg-white dark:bg-slate-900">
                          <option value="employee">Сотрудник</option>
                          <option value="manager">Менеджер</option>
                          <option value="director">Директор</option>
                          <option value="admin">Админ</option>
                          <option value="superadmin">Супер-админ</option>
                        </select>
                      </div>
                    </div>
                  </div>
                </div>
                <div className="proles-modal-footer">
                  <button onClick={() => setShowCreateForm(false)} className="proles-btn-cancel">Отмена</button>
                  <button onClick={handleCreateUser} disabled={saving || !createForm.lastName || !createForm.login} className="proles-btn-save">
                    {saving ? '⏳ Создание...' : '💾 Создать'}
                  </button>
                </div>
              </div>
            </div>,
            document.body
          )}

          {/* Таблица сотрудников */}
          <div className="card overflow-hidden">
            <table className="w-full">
              <thead className="bg-slate-50 dark:bg-slate-800/50 border-b border-slate-200 dark:border-slate-700">
                <tr>
                  <th className="text-left p-4 text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase">Сотрудник</th>
                  <th className="text-left p-4 text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase hidden md:table-cell">Должность</th>
                  <th className="text-left p-4 text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase">Роль</th>
                  <th className="text-right p-4 text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase">Действия</th>
                </tr>
              </thead>
              <tbody>
                {filteredUsers.map(u => (
                  <tr
                    key={u.id}
                    onClick={() => setProfileUser(u)}
                    className="border-b border-slate-100 dark:border-slate-800 hover:bg-slate-50 dark:hover:bg-slate-800/30 transition-colors cursor-pointer"
                  >
                    <td className="p-4">
                      <div className="flex items-center gap-3">
                        <div className="w-9 h-9 rounded-full bg-gradient-to-br from-indigo-400 to-purple-500 flex items-center justify-center text-white text-sm font-bold">
                          {u.name.charAt(0)}
                        </div>
                        <div>
                          <div className="font-medium text-slate-900 dark:text-slate-100 text-sm">{u.name}</div>
                          <div className="text-xs text-slate-500">@{u.login}</div>
                        </div>
                      </div>
                    </td>
                    <td className="p-4 text-sm text-slate-600 dark:text-slate-400 hidden md:table-cell">{u.position || '—'}</td>
                    <td className="p-4">
                      <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold border ${ROLE_COLORS[u.role] || ROLE_COLORS.employee}`}>{u.role}</span>
                    </td>
                    <td className="p-4 text-right" onClick={(e) => e.stopPropagation()}>
                      <div className="flex justify-end gap-2 flex-wrap">
                        {/* ✏️ Редактировать данные сотрудника */}
                        {canEditEmployees && u.role !== 'superadmin' && (
                          <button
                            onClick={() => openEditEmployee(u)}
                            className="inline-flex items-center gap-1.5 text-xs font-semibold px-3 py-1.5 rounded-lg bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-300 hover:border-emerald-400 dark:hover:border-emerald-600 hover:text-emerald-700 dark:hover:text-emerald-400 hover:shadow-sm transition-all"
                            title="Редактировать данные"
                          >
                            <span>✏️</span>
                            <span>Редактировать</span>
                          </button>
                        )}
                        {/* 🔐 Изменить права (вместо ⚙️) */}
                        {canEditPermissions && u.role !== 'superadmin' && (
                          <button
                            onClick={() => setOverrideUser(u)}
                            className="inline-flex items-center gap-1.5 text-xs font-semibold px-3 py-1.5 rounded-lg bg-gradient-to-r from-emerald-500 to-green-600 text-white shadow-sm shadow-emerald-200 dark:shadow-emerald-900/50 hover:from-emerald-600 hover:to-green-700 hover:shadow-md hover:-translate-y-0.5 active:translate-y-0 transition-all"
                            title="Настроить права доступа"
                          >
                            <span>🔐</span>
                            <span>Изменить права</span>
                          </button>
                        )}
                        {/* 🗑 Удалить */}
                        {isSuperAdmin && u.role !== 'superadmin' && (
                          <button
                            onClick={() => handleDeleteUser(u.id)}
                            className="inline-flex items-center gap-1.5 text-xs font-semibold px-3 py-1.5 rounded-lg bg-white dark:bg-slate-800 border border-red-200 dark:border-red-900 text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/30 transition-all"
                            title="Удалить сотрудника"
                          >
                            <span>🗑</span>
                            <span>Удалить</span>
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {/* ═══════════ ВКЛАДКА РОЛИ ═══════════ */}
      {tab === 'roles' && (
        <div className="space-y-3">
          {roles.map(role => {
            const viewCount = role.permissions.filter(p => p.canView).length;
            const createCount = role.permissions.filter(p => p.canCreate).length;
            const editCount = role.permissions.filter(p => p.canEdit).length;
            const deleteCount = role.permissions.filter(p => p.canDelete).length;
            return (
              <div
                key={role.id}
                onClick={() => canEditPermissions && openRoleEdit(role)}
                className={`card p-5 border ${ROLE_COLORS[role.name]?.split(' ').find(c => c.startsWith('border-')) || 'border-slate-200'} ${canEditPermissions ? 'cursor-pointer hover:shadow-md' : ''} transition-all`}
              >
                <div className="flex items-start justify-between mb-3">
                  <div>
                    <div className="flex items-center gap-2 mb-1">
                      <h3 className="font-bold text-slate-900 dark:text-slate-100">{role.displayName}</h3>
                      <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold border ${ROLE_COLORS[role.name] || ROLE_COLORS.employee}`}>{role.name}</span>
                    </div>
                    <p className="text-sm text-slate-500 dark:text-slate-400">{role.description}</p>
                  </div>
                  {canEditPermissions && <span className="text-indigo-500 text-lg">✏️</span>}
                </div>
                <div className="grid grid-cols-4 gap-2 pt-3 border-t border-slate-100 dark:border-slate-800">
                  <div className="text-center p-2 bg-blue-50 dark:bg-blue-950/20 rounded-lg">
                    <div className="text-xl font-bold text-blue-700 dark:text-blue-400">{viewCount}</div>
                    <div className="text-[10px] text-blue-600 dark:text-blue-500 font-semibold">👁 Просмотр</div>
                  </div>
                  <div className="text-center p-2 bg-green-50 dark:bg-green-950/20 rounded-lg">
                    <div className="text-xl font-bold text-green-700 dark:text-green-400">{createCount}</div>
                    <div className="text-[10px] text-green-600 dark:text-green-500 font-semibold">➕ Создание</div>
                  </div>
                  <div className="text-center p-2 bg-orange-50 dark:bg-orange-950/20 rounded-lg">
                    <div className="text-xl font-bold text-orange-700 dark:text-orange-400">{editCount}</div>
                    <div className="text-[10px] text-orange-600 dark:text-orange-500 font-semibold">✏️ Изменение</div>
                  </div>
                  <div className="text-center p-2 bg-red-50 dark:bg-red-950/20 rounded-lg">
                    <div className="text-xl font-bold text-red-700 dark:text-red-400">{deleteCount}</div>
                    <div className="text-[10px] text-red-600 dark:text-red-500 font-semibold">🗑 Удаление</div>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* ═══════════ 🌲 PROLES MODAL: РЕДАКТИРОВАНИЕ СОТРУДНИКА ═══════════ */}
      {editingEmployee && createPortal(
        <div className="proles-modal-backdrop" onClick={() => !editSaving && setEditingEmployee(null)}>
          <div className="proles-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '36rem' }}>
            {/* Шапка */}
            <div className="proles-modal-header">
              <div className="proles-modal-title">
                <div className="proles-modal-icon">✏️</div>
                <div>
                  <div>Редактировать сотрудника</div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                    {editingEmployee.name} • @{editingEmployee.login}
                  </div>
                </div>
              </div>
              <button onClick={() => setEditingEmployee(null)} className="proles-modal-close">✕</button>
            </div>

            {/* Тело */}
            <div className="proles-modal-body">
              {/* Персональные данные */}
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Персональные данные</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group">
                    <label>Фамилия *</label>
                    <input
                      type="text"
                      placeholder="Иванов"
                      value={editForm.lastName}
                      onChange={(e) => setEditForm({ ...editForm, lastName: e.target.value })}
                      className="input"
                    />
                  </div>
                  <div className="proles-input-group">
                    <label>Имя</label>
                    <input
                      type="text"
                      placeholder="Иван"
                      value={editForm.firstName}
                      onChange={(e) => setEditForm({ ...editForm, firstName: e.target.value })}
                      className="input"
                    />
                  </div>
                  <div className="proles-input-group">
                    <label>Отчество</label>
                    <input
                      type="text"
                      placeholder="Иванович"
                      value={editForm.middleName}
                      onChange={(e) => setEditForm({ ...editForm, middleName: e.target.value })}
                      className="input"
                    />
                  </div>
                  <div className="proles-input-group">
                    <label>Должность</label>
                    <input
                      type="text"
                      placeholder="Разработчик"
                      value={editForm.position}
                      onChange={(e) => setEditForm({ ...editForm, position: e.target.value })}
                      className="input"
                    />
                  </div>
                </div>
              </div>

              {/* Учётные данные */}
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Учётные данные</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group">
                    <label>Логин *</label>
                    <input
                      type="text"
                      placeholder="ivanov"
                      value={editForm.login}
                      onChange={(e) => setEditForm({ ...editForm, login: e.target.value })}
                      className="input"
                    />
                  </div>
                  <div className="proles-input-group">
                    <label>Роль</label>
                    <select
                      value={editForm.role}
                      onChange={(e) => setEditForm({ ...editForm, role: e.target.value })}
                      className="input bg-white dark:bg-slate-900"
                    >
                      <option value="employee">Сотрудник</option>
                      <option value="director">Директор</option>
                      <option value="admin">Админ</option>
                      <option value="superadmin">Супер-админ</option>
                    </select>
                  </div>
                </div>
              </div>

              {/* Смена пароля */}
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
                      <span>Новый пароль</span>
                      <span style={{ fontSize: '0.7rem', color: '#94a3b8', fontWeight: 500 }}>(оставьте пустым, чтобы не менять)</span>
                    </label>
                    <input
                      type="password"
                      placeholder="Минимум 6 символов"
                      value={editForm.newPassword}
                      onChange={(e) => setEditForm({ ...editForm, newPassword: e.target.value })}
                      className="input"
                    />
                  </div>
                </div>
              </div>

              {/* Системная информация */}
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Системная информация</div>
                <div
                  style={{
                    padding: '0.75rem 1rem',
                    background: 'rgba(148, 163, 184, 0.08)',
                    borderRadius: '0.5rem',
                    fontSize: '0.75rem',
                    color: '#64748b',
                    display: 'grid',
                    gridTemplateColumns: 'auto 1fr',
                    gap: '0.5rem 1rem'
                  }}
                >
                  <span style={{ fontWeight: 600 }}>ID:</span>
                  <code style={{ fontFamily: 'monospace', color: '#475569' }}>{editingEmployee.id}</code>
                  <span style={{ fontWeight: 600 }}>Текущая роль:</span>
                  <span style={{ color: '#475569' }}>{editingEmployee.role}</span>
                </div>
              </div>
            </div>

            {/* Футер */}
            <div className="proles-modal-footer">
              <button onClick={() => setEditingEmployee(null)} className="proles-btn-cancel">
                Отмена
              </button>
              <button
                onClick={handleSaveEmployee}
                disabled={editSaving || !editForm.lastName || !editForm.login}
                className="proles-btn-save"
              >
                {editSaving ? '⏳ Сохранение...' : '💾 Сохранить изменения'}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* ═══════════ МОДАЛКА: МИНИ-ПРОФИЛЬ ═══════════ */}
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
                    setProfileUser(null); 
                    navigate(`/hours-calendar?userId=${profileUser.id}&dateFrom=${year}-${month}-01&dateTo=${year}-${month}-31`); 
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
                    setProfileUser(null);
                    navigate(`/trips?userId=${profileUser.id}&dateFrom=${year}-${month}-01&dateTo=${year}-${month}-31`); 
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

            {/* Системная информация (только супер-админ) */}
            {isSuperAdmin && (
              <div className="mt-4 pt-4 border-t border-slate-100 dark:border-slate-800">
                <h3 className="text-xs font-bold text-slate-500 dark:text-slate-400 uppercase tracking-wide mb-2">Системная информация</h3>
                <div className="grid grid-cols-2 gap-2 text-xs">
                  <div><span className="text-slate-400">ID:</span> <code className="text-slate-700 dark:text-slate-300">{profileUser.id}</code></div>
                  <div><span className="text-slate-400">Логин:</span> <span className="text-slate-700 dark:text-slate-300">{profileUser.login}</span></div>
                </div>
              </div>
            )}
          </div>
        </div>
      , document.body)}

      {/* ═══════════ МОДАЛКА: РЕДАКТИРОВАНИЕ РОЛИ ═══════════ */}
      {editingRole && createPortal(
        <div className="fixed inset-0 top-0 left-0 z-[9999] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={() => setEditingRole(null)} />
          <div className="relative bg-white dark:bg-slate-900 rounded-2xl shadow-2xl w-full max-w-2xl max-h-[90vh] flex flex-col animate-fade-in border border-slate-200 dark:border-slate-700">
            <div className="p-6 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between">
              <div>
                <h2 className="text-lg font-bold text-slate-900 dark:text-slate-100">✏️ {editingRole.displayName}</h2>
                <p className="text-xs text-slate-500">Настройте права для этой роли</p>
              </div>
              <button onClick={() => setEditingRole(null)} className="text-slate-400 hover:text-slate-600 text-xl">✕</button>
            </div>

            <div className="flex-1 overflow-y-auto p-6">
              {/* Заголовок таблицы */}
              <div className="flex items-center gap-2 mb-3 p-2 bg-slate-50 dark:bg-slate-800 rounded-lg">
                <div className="flex-1 text-xs font-bold text-slate-600 dark:text-slate-400 pl-2">Раздел</div>
                <div className="w-12 text-center text-xs font-bold">👁</div>
                <div className="w-12 text-center text-xs font-bold">➕</div>
                <div className="w-12 text-center text-xs font-bold">✏️</div>
                <div className="w-12 text-center text-xs font-bold">🗑</div>
              </div>

              {rolePermissions.map(perm => (
                <div key={perm.permission} className="flex items-center gap-2 py-2 border-b border-slate-100 dark:border-slate-800">
                  <div className="flex-1 text-sm text-slate-900 dark:text-slate-100 pl-2 truncate">
                    {PERMISSION_LABELS[perm.permission] || perm.permission}
                  </div>
                  {(['canView', 'canCreate', 'canEdit', 'canDelete'] as const).map(action => (
                    <div key={action} className="w-12 flex justify-center">
                      <input
                        type="checkbox"
                        checked={perm[action]}
                        onChange={() => {
                          setRolePermissions(prev => prev.map(p =>
                            p.permission === perm.permission ? { ...p, [action]: !p[action] } : p
                          ));
                        }}
                        className="w-4 h-4 rounded accent-indigo-600"
                      />
                    </div>
                  ))}
                </div>
              ))}

              {/* Быстрые действия */}
              <div className="mt-4 flex gap-2">
                <button onClick={() => setRolePermissions(prev => prev.map(p => ({ ...p, canView: true })))} className="btn-outline px-3 py-1.5 text-xs flex-1">Вкл. всё 👁</button>
                <button onClick={() => setRolePermissions(prev => prev.map(p => ({ ...p, canView: false, canCreate: false, canEdit: false, canDelete: false })))} className="btn-outline px-3 py-1.5 text-xs flex-1">Выкл. всё</button>
              </div>
            </div>

            <div className="p-4 border-t border-slate-200 dark:border-slate-700 flex justify-end gap-2">
              <button onClick={() => setEditingRole(null)} className="btn-outline px-5 py-2.5">Отмена</button>
              <button onClick={handleSaveRole} disabled={roleSaving} className="btn-primary px-6 py-2.5">
                {roleSaving ? '⏳...' : '💾 Сохранить'}
              </button>
            </div>
          </div>
        </div>
      , document.body)}

      {/* ═══════════ МОДАЛКА: OVERRIDE ПРАВ СОТРУДНИКА ═══════════ */}
      {overrideUser && createPortal(
        <div className="fixed inset-0 top-0 left-0 z-[9999] flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" onClick={() => !overrideSaving && setOverrideUser(null)} />
          <div className="relative bg-white dark:bg-slate-900 rounded-2xl shadow-2xl w-full max-w-2xl max-h-[90vh] flex flex-col animate-fade-in border border-slate-200 dark:border-slate-700">
            <div className="p-6 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between">
              <div>
                <h2 className="text-lg font-bold text-slate-900 dark:text-slate-100">⚙️ {overrideUser.name}</h2>
                <p className="text-xs text-slate-500">Роль: {overrideUser.role} • Переопределения прав</p>
              </div>
              <button onClick={() => setOverrideUser(null)} className="text-slate-400 hover:text-slate-600 text-xl">✕</button>
            </div>

            <div className="flex-1 overflow-y-auto p-6">
              {overrideLoading ? (
                <div className="flex justify-center py-12"><div className="animate-spin text-2xl">⏳</div></div>
              ) : (
                <>
                  {/* Заголовок */}
                  <div className="flex items-center gap-2 mb-2 p-2 bg-slate-50 dark:bg-slate-800 rounded-lg">
                    <div className="flex-1 text-xs font-bold text-slate-600 dark:text-slate-400 pl-2">Раздел</div>
                    <div className="w-12 text-center text-xs font-bold">👁</div>
                    <div className="w-12 text-center text-xs font-bold">➕</div>
                    <div className="w-12 text-center text-xs font-bold">✏️</div>
                    <div className="w-12 text-center text-xs font-bold">🗑</div>
                  </div>

                  {/* Строки */}
                  {overrides.sort((a, b) => a.permission.localeCompare(b.permission)).map(o => (
                    <div key={o.permission} className="flex items-center gap-2 py-2 border-b border-slate-100 dark:border-slate-800">
                      <div className="flex-1 text-sm text-slate-900 dark:text-slate-100 pl-2 truncate">
                        {PERMISSION_LABELS[o.permission] || o.permission}
                      </div>
                      {(['canView', 'canCreate', 'canEdit', 'canDelete'] as const).map(action => {
                        const val = o[action];
                        const isOvr = o.isOverride;
                        // 🎯 Всегда показываем ✅ или ❌ на основе фактического значения (роль или override)
                        const color = val ? 'bg-green-500' : 'bg-red-500';
                        const icon = val ? '✅' : '❌';
                        return (
                          <div key={action} className="w-12 flex justify-center">
                            <button
                              onClick={() => toggleOverride(o.permission, action)}
                              className={`w-8 h-8 rounded-lg flex items-center justify-center text-sm transition-all ${color} ${
                                isOvr ? 'ring-2 ring-orange-400 shadow-sm' : 'opacity-70 hover:opacity-100'
                              }`}
                              title={isOvr
                                ? (val ? '✅ Разрешено (персональный override)' : '❌ Запрещено (персональный override)')
                                : (val ? '✅ Разрешено (из роли)' : '❌ Запрещено (из роли)')
                              }
                            >
                              {icon}
                            </button>
                          </div>
                        );
                      })}
                    </div>
                  ))}

                  <button onClick={handleResetOverrides} className="mt-4 w-full btn-danger px-4 py-2 text-sm">
                    🔄 Сбросить все переопределения (использовать роль)
                  </button>
                </>
              )}
            </div>

            <div className="p-4 border-t border-slate-200 dark:border-slate-700 flex justify-end gap-2">
              <button onClick={() => setOverrideUser(null)} disabled={overrideSaving} className="btn-outline px-5 py-2.5">Отмена</button>
              <button onClick={handleSaveOverrides} disabled={overrideSaving || overrideLoading} className="btn-primary px-6 py-2.5">
                {overrideSaving ? '⏳...' : '💾 Сохранить'}
              </button>
            </div>
          </div>
        </div>
      , document.body)}
    </div>
  );
}