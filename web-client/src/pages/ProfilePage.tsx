import { useState, useEffect } from 'react';
import api from '../api/client';
import type { UserDto } from '../types';
import { usePermissions } from '../hooks/usePermissions';

export function ProfilePage() {
  const [user, setUser] = useState<UserDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const { can, loading: permLoading } = usePermissions();

  // 🔐 Редактировать профиль можно только при наличии права employees.edit
  // (обычно это админ, но в будущем можно дать сотрудникам право редактировать себя)
  const canEditProfile = !permLoading && can('employees', 'edit');
  const isSuperAdmin = !permLoading && can('permissions', 'delete');

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

  if (loading || !user) {
    return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;
  }

  return (
    <div className="space-y-6 max-w-3xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">👤 Профиль</h1>
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
                {saving ? '⏳...' : '💾 Сохранить профиль'}
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

      {/* Смена пароля — доступна всем */}
      <div className="card p-6">
        <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-4 flex items-center gap-2">
          <span className="w-1.5 h-5 bg-red-500 rounded-full"></span>
          🔒 Смена пароля
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
            {changingPassword ? '⏳...' : '🔑 Изменить пароль'}
          </button>
        </div>
      </div>

      {/* Системная информация — только для супер-админа */}
      {isSuperAdmin && (
        <div className="card p-6 bg-slate-50/50 dark:bg-slate-900/50">
          <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-3 text-sm flex items-center gap-2">
            <span className="w-1.5 h-5 bg-purple-500 rounded-full"></span>
            ℹ️ Системная информация (только superadmin)
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