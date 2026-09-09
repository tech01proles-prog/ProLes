import { useEffect, useState } from 'react';
import api from '../api/client';
import type { NotificationPreferencesDto } from '../types';
import { ThemeToggle } from '../components/ThemeToggle';

const CHANNELS: Array<{ key: keyof NotificationPreferencesDto; label: string; desc: string }> = [
  { key: 'tripEnabled', label: '✈️ Командировки', desc: 'Новые командировки и изменения маршрута' },
  { key: 'vacationEnabled', label: '🏖 Отпуска', desc: 'Запросы на отпуск для руководителей' },
  { key: 'dayoffEnabled', label: '🌞 Выходные', desc: 'Изменения выходных и рабочих дней' },
  { key: 'expenseEnabled', label: '💸 Расходы', desc: 'Расходные операции и финансовые события' },
  { key: 'payrollEnabled', label: '💰 Зарплата', desc: 'Расчёты, утверждения и выплаты' },
  { key: 'ticketEnabled', label: '🎫 Билеты', desc: 'Новые билеты и переданные документы' },
  { key: 'tripChangeEnabled', label: '🔄 Изменения поездок', desc: 'Переезды, завершение и исправления командировок' },
  { key: 'vacationDecisionEnabled', label: '✅ Решение по отпуску', desc: 'Подтверждение или отклонение моего отпуска' },
  { key: 'expenseCreatedEnabled', label: '🧾 Новые расходы', desc: 'Создание расхода от моего имени или по проекту' },
  { key: 'ticketReceiptEnabled', label: '🧷 Чек по билету', desc: 'Сохранение чека билета и бухгалтерский расход' },
];

export function NotificationSettingsPage() {
  const [prefs, setPrefs] = useState<NotificationPreferencesDto | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const load = async () => {
    try {
      const { data } = await api.get<Record<string, any>>('/notification-preferences');
      setPrefs({
        userId: '',
        tripEnabled: data.tripEnabled ?? true, vacationEnabled: data.vacationEnabled ?? true,
        dayoffEnabled: data.dayoffEnabled ?? true, expenseEnabled: data.expenseEnabled ?? true,
        payrollEnabled: data.payrollEnabled ?? true, ticketEnabled: data.ticketEnabled ?? true,
        tripVisibleToAll: data.tripVisibleToAll ?? false, tripTelegramBroadcast: data.tripTelegramBroadcast ?? false,
        tripChangeEnabled: data.tripChangeEnabled ?? true, vacationDecisionEnabled: data.vacationDecisionEnabled ?? true,
        expenseCreatedEnabled: data.expenseCreatedEnabled ?? true, ticketReceiptEnabled: data.ticketReceiptEnabled ?? true,
        telegramEnabled: data.telegramEnabled ?? false, telegramLinked: data.telegramLinked ?? false,
        telegramLinkCode: data.telegramLinkCode ?? null, emailEnabled: data.emailEnabled ?? false, email: data.email ?? '',
      });
    } catch (e) { console.error(e); }
    finally { setLoading(false); }
  };
  useEffect(() => { load(); }, []);

  const save = async (next: NotificationPreferencesDto) => {
    setSaving(true); setMessage(null);
    try {
      const { data } = await api.put('/notification-preferences', Object.fromEntries(
        Object.entries(next).filter(([key]) => key !== 'userId').map(([key, value]) => [key, String(value)])
      ));
      setPrefs(prev => prev ? { ...prev, ...next, telegramLinkCode: data.telegramLinkCode ?? prev.telegramLinkCode } : prev);
      setMessage('✅ Настройки сохранены');
    } catch (e) { console.error(e); setMessage('❌ Ошибка сохранения'); }
    finally { setSaving(false); setTimeout(() => setMessage(null), 3000); }
  };

  const toggle = async (key: keyof NotificationPreferencesDto) => {
    if (!prefs || typeof prefs[key] !== 'boolean') return;
    const next = { ...prefs, [key]: !prefs[key] };
    setPrefs(next);
    if (key === 'telegramEnabled' && next.telegramEnabled) {
      await save(next);
    }
  };

  if (loading || !prefs) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;

  return <div className="space-y-6 max-w-3xl mx-auto">
    <div><h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">🔔 Настройки уведомлений</h1><p className="text-sm text-slate-500 mt-1">Каналы, события и конфиденциальность</p></div>
    {message && <div className="p-4 rounded-xl text-sm bg-emerald-50 border border-emerald-200 text-emerald-700">{message}</div>}

    <div className="card p-6"><h3 className="font-bold mb-4">🎨 Тема</h3><ThemeToggle /></div>

    <div className="card p-6"><h3 className="font-bold mb-4">📱 События</h3><div className="space-y-1">
      {CHANNELS.map(ch => <div key={ch.key as string} className="flex items-center justify-between p-4 rounded-xl hover:bg-slate-50 dark:hover:bg-slate-800">
        <div><div className="font-medium text-sm">{ch.label}</div><div className="text-xs text-slate-500 mt-0.5">{ch.desc}</div></div>
        <button onClick={() => toggle(ch.key)} className={`relative w-12 h-7 rounded-full ${prefs[ch.key] ? 'bg-indigo-600' : 'bg-slate-300'}`}>
          <div className="absolute top-0.5 left-0.5 w-6 h-6 rounded-full bg-white shadow transition-transform" style={{ transform: prefs[ch.key] ? 'translateX(20px)' : 'translateX(0)' }} />
        </button>
      </div>)}
    </div></div>

    <div className="card p-6"><h3 className="font-bold mb-4">🔐 Конфиденциальность</h3>
      <div className="space-y-3">
        <label className="flex items-center justify-between gap-4 p-4 rounded-xl bg-slate-50 dark:bg-slate-800"><span><b className="text-sm">Отображать мои командировки всем</b><span className="block text-xs text-slate-500 mt-1">Другие сотрудники увидят ваши командировки в общем списке.</span></span><input type="checkbox" checked={prefs.tripVisibleToAll} onChange={() => toggle('tripVisibleToAll')} /></label>
        <label className="flex items-center justify-between gap-4 p-4 rounded-xl bg-slate-50 dark:bg-slate-800"><span><b className="text-sm">Оповещать о моих командировках в Telegram</b><span className="block text-xs text-slate-500 mt-1">При включении новые поездки дублируются связанным подписчикам Telegram.</span></span><input type="checkbox" checked={prefs.tripTelegramBroadcast} onChange={() => toggle('tripTelegramBroadcast')} /></label>
      </div>
    </div>

    <div className="card p-6"><h3 className="font-bold mb-4">🤖 Telegram</h3>
      <div className="flex items-center justify-between p-4 rounded-xl bg-cyan-50/50 border border-cyan-100">
        <div><div className="font-medium text-sm">Telegram-бот</div><div className="text-xs text-slate-500 mt-1">Получать уведомления в личный чат с ботом</div></div>
        <button onClick={() => toggle('telegramEnabled')} disabled={saving} className={`relative w-12 h-7 rounded-full ${prefs.telegramEnabled ? 'bg-cyan-600' : 'bg-slate-300'}`}><div className="absolute top-0.5 left-0.5 w-6 h-6 rounded-full bg-white shadow transition-transform" style={{ transform: prefs.telegramEnabled ? 'translateX(20px)' : 'translateX(0)' }} /></button>
      </div>
      {prefs.telegramEnabled && <div className="mt-3 p-4 bg-amber-50 rounded-lg border border-amber-200 text-sm text-amber-800">
        {prefs.telegramLinked ? <span>✅ Telegram уже привязан. Уведомления будут отправляться в ваш связанный чат.</span> : <><div>1. Откройте Telegram-бот Proles Sys.</div><div>2. Отправьте ему этот код:</div><div className="mt-2 text-2xl font-mono font-black tracking-widest">{prefs.telegramLinkCode || 'генерируется…'}</div><div className="mt-2 text-xs">Код выдаётся один раз. После привязки повторно вводить его не требуется.</div></>}
      </div>}
    </div>

    <div className="card p-6"><h3 className="font-bold mb-4">📧 Email</h3>
      <div className="flex items-center justify-between p-4 rounded-xl bg-orange-50/50 border border-orange-100"><div><div className="font-medium text-sm">Email-уведомления</div><div className="text-xs text-slate-500 mt-1">Системные и критические уведомления</div></div><button onClick={() => toggle('emailEnabled')} className={`relative w-12 h-7 rounded-full ${prefs.emailEnabled ? 'bg-orange-600' : 'bg-slate-300'}`}><div className="absolute top-0.5 left-0.5 w-6 h-6 rounded-full bg-white shadow transition-transform" style={{ transform: prefs.emailEnabled ? 'translateX(20px)' : 'translateX(0)' }} /></button></div>
      {prefs.emailEnabled && <input type="email" value={prefs.email} onChange={e => setPrefs({ ...prefs, email: e.target.value })} className="input mt-3" placeholder="your@email.com" />}
    </div>

    <div className="flex justify-end"><button onClick={() => save(prefs)} disabled={saving} className="btn-primary px-8 py-3">{saving ? '⏳ Сохранение...' : '💾 Сохранить настройки'}</button></div>
  </div>;
}
