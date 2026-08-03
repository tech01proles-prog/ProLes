import { useState } from 'react';
import type { FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/client';
import type { AuthResponse, LoginRequest } from '../types';

export function LoginPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(true);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleLogin = async (e: FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');

    try {
      const body: LoginRequest = { email, password, rememberMe };
      const { data } = await api.post<AuthResponse>('/auth/login', body);

      localStorage.setItem('proles_token', data.token);
      localStorage.setItem('proles_user', JSON.stringify(data.user));

      navigate('/', { replace: true });
    } catch (err: any) {
      const msg = err.response?.data;
      if (typeof msg === 'string') {
        setError(msg);
      } else if (msg?.message) {
        setError(msg.message);
      } else {
        setError('Ошибка входа. Проверьте данные.');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-dvh flex items-center justify-center bg-gradient-to-br from-blue-50 to-cyan-50 dark:from-slate-950 dark:via-slate-900 dark:to-emerald-950/30 px-4 pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)]">
      <form
        onSubmit={handleLogin}
        className="w-full max-w-md bg-white dark:bg-slate-900 dark:border dark:border-slate-800 rounded-2xl shadow-xl dark:shadow-2xl dark:shadow-emerald-950/20 p-8 space-y-6"
      >
        {/* Заголовок */}
        <div className="text-center space-y-2">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-gradient-to-br from-emerald-500 to-green-600 dark:from-emerald-600 dark:to-green-700 text-white text-3xl shadow-lg shadow-emerald-200 dark:shadow-emerald-950/50 mb-2">
            🕐
          </div>
          <h1 className="text-2xl font-bold text-gray-900 dark:text-slate-100">Proles Timesheet</h1>
          <p className="text-sm text-gray-500 dark:text-slate-400">Войдите в систему учёта времени</p>
        </div>

        {/* Ошибка */}
        {error && (
          <div className="p-3 rounded-lg bg-red-50 dark:bg-red-950/40 border border-red-200 dark:border-red-900 text-red-700 dark:text-red-400 text-sm text-center">
            ❌ {error}
          </div>
        )}

        {/* Поля ввода */}
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-semibold text-gray-700 dark:text-slate-300 mb-1.5">
              Email или логин
            </label>
            <input
              type="text"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="ivan@proles.local"
              autoComplete="username"
              autoFocus
              required
              className="w-full px-4 py-3 rounded-xl border border-gray-300 dark:border-slate-700 bg-white dark:bg-slate-800/60 text-gray-900 dark:text-slate-100 placeholder:text-gray-400 dark:placeholder:text-slate-500 focus:ring-2 focus:ring-emerald-500 dark:focus:ring-emerald-500/60 focus:border-transparent outline-none transition-all text-base"
            />
          </div>
          <div>
            <label className="block text-sm font-semibold text-gray-700 dark:text-slate-300 mb-1.5">
              Пароль
            </label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              autoComplete="current-password"
              required
              className="w-full px-4 py-3 rounded-xl border border-gray-300 dark:border-slate-700 bg-white dark:bg-slate-800/60 text-gray-900 dark:text-slate-100 placeholder:text-gray-400 dark:placeholder:text-slate-500 focus:ring-2 focus:ring-emerald-500 dark:focus:ring-emerald-500/60 focus:border-transparent outline-none transition-all text-base"
            />
          </div>
          <label className="flex items-center gap-2 cursor-pointer select-none">
            <input
              type="checkbox"
              checked={rememberMe}
              onChange={(e) => setRememberMe(e.target.checked)}
              className="w-5 h-5 rounded text-emerald-600 accent-emerald-600 dark:accent-emerald-500"
            />
            <span className="text-sm text-gray-700 dark:text-slate-300">Запомнить меня</span>
          </label>
        </div>

        {/* Кнопка */}
        <button
          type="submit"
          disabled={loading}
          className="w-full py-3.5 rounded-xl bg-gradient-to-r from-emerald-500 to-green-600 hover:from-emerald-600 hover:to-green-700 text-white font-semibold text-lg shadow-lg shadow-emerald-200 dark:shadow-emerald-950/40 hover:shadow-xl active:scale-[0.98] transition-all disabled:opacity-70 disabled:cursor-not-allowed"
        >
          {loading ? '⏳ Вход...' : '🌲 Войти'}
        </button>
      </form>
    </div>
  );
}