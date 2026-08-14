import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/client';
import type { UserDto, ExpenseDto, IncomeDto } from '../types';
import { formatMoney } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';

export function ProfilePage() {
  const navigate = useNavigate();
  const [user, setUser] = useState<UserDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const { can, loading: permLoading } = usePermissions();

  // 🔐 Редактировать профиль можно только при наличии права employees.edit
  // (обычно это админ, но в будущем можно дать сотрудникам право редактировать себя)
  const canEditProfile = !permLoading && can('employees', 'edit');
  const isSuperAdmin = !permLoading && can('permissions', 'delete');
  const canViewAll = !permLoading && can('expenses_all', 'view');

  const [form, setForm] = useState({
    firstName: '',
    lastName: '',
    middleName: '',
    position: '',
  });

  const [passwordForm, setPasswordForm] = useState({
    newPassword: '',
    confirmPassword: '',
  });
  const [changingPassword, setChangingPassword] = useState(false);

  // Финансовые данные
  const [expenses, setExpenses] = useState<ExpenseDto[]>([]);
  const [incomes, setIncomes] = useState<IncomeDto[]>([]);
  const [financialLoading, setFinancialLoading] = useState(true);

  // Фильтр по месяцу для финансов
  const now = new Date();
  const [currentMonth, setCurrentMonth] = useState({
    year: now.getFullYear(),
    month: now.getMonth(),
  });

  const monthStart = `${currentMonth.year}-${String(currentMonth.month + 1).padStart(2, '0')}-01`;
  const monthEnd = `${currentMonth.year}-${String(currentMonth.month + 1).padStart(2, '0')}-31`;

  const monthNames = [
    'Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь',
    'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь'
  ];

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) {
      try {
        const u = JSON.parse(stored);
        setUser(u);
        setForm({
          firstName: u.firstName || '',
          lastName: u.lastName || '',
          middleName: u.middleName || '',
          position: u.position || '',
        });
      } catch {}
    }
    setLoading(false);
  }, []);

  // Загрузка финансовых данных
  useEffect(() => {
    if (!user) return;
    
    const loadFinancials = async () => {
      setFinancialLoading(true);
      try {
        const userId = user.id;
        const [expRes, incRes] = await Promise.allSettled([
          api.get<ExpenseDto[]>(`/expenses?userId=${userId}&dateFrom=${monthStart}&dateTo=${monthEnd}`),
          api.get<IncomeDto[]>(`/incomes?userId=${userId}&dateFrom=${monthStart}&dateTo=${monthEnd}`),
        ]);
        
        let expData = expRes.status === 'fulfilled' ? expRes.value.data : [];
        let incData = incRes.status === 'fulfilled' ? incRes.value.data : [];
        
        // Если есть право view all, загружаем все данные для сотрудника
        if (canViewAll) {
          const [allExpRes, allIncRes] = await Promise.allSettled([
            api.get<ExpenseDto[]>(`/expenses/all?dateFrom=${monthStart}&dateTo=${monthEnd}&userId=${userId}`),
            api.get<IncomeDto[]>(`/incomes/all?dateFrom=${monthStart}&dateTo=${monthEnd}&userId=${userId}`),
          ]);
          if (allExpRes.status === 'fulfilled') expData = allExpRes.value.data;
          if (allIncRes.status === 'fulfilled') incData = allIncRes.value.data;
        }
        
        setExpenses(expData);
        setIncomes(incData);
      } catch (err) {
        console.error('Ошибка загрузки финансовых данных:', err);
      } finally {
        setFinancialLoading(false);
      }
    };

    loadFinancials();
  }, [user, currentMonth, canViewAll]);

  // Расчет сальдо
  const totalIncome = useMemo(() => incomes.reduce((sum, inc) => sum + inc.amount, 0), [incomes]);
  const totalExpense = useMemo(() => expenses.reduce((sum, exp) => sum + exp.amount, 0), [expenses]);
  const saldo = totalIncome - totalExpense;

  const handleSaveProfile = async () => {
    if (!user) return;
    setSaving(true);
    setMessage(null);
    try {
      const updated = {
        ...user,
        ...form,
        name: [form.lastName, form.firstName, form.middleName].filter(Boolean).join(' '),
      };
      await api.put('/users', updated);
      localStorage.setItem('proles_user', JSON.stringify(updated));
      setUser(updated);
      setMessage({ type: 'success', text: '✅ Профиль обновлён' });
      setTimeout(() => setMessage(null), 3000);
    } catch (err) {
      setMessage({ type: 'error', text: '❌ Ошибка сохранения' });
    } finally {
      setSaving(false);
    }
  };

  const handleChangePassword = async () => {
    if (!user) return;
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      setMessage({ type: 'error', text: '❌ Пароли не совпадают' });
      return;
    }
    if (passwordForm.newPassword.length < 6) {
      setMessage({ type: 'error', text: '❌ Пароль должен быть минимум 6 символов' });
      return;
    }
    setChangingPassword(true);
    setMessage(null);
    try {
      await api.put('/users', {
        ...user,
        newPassword: passwordForm.newPassword,
      });
      setPasswordForm({ newPassword: '', confirmPassword: '' });
      setMessage({ type: 'success', text: '✅ Пароль изменён' });
      setTimeout(() => setMessage(null), 3000);
    } catch (err) {
      setMessage({ type: 'error', text: '❌ Ошибка смены пароля' });
    } finally {
      setChangingPassword(false);
    }
  };

  const handlePrevMonth = () => {
    setCurrentMonth(prev => {
      if (prev.month === 0) {
        return { year: prev.year - 1, month: 11 };
      }
      return { ...prev, month: prev.month - 1 };
    });
  };

  const handleNextMonth = () => {
    setCurrentMonth(prev => {
      if (prev.month === 11) {
        return { year: prev.year + 1, month: 0 };
      }
      return { ...prev, month: prev.month + 1 };
    });
  };

  if (loading || !user) {
    return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;
  }

  return (
    <div className="space-y-6 max-w-3xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">Профиль</h1>
        <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">Настройки аккаунта и безопасности</p>
      </div>

      {message && (
        <div className={`p-4 rounded-xl text-sm font-medium animate-fade-in ${
          message.type === 'success'
            ? 'bg-emerald-50 dark:bg-emerald-950/30 border border-emerald-200 dark:border-emerald-900 text-emerald-700 dark:text-emerald-400'
            : 'bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900 text-red-700 dark:text-red-400'
        }`}>
          {message.text}
        </div>
      )}

      {/* Аватар и основная информация */}
      <div className="card p-6">
        <div className="flex items-center gap-4 mb-6">
          <div className="w-16 h-16 rounded-2xl bg-gradient-to-br from-indigo-400 to-purple-500 flex items-center justify-center text-white text-2xl font-bold shadow-lg shadow-indigo-200 dark:shadow-indigo-900">
            {user.name?.charAt(0) || '?'}
          </div>
          <div>
            <div className="text-xl font-bold text-slate-900 dark:text-slate-100">{user.name}</div>
            <div className="text-sm text-slate-500 dark:text-slate-400">@{user.login}</div>
            <span className="inline-block mt-1 px-2 py-0.5 rounded-full text-[10px] font-bold bg-indigo-100 dark:bg-indigo-950/40 text-indigo-700 dark:text-indigo-400 border border-indigo-200 dark:border-indigo-900 uppercase">
              {user.role}
            </span>
          </div>
        </div>

        {/* 🔐 Форма редактирования доступна только при canEditProfile */}
        {canEditProfile ? (
          <>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="space-y-1.5">
                <label className="text-xs font-medium text-slate-600 dark:text-slate-400">Фамилия</label>
                <input type="text" value={form.lastName} onChange={(e) => setForm({ ...form, lastName: e.target.value })} className="input" />
              </div>
              <div className="space-y-1.5">
                <label className="text-xs font-medium text-slate-600 dark:text-slate-400">Имя</label>
                <input type="text" value={form.firstName} onChange={(e) => setForm({ ...form, firstName: e.target.value })} className="input" />
              </div>
              <div className="space-y-1.5">
                <label className="text-xs font-medium text-slate-600 dark:text-slate-400">Отчество</label>
                <input type="text" value={form.middleName} onChange={(e) => setForm({ ...form, middleName: e.target.value })} className="input" />
              </div>
              <div className="space-y-1.5">
                <label className="text-xs font-medium text-slate-600 dark:text-slate-400">Должность</label>
                <input type="text" value={form.position} onChange={(e) => setForm({ ...form, position: e.target.value })} className="input" />
              </div>
            </div>
            <div className="flex justify-end pt-4 mt-4 border-t border-slate-100 dark:border-slate-800">
              <button onClick={handleSaveProfile} disabled={saving} className="btn-primary px-6 py-2.5">
                {saving ? '⏳...' : 'Сохранить профиль'}
              </button>
            </div>
          </>
        ) : (
          <div className="space-y-2 text-sm">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <div className="text-xs text-slate-400 mb-0.5">Фамилия</div>
                <div className="font-medium text-slate-900 dark:text-slate-100">{user.lastName || '—'}</div>
              </div>
              <div>
                <div className="text-xs text-slate-400 mb-0.5">Имя</div>
                <div className="font-medium text-slate-900 dark:text-slate-100">{user.firstName || '—'}</div>
              </div>
              <div>
                <div className="text-xs text-slate-400 mb-0.5">Отчество</div>
                <div className="font-medium text-slate-900 dark:text-slate-100">{user.middleName || '—'}</div>
              </div>
              <div>
                <div className="text-xs text-slate-400 mb-0.5">Должность</div>
                <div className="font-medium text-slate-900 dark:text-slate-100">{user.position || '—'}</div>
              </div>
            </div>
            <div className="mt-3 p-3 bg-amber-50 dark:bg-amber-950/20 border border-amber-200 dark:border-amber-900 rounded-lg text-xs text-amber-700 dark:text-amber-400">
              🔒 У вас нет прав на редактирование профиля. Обратитесь к администратору.
            </div>
          </div>
        )}
      </div>

      {/* Финансы: Доходы, Расходы, Сальдо */}
      <div className="card p-6">
        <div className="flex items-center justify-between mb-4">
          <h3 className="font-bold text-slate-900 dark:text-slate-100 flex items-center gap-2">
            <span className="w-1.5 h-5 bg-emerald-500 rounded-full"></span>
            Финансы ({monthNames[currentMonth.month]} {currentMonth.year})
          </h3>
          <div className="flex items-center gap-2">
            <button 
              onClick={handlePrevMonth}
              className="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
              aria-label="Предыдущий месяц"
            >
              ←
            </button>
            <button 
              onClick={() => setCurrentMonth({ year: now.getFullYear(), month: now.getMonth() })}
              className="px-3 py-1.5 text-xs font-semibold rounded-lg bg-slate-100 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
            >
              Сегодня
            </button>
            <button 
              onClick={handleNextMonth}
              className="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
              aria-label="Следующий месяц"
            >
              →
            </button>
          </div>
        </div>

        {financialLoading ? (
          <div className="flex justify-center py-8"><div className="animate-spin text-2xl">⏳</div></div>
        ) : (
          <>
            {/* Карточки с итогами */}
            <div className="grid grid-cols-3 gap-3 mb-4">
              <div className="p-4 rounded-xl bg-emerald-50 dark:bg-emerald-950/20 border border-emerald-200 dark:border-emerald-900">
                <div className="text-xs font-bold text-emerald-600 dark:text-emerald-400 uppercase mb-1">Доходы</div>
                <div className="text-xl font-black text-emerald-700 dark:text-emerald-300">{formatMoney(totalIncome)}</div>
              </div>
              <div className="p-4 rounded-xl bg-red-50 dark:bg-red-950/20 border border-red-200 dark:border-red-900">
                <div className="text-xs font-bold text-red-600 dark:text-red-400 uppercase mb-1">Расходы</div>
                <div className="text-xl font-black text-red-700 dark:text-red-300">{formatMoney(totalExpense)}</div>
              </div>
              <div className={`p-4 rounded-xl border ${saldo >= 0 ? 'bg-blue-50 dark:bg-blue-950/20 border-blue-200 dark:border-blue-900' : 'bg-orange-50 dark:bg-orange-950/20 border-orange-200 dark:border-orange-900'}`}>
                <div className={`text-xs font-bold uppercase mb-1 ${saldo >= 0 ? 'text-blue-600 dark:text-blue-400' : 'text-orange-600 dark:text-orange-400'}`}>Сальдо</div>
                <div className={`text-xl font-black ${saldo >= 0 ? 'text-blue-700 dark:text-blue-300' : 'text-orange-700 dark:text-orange-300'}`}>{formatMoney(saldo)}</div>
              </div>
            </div>

            {/* Список операций */}
            <div className="space-y-2">
              <button 
                onClick={() => navigate(`/expenses?userId=${user.id}&dateFrom=${monthStart}&dateTo=${monthEnd}`)}
                className="w-full text-left text-sm font-medium text-indigo-600 dark:text-indigo-400 hover:underline py-2"
              >
                Показать все операции →
              </button>
            </div>
          </>
        )}
      </div>

      {/* Смена пароля — доступна всем */}
      <div className="card p-6">
        <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-4 flex items-center gap-2">
          <span className="w-1.5 h-5 bg-red-500 rounded-full"></span>
          Смена пароля
        </h3>
        <div className="space-y-4 max-w-md">
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-slate-600 dark:text-slate-400">Новый пароль</label>
            <input type="password" placeholder="Минимум 6 символов" value={passwordForm.newPassword} onChange={(e) => setPasswordForm({ ...passwordForm, newPassword: e.target.value })} className="input" />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-slate-600 dark:text-slate-400">Подтвердите пароль</label>
            <input type="password" placeholder="Повторите пароль" value={passwordForm.confirmPassword} onChange={(e) => setPasswordForm({ ...passwordForm, confirmPassword: e.target.value })} className="input" />
          </div>
          <button onClick={handleChangePassword} disabled={changingPassword || !passwordForm.newPassword} className="btn-danger px-5 py-2.5">
            {changingPassword ? '⏳...' : 'Изменить пароль'}
          </button>
        </div>
      </div>

      {/* Системная информация — только для супер-админа */}
      {isSuperAdmin && (
        <div className="card p-6 bg-slate-50/50 dark:bg-slate-900/50">
          <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-3 text-sm flex items-center gap-2">
            <span className="w-1.5 h-5 bg-purple-500 rounded-full"></span>
            Системная информация (только superadmin)
          </h3>
          <div className="space-y-2 text-sm">
            <div>
              <span className="text-slate-500 dark:text-slate-400 text-xs">ID:</span>
              <code className="ml-2 text-xs text-slate-700 dark:text-slate-300 font-mono bg-slate-100 dark:bg-slate-800 px-2 py-0.5 rounded">{user.id}</code>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-400 text-xs">Логин:</span>
              <span className="ml-2 font-medium text-slate-900 dark:text-slate-100">{user.login}</span>
            </div>
            <div>
              <span className="text-slate-500 dark:text-slate-400 text-xs">Роль:</span>
              <span className="ml-2 font-medium text-slate-900 dark:text-slate-100 capitalize">{user.role}</span>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}