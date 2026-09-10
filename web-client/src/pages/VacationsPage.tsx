import { useState, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import api from '../api/client';
import type { VacationDto, UserDto } from '../types';
import { generateUUID, formatDate, daysBetween } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';
import { ScopeTabs } from '../components/ScopeTabs';

export function VacationsPage() {
  const [user, setUser] = useState<UserDto | null>(null);
  const { can } = usePermissions();
  const canViewAll = can('vacations_all', 'view');

  const [scope, setScope] = useState<'my' | 'all'>('my');
  const effectiveScope = canViewAll ? scope : 'my';

  const [myVacations, setMyVacations] = useState<VacationDto[]>([]);
  const [allVacations, setAllVacations] = useState<VacationDto[]>([]);
  const [allUsers, setAllUsers] = useState<UserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const currentYear = new Date().getFullYear();

  const [form, setForm] = useState({
    start: new Date().toISOString().slice(0, 10),
    end: new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10),
  });
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) try { setUser(JSON.parse(stored)); } catch {}
  }, []);

  const loadData = useCallback(async () => {
    if (!user) return;
    setLoading(true);
    try {
      let myVacationsData: VacationDto[] = [];
      let allVacationsData: VacationDto[] = [];
      let usersData: UserDto[] = [];

      // Всегда загружаем свои отпуска
      const vacRes = await api.get<VacationDto[]>('/vacations', { params: { userId: user.id } });
      myVacationsData = vacRes.data;

      if (canViewAll) {
        // Загружаем всех сотрудников и их отпуска для вкладки "Все"
        const usersRes = await api.get<UserDto[]>('/users');
        usersData = usersRes.data;
        const vacationPromises = usersData.map(u =>
          api.get<VacationDto[]>('/vacations', { params: { userId: u.id } })
            .then(res => res.data)
            .catch(() => [] as VacationDto[])
        );
        const allVacs = await Promise.all(vacationPromises);
        allVacationsData = allVacs.flat().filter(v =>
          v.start.startsWith(String(currentYear)) || v.end.startsWith(String(currentYear))
        );
      }

      setMyVacations(myVacationsData);
      setAllVacations(allVacationsData);
      setAllUsers(usersData);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [user, canViewAll, currentYear]);

  useEffect(() => { loadData(); }, [loadData]);

  const vacations = effectiveScope === 'all' ? allVacations : myVacations;
  const myCount = myVacations.length;
  const allCount = allVacations.length;

  const now = new Date();
  const stats = {
    ongoing: vacations.filter(v => v.status === 'APPROVED' && new Date(v.start) <= now && new Date(v.end) >= now).length,
    upcoming: vacations.filter(v => v.status === 'APPROVED' && new Date(v.start) > now).length,
    past: vacations.filter(v => v.status === 'APPROVED' && new Date(v.end) < now).length,
  };

  const getUserName = (userId: string) => allUsers.find(u => u.id === userId)?.name || 'Неизвестный';

  const handleCreate = async () => {
    if (!user) return;
    if (new Date(form.end) < new Date(form.start)) {
      alert('Дата окончания не может быть раньше начала');
      return;
    }
    setSaving(true);
    try {
      await api.post('/vacations', {
        id: generateUUID(),
        userId: user.id,
        start: form.start,
        end: form.end,
      });
      setShowForm(false);
      await loadData();
    } catch { alert('Ошибка создания отпуска'); }
    finally { setSaving(false); }
  };

  // ⚠️ Сервер удаляет ВСЕ отпуска пользователя разом (нет удаления по id)
  const handleDeleteAll = async () => {
    if (!user) return;
    const ownCount = myVacations.length;
    if (!confirm(`Удалить ВСЕ свои отпуска (${ownCount} шт.)? Это действие нельзя отменить.`)) return;
    await api.delete('/vacations', { params: { userId: user.id } });
    await loadData();
  };

  const formDays = new Date(form.end) >= new Date(form.start) ? daysBetween(form.start, form.end) : 0;

  return (
    <div className="space-y-6 max-w-5xl mx-auto">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">🏖 Отпуск</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            {effectiveScope === 'all' ? `Все отпуска за ${currentYear} год` : 'Ваши запланированные отпуска'}
          </p>
        </div>
        <button onClick={() => setShowForm(!showForm)} className="btn-primary px-5 py-2.5 shadow-indigo-200 shadow-md">
          {showForm ? '✕ Закрыть' : '＋ Запросить отпуск'}
        </button>
      </div>

      <ScopeTabs scope={effectiveScope} onChange={setScope} canViewAll={canViewAll} myCount={myCount} allCount={allCount} />

      {/* Статистика */}
      <div className="grid grid-cols-3 gap-3">
        <div className="card p-4 bg-gradient-to-br from-emerald-50 to-green-50 dark:from-emerald-950/30 dark:to-green-950/30 border-emerald-100 dark:border-emerald-900">
          <div className="text-xs font-bold text-emerald-600 dark:text-emerald-400 uppercase">Сейчас</div>
          <div className="text-2xl font-black text-emerald-900 dark:text-emerald-100 mt-1">{stats.ongoing}</div>
        </div>
        <div className="card p-4 bg-gradient-to-br from-blue-50 to-indigo-50 dark:from-blue-950/30 dark:to-indigo-950/30 border-blue-100 dark:border-blue-900">
          <div className="text-xs font-bold text-blue-600 dark:text-blue-400 uppercase">Запланировано</div>
          <div className="text-2xl font-black text-blue-900 dark:text-blue-100 mt-1">{stats.upcoming}</div>
        </div>
        <div className="card p-4 bg-gradient-to-br from-slate-50 to-gray-50 dark:from-slate-900 dark:to-slate-800 border-slate-200 dark:border-slate-700">
          <div className="text-xs font-bold text-slate-600 dark:text-slate-400 uppercase">Завершено</div>
          <div className="text-2xl font-black text-slate-900 dark:text-slate-100 mt-1">{stats.past}</div>
        </div>
      </div>

      {/* 🌲 PROLES MODAL: Запросить отпуск */}
      {showForm && createPortal(
        <div className="proles-modal-backdrop" onClick={() => !saving && setShowForm(false)}>
          <div className="proles-modal" onClick={(e) => e.stopPropagation()}>
            <div className="proles-modal-header">
              <div className="proles-modal-title">
                <div className="proles-modal-icon">🏖</div>
                <div>
                  <div>Запросить отпуск</div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                    Планирование периода отдыха
                  </div>
                </div>
              </div>
              <button onClick={() => setShowForm(false)} className="proles-modal-close">✕</button>
            </div>
            <div className="proles-modal-body">
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Период отпуска</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group">
                    <label>Начало *</label>
                    <input type="date" value={form.start} onChange={(e) => setForm({ ...form, start: e.target.value })} className="input" />
                  </div>
                  <div className="proles-input-group">
                    <label>Окончание *</label>
                    <input type="date" value={form.end} onChange={(e) => setForm({ ...form, end: e.target.value })} className="input" />
                  </div>
                </div>
              </div>
              {formDays > 0 && (
                <div style={{
                  padding: '1rem',
                  background: 'linear-gradient(135deg, rgba(16, 185, 129, 0.08) 0%, rgba(5, 150, 105, 0.08) 100%)',
                  border: '1px solid rgba(16, 185, 129, 0.2)',
                  borderRadius: '0.75rem',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  marginTop: '0.5rem'
                }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                    <span style={{ fontSize: '1.25rem' }}>📊</span>
                    <span style={{ fontSize: '0.875rem', color: '#047857', fontWeight: 600 }}>Длительность</span>
                  </div>
                  <div style={{
                    fontSize: '1.5rem',
                    fontWeight: 800,
                    background: 'linear-gradient(135deg, #059669, #047857)',
                    WebkitBackgroundClip: 'text',
                    WebkitTextFillColor: 'transparent'
                  }}>
                    {formDays} дн.
                  </div>
                </div>
              )}
            </div>
            <div className="proles-modal-footer">
              <button onClick={() => setShowForm(false)} className="proles-btn-cancel">Отмена</button>
              <button onClick={handleCreate} disabled={saving || formDays === 0} className="proles-btn-save">
                {saving ? '⏳ Отправка...' : '💾 Запросить'}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* Список */}
      {loading ? (
        <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>
      ) : vacations.length === 0 ? (
        <div className="card p-12 text-center border-dashed border-2 border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-900/50">
          <div className="text-5xl mb-4 opacity-50">🏖</div>
          <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Нет отпусков</h3>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">Запросите свой первый отпуск</p>
        </div>
      ) : (
        <div className="space-y-3">
          {vacations.sort((a, b) => a.start.localeCompare(b.start)).map(v => {
            const start = new Date(v.start);
            const end = new Date(v.end);
            const isOngoing = start <= now && end >= now;
            const isPast = end < now;
            const days = daysBetween(v.start, v.end);
            const isOwn = v.userId === user?.id;
            const isPending = v.status === 'PENDING';
            const userName = effectiveScope === 'all' ? getUserName(v.userId) : null;

            return (
              <div key={v.id} className={`card p-5 group transition-all animate-fade-in ${
                isOngoing ? 'border-emerald-300 dark:border-emerald-700 bg-emerald-50/30 dark:bg-emerald-950/20 ring-2 ring-emerald-200 dark:ring-emerald-800' :
                isPast ? 'opacity-60' : ''
              }`}>
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 flex-wrap mb-2">
                      <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold border ${
                        isPending ? 'bg-amber-100 text-amber-700 border-amber-200' : v.status === 'REJECTED' ? 'bg-red-100 text-red-700 border-red-200' : isOngoing ? 'bg-emerald-100 text-emerald-700 border-emerald-200' : isPast ? 'bg-slate-100 text-slate-700 border-slate-200' : 'bg-blue-100 text-blue-700 border-blue-200'
                      }`}>
                        {isPending ? '⏳ На подтверждении' : v.status === 'REJECTED' ? '❌ Отклонён' : isOngoing ? '🟢 Сейчас' : isPast ? '✓ Завершён' : '📅 Подтверждён'}
                      </span>
                      <span className="text-xs text-slate-500 dark:text-slate-400">{days} дн.</span>
                      {userName && <span className="text-xs font-medium text-indigo-600 dark:text-indigo-400">👤 {userName}</span>}
                    </div>
                    <div className="font-bold text-slate-900 dark:text-slate-100 text-lg">
                      {formatDate(v.start)} — {formatDate(v.end)}
                    </div>
                    {isOngoing && (
                      <div className="mt-3">
                        <div className="h-2 bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden">
                          <div className="h-full bg-gradient-to-r from-emerald-400 to-green-500 transition-all"
                            style={{ width: `${Math.min(100, ((now.getTime() - start.getTime()) / (end.getTime() - start.getTime())) * 100)}%` }} />
                        </div>
                        <div className="text-[10px] text-slate-500 dark:text-slate-400 mt-1">
                          Пройдено {Math.floor((now.getTime() - start.getTime()) / (1000 * 60 * 60 * 24)) + 1} из {days} дн.
                        </div>
                      </div>
                    )}
                  </div>
                  <div className="flex items-center gap-3">
                    {canViewAll && isPending && (
                      <>
                        <button onClick={async () => { await api.post(`/vacations/${v.id}/decision`, { approved: 'true' }); await loadData(); }} className="text-emerald-600 text-sm font-semibold">Подтвердить</button>
                        <button onClick={async () => { const reason = prompt('Причина отклонения') || ''; await api.post(`/vacations/${v.id}/decision`, { approved: 'false', reason }); await loadData(); }} className="text-red-600 text-sm font-semibold">Отклонить</button>
                      </>
                    )}
                    {isOwn && isPending && <button onClick={handleDeleteAll} className="text-red-500 hover:text-red-700 text-sm" title="Удалить запросы">🗑</button>}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}