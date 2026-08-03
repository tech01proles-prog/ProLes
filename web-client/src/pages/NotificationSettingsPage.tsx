import { useState, useEffect } from 'react';
import api from '../api/client';
import type { NotificationPreferencesDto } from '../types';
import { ThemeToggle } from '../components/ThemeToggle';

const NOTIFICATION_CHANNELS = [
  { key: 'tripEnabled', label: '✈️ Командировки', desc: 'Уведомления о новых командировках и изменениях' },
  { key: 'vacationEnabled', label: '🏖 Отпуска', desc: 'Уведомления об оформленных отпусках' },
  { key: 'dayoffEnabled', label: '🌞 Выходные', desc: 'Уведомления о выходных в будние дни' },
  { key: 'expenseEnabled', label: '💸 Расходы', desc: 'Уведомления о новых расходах по проектам' },
  { key: 'payrollEnabled', label: '💰 Зарплата', desc: 'Уведомления о расчётах и выплатах', adminOnly: true },
  { key: 'ticketEnabled', label: '🎫 Билеты', desc: 'Уведомления о загруженных билетах' },
];

export function NotificationSettingsPage() {
  const [prefs, setPrefs] = useState<NotificationPreferencesDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [isAdmin, setIsAdmin] = useState(false);

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) {
      try {
        const u = JSON.parse(stored);
        setIsAdmin(['admin', 'director', 'superadmin'].includes(u.role));
      } catch {}
    }
    loadPrefs();
  }, []);

  const loadPrefs = async () => {
    setLoading(true);
    try {
      const { data } = await api.get<Record<string, any>>('/notification-preferences');
      // Сервер возвращает Map, преобразуем в наш тип
      setPrefs({
        userId: '',
        tripEnabled: data.tripEnabled ?? true,
        vacationEnabled: data.vacationEnabled ?? true,
        dayoffEnabled: data.dayoffEnabled ?? true,
        expenseEnabled: data.expenseEnabled ?? true,
        payrollEnabled: data.payrollEnabled ?? true,
        ticketEnabled: data.ticketEnabled ?? true,
        telegramEnabled: data.telegramEnabled ?? false,
        emailEnabled: data.emailEnabled ?? false,
        email: data.email ?? '',
      });
    } catch (err) {
      console.error('Failed to load preferences:', err);
      // Дефолтные значения
      setPrefs({
        userId: '', tripEnabled: true, vacationEnabled: true, dayoffEnabled: true,
        expenseEnabled: true, payrollEnabled: true, ticketEnabled: true,
        telegramEnabled: false, emailEnabled: false, email: '',
      });
    } finally {
      setLoading(false);
    }
  };

  const handleSave = async () => {
    if (!prefs) return;
    setSaving(true);
    setMessage(null);
    try {
      // Сервер ожидает Map<String, String>, а не JSON-объект с булевыми
      await api.put('/notification-preferences', {
        tripEnabled: String(prefs.tripEnabled),
        vacationEnabled: String(prefs.vacationEnabled),
        dayoffEnabled: String(prefs.dayoffEnabled),
        expenseEnabled: String(prefs.expenseEnabled),
        payrollEnabled: String(prefs.payrollEnabled),
        ticketEnabled: String(prefs.ticketEnabled),
        telegramEnabled: String(prefs.telegramEnabled),
        emailEnabled: String(prefs.emailEnabled),
        email: prefs.email,
      });
      setMessage('✅ Настройки сохранены');
      setTimeout(() => setMessage(null), 3000);
    } catch (err) {
      setMessage('❌ Ошибка сохранения');
    } finally {
      setSaving(false);
    }
  };

  const togglePref = (key: keyof NotificationPreferencesDto) => {
    if (!prefs) return;
    setPrefs({ ...prefs, [key]: !prefs[key] });
  };

  if (loading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;
  if (!prefs) return null;

  return (
    <div className="space-y-6 max-w-3xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">🔔 Настройки уведомлений</h1>
        <p className="text-sm text-slate-500 mt-1">Управление push-уведомлениями и каналами связи</p>
      </div>

      {message && (
        <div className={`p-4 rounded-xl text-sm font-medium animate-fade-in ${
          message.startsWith('✅') ? 'bg-emerald-50 border border-emerald-200 text-emerald-700' : 'bg-red-50 border border-red-200 text-red-700'
        }`}>
          {message}
        </div>
      )}

      {/* Тема оформления */}
      <div className="card p-6">
        <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-4 flex items-center gap-2">
          <span className="w-1.5 h-5 bg-violet-500 rounded-full"></span>
          🎨 Тема оформления
        </h3>
        <div className="space-y-2">
          <ThemeToggle />
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-2">
            💡 Системная тема автоматически подстраивается под настройки вашего устройства
          </p>
        </div>
      </div>

      {/* Каналы уведомлений */}
      <div className="card p-6">
        <h3 className="font-bold text-slate-900 mb-4 flex items-center gap-2">
          <span className="w-1.5 h-5 bg-indigo-500 rounded-full"></span>
          📱 Push-уведомления
        </h3>
        <div className="space-y-1">
          {NOTIFICATION_CHANNELS.filter(ch => !ch.adminOnly || isAdmin).map(channel => (
            <div key={channel.key} className="flex items-center justify-between p-4 rounded-xl hover:bg-slate-50 transition-colors">
              <div className="min-w-0 flex-1">
                <div className="font-medium text-slate-900 text-sm">{channel.label}</div>
                <div className="text-xs text-slate-500 mt-0.5">{channel.desc}</div>
              </div>
              <button
                onClick={() => togglePref(channel.key as keyof NotificationPreferencesDto)}
                className={`relative w-12 h-7 rounded-full transition-all flex-shrink-0 ml-4 ${
                  prefs[channel.key as keyof NotificationPreferencesDto] ? 'bg-indigo-600' : 'bg-slate-300'
                }`}
              >
                <div className={`absolute top-0.5 w-6 h-6 rounded-full bg-white shadow-md transition-transform ${
                  prefs[channel.key as keyof NotificationPreferencesDto] ? 'translate-x-5.5 left-0.5' : 'left-0.5'
                }`} style={{ transform: prefs[channel.key as keyof NotificationPreferencesDto] ? 'translateX(20px)' : 'translateX(0)' }} />
              </button>
            </div>
          ))}
        </div>
      </div>

      {/* Telegram */}
      <div className="card p-6">
        <h3 className="font-bold text-slate-900 mb-4 flex items-center gap-2">
          <span className="w-1.5 h-5 bg-cyan-500 rounded-full"></span>
          🤖 Telegram
        </h3>
        <div className="flex items-center justify-between p-4 rounded-xl bg-cyan-50/50 border border-cyan-100">
          <div>
            <div className="font-medium text-slate-900 text-sm">🤖 Telegram-бот</div>
            <div className="text-xs text-slate-500 mt-0.5">Получать уведомления в Telegram</div>
          </div>
          <button
            onClick={() => togglePref('telegramEnabled')}
            className={`relative w-12 h-7 rounded-full transition-all flex-shrink-0 ${prefs.telegramEnabled ? 'bg-cyan-600' : 'bg-slate-300'}`}
          >
            <div className="absolute top-0.5 left-0.5 w-6 h-6 rounded-full bg-white shadow-md transition-transform" style={{ transform: prefs.telegramEnabled ? 'translateX(20px)' : 'translateX(0)' }} />
          </button>
        </div>
        {prefs.telegramEnabled && (
          <div className="mt-3 p-3 bg-amber-50 rounded-lg border border-amber-200 text-xs text-amber-700">
            💡 Для активации напишите боту <span className="font-mono font-bold">@PROLES_MGR_BOT</span> команду <span className="font-mono font-bold">/start</span>
          </div>
        )}
      </div>

      {/* Email */}
      <div className="card p-6">
        <h3 className="font-bold text-slate-900 mb-4 flex items-center gap-2">
          <span className="w-1.5 h-5 bg-orange-500 rounded-full"></span>
          📧 Email
        </h3>
        <div className="flex items-center justify-between p-4 rounded-xl bg-orange-50/50 border border-orange-100 mb-3">
          <div>
            <div className="font-medium text-slate-900 text-sm">📧 Email-уведомления</div>
            <div className="text-xs text-slate-500 mt-0.5">Получать сводки и важные уведомления на почту</div>
          </div>
          <button
            onClick={() => togglePref('emailEnabled')}
            className={`relative w-12 h-7 rounded-full transition-all flex-shrink-0 ${prefs.emailEnabled ? 'bg-orange-600' : 'bg-slate-300'}`}
          >
            <div className="absolute top-0.5 left-0.5 w-6 h-6 rounded-full bg-white shadow-md transition-transform" style={{ transform: prefs.emailEnabled ? 'translateX(20px)' : 'translateX(0)' }} />
          </button>
        </div>
        {prefs.emailEnabled && (
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-slate-600">Email для уведомлений</label>
            <input type="email" placeholder="your@email.com" value={prefs.email} onChange={(e) => setPrefs({ ...prefs, email: e.target.value })} className="input" />
          </div>
        )}
      </div>

      {/* Сохранить */}
      <div className="flex justify-end">
        <button onClick={handleSave} disabled={saving} className="btn-primary px-8 py-3 text-base shadow-lg shadow-indigo-200">
          {saving ? '⏳ Сохранение...' : '💾 Сохранить настройки'}
        </button>
      </div>
    </div>
  );
}