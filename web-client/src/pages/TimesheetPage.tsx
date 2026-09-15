import { useState, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import api from '../api/client';
import type { TimeEntryDto, ProjectDto, UserDto, DayOffDto } from '../types';
import { generateUUID, dayOfWeekRu, isWeekend } from '../lib/utils';

function getCalendarDays(year: number, month: number) {
  const firstDay = new Date(year, month, 1);
  const lastDay = new Date(year, month + 1, 0);
  const daysInMonth = lastDay.getDate();
  let startDow = firstDay.getDay() - 1;
  if (startDow < 0) startDow = 6;
  const days: Array<{ date: string; day: number; isCurrentMonth: boolean; dow: number }> = [];
  for (let i = 0; i < startDow; i++) days.push({ date: '', day: 0, isCurrentMonth: false, dow: i });
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${year}-${String(month + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
    days.push({ date: dateStr, day: d, isCurrentMonth: true, dow: (startDow + d - 1) % 7 });
  }
  return days;
}

const WEEKDAYS = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];

// 🌍 Только РФ и РБ с флагами
const COUNTRIES = [
  { code: 'RF', label: 'Россия' },
  { code: 'BY', label: 'Беларусь' },
];

const PROJECT_COLORS = [
  'bg-blue-500', 'bg-emerald-500', 'bg-violet-500', 'bg-orange-500',
  'bg-cyan-500', 'bg-pink-500', 'bg-teal-500', 'bg-indigo-500',
];
function getProjectColor(projectId: string): string {
  let hash = 0;
  for (let i = 0; i < projectId.length; i++) hash = projectId.charCodeAt(i) + ((hash << 5) - hash);
  return PROJECT_COLORS[Math.abs(hash) % PROJECT_COLORS.length];
}

function StandardTimesheetPage() {
  const [user, setUser] = useState<UserDto | null>(null);
  const [entries, setEntries] = useState<TimeEntryDto[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [dayOffs, setDayOffs] = useState<DayOffDto[]>([]);
  const [loading, setLoading] = useState(true);

  const now = new Date();
  const [viewYear, setViewYear] = useState(now.getFullYear());
  const [viewMonth, setViewMonth] = useState(now.getMonth());
  const [selectedDate, setSelectedDate] = useState(now.toISOString().slice(0, 10));
  const [hoveredDate, setHoveredDate] = useState<string | null>(null);

  const [showForm, setShowForm] = useState(false);
  const [formProjectId, setFormProjectId] = useState('');
  const [formHours, setFormHours] = useState('');
  const [formCountry, setFormCountry] = useState('RF');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) try { setUser(JSON.parse(stored)); } catch {}
  }, []);

  const loadData = useCallback(async () => {
    if (!user) return;
    setLoading(true);
    try {
      const [entriesRes, projectsRes, dayOffsRes] = await Promise.allSettled([
        api.get<TimeEntryDto[]>('/entries', { params: { userId: user.id } }),
        api.get<ProjectDto[]>('/projects'),
        api.get<DayOffDto[]>('/dayoffs', { params: { userId: user.id } }),
      ]);
      setEntries(entriesRes.status === 'fulfilled' ? entriesRes.value.data : []);
      setProjects(projectsRes.status === 'fulfilled' ? projectsRes.value.data.filter(p => p.isActive) : []);
      setDayOffs(dayOffsRes.status === 'fulfilled' ? dayOffsRes.value.data : []);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [user]);

  useEffect(() => { loadData(); }, [loadData]);

  const dayEntries = entries.filter(e => e.date === selectedDate);
  const dayTotal = dayEntries.reduce((s, e) => s + e.hours, 0);
  const isDayOff = dayOffs.some(d => d.date === selectedDate);

  const hoursByDate = new Map<string, number>();
  entries.forEach(e => {
    if (e.date.startsWith(`${viewYear}-${String(viewMonth + 1).padStart(2, '0')}`)) {
      hoursByDate.set(e.date, (hoursByDate.get(e.date) || 0) + e.hours);
    }
  });
  const dayOffDates = new Set(dayOffs.map(d => d.date));
  const calendarDays = getCalendarDays(viewYear, viewMonth);

  const prevMonth = () => {
    if (viewMonth === 0) { setViewMonth(11); setViewYear(y => y - 1); }
    else setViewMonth(m => m - 1);
  };
  const nextMonth = () => {
    if (viewMonth === 11) { setViewMonth(0); setViewYear(y => y + 1); }
    else setViewMonth(m => m + 1);
  };

  const handleAddEntry = async () => {
    if (!user || !formProjectId || !formHours) return;
    setSaving(true);
    try {
      await api.post('/entries', {
        id: generateUUID(), userId: user.id, projectId: formProjectId,
        projectName: projects.find(p => p.id === formProjectId)?.name || '',
        date: selectedDate, hours: parseFloat(formHours), country: formCountry,
        comment: '', synced: false,
      });
      setShowForm(false); setFormHours(''); setFormProjectId('');
      await loadData();
    } catch { alert('Ошибка добавления записи'); }
    finally { setSaving(false); }
  };

  const handleDeleteEntry = async (entryId: string) => {
    if (!confirm('Удалить запись?')) return;
    await api.delete('/entries', { params: { entryId } });
    await loadData();
  };

  const handleAddDayOff = async () => {
    if (!user) return;
    try {
      await api.post('/dayoffs', { user_id: user.id, date: selectedDate });
      await loadData();
    } catch { alert('Ошибка добавления выходного'); }
  };

  const handleRemoveDayOff = async () => {
    if (!user || !confirm('Убрать выходной?')) return;
    await api.delete('/dayoffs', { params: { userId: user.id, date: selectedDate } });
    await loadData();
  };

  const formatHours = (h: number) => {
    const hrs = Math.floor(h); const mins = Math.round((h - hrs) * 60);
    return mins > 0 ? `${hrs}ч ${mins}м` : `${hrs}ч`;
  };

  const monthNameStr = new Date(viewYear, viewMonth).toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' });
  const selectedIsWeekend = isWeekend(selectedDate);

  // Месячная статистика
  const monthTotalHours = Array.from(hoursByDate.values()).reduce((s, h) => s + h, 0);
  const monthWorkDays = hoursByDate.size;
  const monthDayOffs = dayOffs.filter(d => d.date.startsWith(`${viewYear}-${String(viewMonth + 1).padStart(2, '0')}`)).length;

  if (loading && entries.length === 0) {
    return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;
  }

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      {/* Header с кнопками действий */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">⏱ Табель</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            {new Date(selectedDate).toLocaleDateString('ru-RU', { weekday: 'long', day: 'numeric', month: 'long' })}
            {dayTotal > 0 && ` • ${formatHours(dayTotal)}`}
            {isDayOff && dayTotal === 0 && ' • 🔴 Выходной'}
          </p>
        </div>
        <div className="flex gap-2">
          {isDayOff && dayTotal === 0 && (
            <button onClick={handleRemoveDayOff} className="btn-outline px-4 py-2.5 border-rose-300 dark:border-rose-700 text-rose-700 dark:text-rose-400 hover:bg-rose-50 dark:hover:bg-rose-950/30">
              ✕ Убрать выходной
            </button>
          )}
          {!isDayOff && !selectedIsWeekend && dayEntries.length === 0 && (
            <button onClick={handleAddDayOff} className="btn-outline px-4 py-2.5 border-rose-300 dark:border-rose-700 text-rose-700 dark:text-rose-400 hover:bg-rose-50 dark:hover:bg-rose-950/30">
              🔴 Выходной
            </button>
          )}
          <button onClick={() => setShowForm(true)} className="btn-primary px-5 py-2.5 shadow-indigo-200 shadow-md">
            ＋ Добавить часы
          </button>
        </div>
      </div>

      {/* Месячная статистика */}
      <div className="grid grid-cols-3 gap-3">
        <div className="card p-4 bg-gradient-to-br from-blue-50 to-indigo-50 dark:from-blue-950/30 dark:to-indigo-950/30 border-blue-100 dark:border-blue-900">
          <div className="text-xs font-bold text-blue-600 dark:text-blue-400 uppercase">Всего часов</div>
          <div className="text-2xl font-black text-blue-900 dark:text-blue-100 mt-1">{formatHours(monthTotalHours)}</div>
        </div>
        <div className="card p-4 bg-gradient-to-br from-emerald-50 to-green-50 dark:from-emerald-950/30 dark:to-green-950/30 border-emerald-100 dark:border-emerald-900">
          <div className="text-xs font-bold text-emerald-600 dark:text-emerald-400 uppercase">Рабочих дней</div>
          <div className="text-2xl font-black text-emerald-900 dark:text-emerald-100 mt-1">{monthWorkDays}</div>
        </div>
        <div className="card p-4 bg-gradient-to-br from-rose-50 to-red-50 dark:from-rose-950/30 dark:to-red-950/30 border-rose-100 dark:border-rose-900">
          <div className="text-xs font-bold text-rose-600 dark:text-rose-400 uppercase">Выходных</div>
          <div className="text-2xl font-black text-rose-900 dark:text-rose-100 mt-1">{monthDayOffs}</div>
        </div>
      </div>

      {/* 🔥 ДВУХКОЛОНОЧНЫЙ LAYOUT: Календарь + Записи */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* 📅 Календарь (левая колонка на ПК, полная ширина на мобильном) */}
        <div className="lg:col-span-2">
          <div className="card p-4 md:p-5">
            <div className="flex items-center justify-between mb-4">
              <button onClick={prevMonth} className="btn-ghost px-3 py-1 text-lg">‹</button>
              <h3 className="text-lg font-bold text-slate-900 dark:text-slate-100 capitalize">{monthNameStr}</h3>
              <button onClick={nextMonth} className="btn-ghost px-3 py-1 text-lg">›</button>
            </div>

            {/* Дни недели */}
            <div className="grid grid-cols-7 mb-2 gap-1">
              {WEEKDAYS.map((wd, i) => (
                <div key={wd} className={`text-center text-xs font-bold uppercase tracking-wide py-2 ${i >= 5 ? 'text-red-500 dark:text-red-400' : 'text-slate-500 dark:text-slate-400'}`}>
                  {wd}
                </div>
              ))}
            </div>

            {/* Сетка дней (узкие ячейки на ПК) */}
            <div className="grid grid-cols-7 gap-1">
              {calendarDays.map((cell, idx) => {
                if (!cell.isCurrentMonth) return <div key={`empty-${idx}`} className="min-h-[60px] md:min-h-[60px]" />;

                const isSelected = cell.date === selectedDate;
                const isHovered = cell.date === hoveredDate;
                const hours = hoursByDate.get(cell.date) || 0;
                const hasDayOff = dayOffDates.has(cell.date);
                const isWknd = cell.dow >= 5;
                const isToday = cell.date === now.toISOString().slice(0, 10);

                let bgClass = '';
                let textClass = '';
                let borderClass = '';

                if (hasDayOff && hours === 0) {
                  bgClass = 'bg-rose-100 dark:bg-rose-950/40';
                  textClass = 'text-rose-900 dark:text-rose-200';
                  borderClass = 'border-rose-300 dark:border-rose-800';
                } else if (hasDayOff && hours > 0) {
                  bgClass = 'bg-rose-100 dark:bg-rose-950/40';
                  textClass = 'text-rose-900 dark:text-rose-200';
                  borderClass = 'border-rose-300 dark:border-rose-800';
                } else if (isWknd) {
                  bgClass = 'bg-red-50 dark:bg-red-950/30';
                  textClass = 'text-red-800 dark:text-red-300';
                  borderClass = 'border-red-200 dark:border-red-900';
                } else {
                  bgClass = 'bg-white dark:bg-slate-800';
                  textClass = 'text-slate-700 dark:text-slate-300';
                  borderClass = 'border-slate-200 dark:border-slate-700';
                }

                if (isSelected) {
                  bgClass = 'bg-indigo-600';
                  textClass = 'text-white';
                  borderClass = 'border-indigo-400 dark:border-indigo-500 ring-2 ring-indigo-400 dark:ring-indigo-500';
                } else if (isToday) {
                  borderClass += ' ring-2 ring-indigo-300 dark:ring-indigo-700';
                }

                return (
                  <button
                    key={cell.date}
                    onClick={() => setSelectedDate(cell.date)}
                    onMouseEnter={() => setHoveredDate(cell.date)}
                    onMouseLeave={() => setHoveredDate(null)}
                    className={`min-h-[60px] md:min-h-[60px] rounded-lg relative flex flex-col items-center justify-between p-1.5 transition-all border ${bgClass} ${textClass} ${borderClass} hover:shadow-md hover:scale-[1.01] active:scale-[0.99]`}
                  >
                    <div className={`text-xs font-bold w-full text-left ${isSelected ? 'text-white' : ''}`}>
                      {cell.day}
                    </div>

                    {hours > 0 && (
                      <div className={`text-lg md:text-xl font-black mt-auto leading-none ${isSelected ? 'text-white' : 'text-emerald-600 dark:text-emerald-400'}`}>
                        {hours % 1 === 0 ? `${hours}` : hours.toFixed(1)}
                        <span className="text-[10px] font-bold ml-0.5 opacity-70">ч</span>
                      </div>
                    )}

                    {hasDayOff && hours === 0 && !isSelected && (
                      <div className="text-[10px] font-bold mt-auto opacity-80">
                        ВЫХ
                      </div>
                    )}

                    {isHovered && !isSelected && (
                      <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 z-10 pointer-events-none">
                        <div className="bg-slate-900 dark:bg-slate-700 text-white text-xs rounded-lg px-3 py-2 whitespace-nowrap shadow-xl">
                          <div className="font-bold">{dayOfWeekRu(cell.date)}</div>
                          {hours > 0 && <div className="text-emerald-400">{formatHours(hours)}</div>}
                          {hasDayOff && hours === 0 && <div className="text-rose-400">Выходной</div>}
                          {hours === 0 && !hasDayOff && !isWknd && <div className="text-slate-400">Нет записей</div>}
                        </div>
                      </div>
                    )}
                  </button>
                );
              })}
            </div>

            {/* Легенда */}
            <div className="mt-4 pt-4 border-t border-slate-200 dark:border-slate-700 flex flex-wrap gap-x-4 gap-y-2 text-xs">
              <div className="flex items-center gap-1.5">
                <div className="w-3 h-3 rounded bg-rose-100 dark:bg-rose-950/40 border border-rose-300 dark:border-rose-800"></div>
                <span className="text-slate-600 dark:text-slate-400">Ваш выходной</span>
              </div>
              <div className="flex items-center gap-1.5">
                <div className="w-3 h-3 rounded bg-red-50 dark:bg-red-950/30 border border-red-200 dark:border-red-900"></div>
                <span className="text-slate-600 dark:text-slate-400">Выходной день</span>
              </div>
              <div className="flex items-center gap-1.5">
                <div className="w-3 h-3 rounded bg-indigo-600 border border-indigo-400"></div>
                <span className="text-slate-600 dark:text-slate-400">Выбрано</span>
              </div>
              <div className="flex items-center gap-1.5">
                <div className="w-3 h-3 rounded bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 ring-2 ring-indigo-300 dark:ring-indigo-700"></div>
                <span className="text-slate-600 dark:text-slate-400">Сегодня</span>
              </div>
            </div>
          </div>
        </div>

        {/* 📝 Записи за день (правая колонка на ПК, снизу на мобильном) */}
        <div className="lg:col-span-1">
          {dayEntries.length > 0 ? (
            <div className="space-y-3">
              <h3 className="text-sm font-bold text-slate-900 dark:text-slate-100 flex items-center gap-2">
                <span className="w-1 h-4 bg-indigo-500 rounded-full"></span>
                Записи за {new Date(selectedDate).toLocaleDateString('ru-RU')}
              </h3>
              {dayEntries.map(entry => (
                <div key={entry.id} className="card p-4 group hover:shadow-md transition-all animate-fade-in flex items-center gap-3">
                  <div className={`w-3 h-10 rounded-full flex-shrink-0 ${getProjectColor(entry.projectId)}`} />
                  <div className="min-w-0 flex-1">
                    <div className="font-bold text-slate-900 dark:text-slate-100 truncate text-sm">{entry.projectName || 'Без проекта'}</div>
                    <div className="text-xs text-slate-500 dark:text-slate-400 flex items-center gap-2 mt-1">
                      <span>{COUNTRIES.find(c => c.code === entry.country)?.label || entry.country}</span>
                      {entry.synced && <span className="text-green-600 dark:text-green-400">✓</span>}
                    </div>
                  </div>
                  <div className="flex items-center gap-2 flex-shrink-0">
                    <span className="text-lg font-black text-emerald-600 dark:text-emerald-400 bg-emerald-50 dark:bg-emerald-950/30 px-3 py-1.5 rounded-lg tabular-nums">{formatHours(entry.hours)}</span>
                    <button onClick={() => handleDeleteEntry(entry.id)} className="p-1.5 text-slate-300 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30 rounded-lg transition-all opacity-0 group-hover:opacity-100">🗑</button>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="card p-8 text-center border-dashed border-2 border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-900/50">
              <div className="text-4xl mb-3 opacity-50">📭</div>
              <h3 className="text-base font-semibold text-slate-900 dark:text-slate-100">Нет записей</h3>
              <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">Выберите день и добавьте часы</p>
            </div>
          )}
        </div>
      </div>

      {/* 🌲 PROLES MODAL: Добавить часы */}
      {showForm && createPortal(
        <div className="proles-modal-backdrop" onClick={() => !saving && setShowForm(false)}>
          <div className="proles-modal" onClick={(e) => e.stopPropagation()}>
            <div className="proles-modal-header">
              <div className="proles-modal-title">
                <div className="proles-modal-icon">⏱</div>
                <div>
                  <div>Добавить часы</div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                    {new Date(selectedDate).toLocaleDateString('ru-RU', { weekday: 'long', day: 'numeric', month: 'long' })}
                  </div>
                </div>
              </div>
              <button onClick={() => setShowForm(false)} className="proles-modal-close">✕</button>
            </div>
            <div className="proles-modal-body">
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Проект</div>
                <div className="proles-input-group">
                  <select value={formProjectId} onChange={(e) => setFormProjectId(e.target.value)} className="input bg-white dark:bg-slate-900">
                    <option value="">Выберите проект</option>
                    {projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
                  </select>
                </div>
              </div>
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Детали записи</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group">
                    <label>Часы *</label>
                    <input type="number" step="0.5" min="0.5" placeholder="Например: 8" value={formHours} onChange={(e) => setFormHours(e.target.value)} className="input" />
                  </div>
                  <div className="proles-input-group">
                    <label>Страна *</label>
                    <select value={formCountry} onChange={(e) => setFormCountry(e.target.value)} className="input bg-white dark:bg-slate-900">
                      {COUNTRIES.map(c => <option key={c.code} value={c.code}>{c.label}</option>)}
                    </select>
                  </div>
                </div>
              </div>
            </div>
            <div className="proles-modal-footer">
              <button onClick={() => setShowForm(false)} className="proles-btn-cancel">Отмена</button>
              <button onClick={handleAddEntry} disabled={saving || !formProjectId || !formHours} className="proles-btn-save">
                {saving ? '⏳ Сохранение...' : '💾 Сохранить'}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}
    </div>
  );
}

const PERSONAL_TIMESHEET_LOGIN = 'a.ermashkevich';

type PersonalTask = {
  id: string;
  name: string;
  description: string;
  hours: number;
  category: string;
  status: string;
  isMonthTask?: boolean;
  sortOrder?: number;
};

const DEFAULT_PERSONAL_CATEGORIES = ['Командировки', 'Китай', 'HR', 'Проекты', 'Документы', 'Отчёты', 'Другое'];
const PERSONAL_STATUS_OPTIONS = [
  { value: 'Completed', label: 'Выполнено' },
  { value: 'In progress', label: 'В работе' },
  { value: 'Not started', label: 'Не начато' },
  { value: 'Blocked', label: 'Заблокировано' },
];
const PERSONAL_STATUS_LABELS: Record<string, string> = Object.fromEntries(PERSONAL_STATUS_OPTIONS.map(option => [option.value, option.label]));

function toIsoDate(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function getMonthBounds(year: number, month: number) {
  const start = new Date(year, month - 1, 1);
  const end = new Date(year, month, 0);
  return { start: toIsoDate(start), end: toIsoDate(end) };
}

function PinkStatusChart({ tasks }: { tasks: PersonalTask[] }) {
  const counted = PERSONAL_STATUS_OPTIONS.map(({ value, label }) => ({
    status: value,
    label,
    count: tasks.filter(t => t.name.trim() && t.status === value).length,
  }));
  const max = Math.max(1, ...counted.map(item => item.count));
  const step = Math.max(1, Math.ceil(max / 4));
  const ticks = [step * 4, step * 3, step * 2, step, 0];

  return (
    <div className="h-full rounded-3xl border border-[#f0cedd] bg-gradient-to-br from-white via-[#fffafd] to-[#fff1f7] p-4 md:p-5 shadow-[0_10px_30px_rgba(198,92,138,0.10)]">
      <div className="flex items-center justify-between gap-3">
        <div>
          <div className="text-[12px] font-extrabold uppercase tracking-[0.24em] text-[#a54873]">Статус задач</div>
          <div className="mt-1 text-sm font-medium text-slate-500">Распределение задач по текущему месяцу</div>
        </div>
        <div className="rounded-2xl bg-[#f7d8e6] px-3 py-2 text-right">
          <div className="text-[10px] font-bold uppercase tracking-wider text-[#a54873]">Всего</div>
          <div className="text-xl font-black text-[#6d2949]">{tasks.filter(t => t.name.trim()).length}</div>
        </div>
      </div>
      <div className="mt-5 grid grid-cols-[26px_1fr] gap-3">
        <div className="h-[190px] flex flex-col justify-between text-[10px] font-semibold text-slate-400 text-right">
          {ticks.map(tick => <span key={tick}>{tick}</span>)}
        </div>
        <div className="relative h-[190px] rounded-2xl bg-white/80 px-3 pt-2 pb-7 border border-[#f3dbe5]">
          {[0, 25, 50, 75, 100].map(percent => (
            <div key={percent} className="absolute left-3 right-3 border-t border-dashed border-[#efd9e4]" style={{ top: `${percent}%` }} />
          ))}
          <div className="absolute inset-x-3 top-2 bottom-7 flex items-end gap-3">
            {counted.map(item => {
              const height = Math.max(item.count ? 8 : 3, (item.count / (step * 4)) * 165);
              return (
                <div key={item.status} className="flex-1 h-full flex flex-col items-center justify-end min-w-0">
                  <div className="mb-1 rounded-full bg-[#d96f9b] px-2 py-0.5 text-[10px] font-black text-white shadow-sm">{item.count}</div>
                  <div
                    className="w-full max-w-[54px] rounded-t-2xl bg-gradient-to-t from-[#cf5f8f] to-[#ea8fb2] shadow-[0_8px_18px_rgba(207,95,143,0.20)] transition-all"
                    style={{ height: `${height}px` }}
                    title={`${item.label}: ${item.count}`}
                  />
                  <div className="mt-2 text-[10px] font-semibold text-slate-500 text-center leading-tight">{item.label}</div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}

function PersonalTimesheetPage() {
  const current = new Date();
  const [year, setYear] = useState(current.getFullYear());
  const [month, setMonth] = useState(current.getMonth() + 1);
  const [periodStart, setPeriodStart] = useState(getMonthBounds(current.getFullYear(), current.getMonth() + 1).start);
  const [periodEnd, setPeriodEnd] = useState(getMonthBounds(current.getFullYear(), current.getMonth() + 1).end);
  const [tasks, setTasks] = useState<PersonalTask[]>([]);
  const [monthlyTaskId, setMonthlyTaskId] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [categories, setCategories] = useState<string[]>(DEFAULT_PERSONAL_CATEGORIES);
  const [newCategory, setNewCategory] = useState('');

  const monthLabel = new Date(year, month - 1, 1).toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' });

  const normalizedTasks = tasks.map(task => ({ ...task, hours: Number.isFinite(task.hours) ? task.hours : 0 }));
  const totalHours = normalizedTasks.reduce((sum, task) => sum + task.hours, 0);
  const realTasks = normalizedTasks.filter(task => task.name.trim());
  const totalTasks = realTasks.length;

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [{ data }, categoriesResponse] = await Promise.all([
        api.get<{ periodStart: string; periodEnd: string; monthlyTaskId: string | null; tasks: PersonalTask[] }>('/personal-timesheet', { params: { year, month } }),
        api.get<Array<{ id: string; name: string }>>('/personal-timesheet/categories'),
      ]);
      setCategories(Array.from(new Set([...DEFAULT_PERSONAL_CATEGORIES, ...(categoriesResponse.data || []).map(c => c.name)])));

      setPeriodStart(data.periodStart);
      setPeriodEnd(data.periodEnd);
      setMonthlyTaskId(data.monthlyTaskId || '');
      setTasks((data.tasks || []).map((task, index) => ({
        id: task.id || generateUUID(),
        name: task.name || '',
        description: task.description || '',
        hours: Number(task.hours) || 0,
        category: task.category || '',
        status: task.status || 'Not started',
        isMonthTask: Boolean(task.isMonthTask),
        sortOrder: index,
      })));
      setDirty(false);
    } catch (error) {
      console.error(error);
      setMessage('Не удалось загрузить табель');
    } finally {
      setLoading(false);
    }
  }, [year, month]);

  useEffect(() => { load(); }, [load]);

  const addRow = () => {
    const newTask: PersonalTask = {
      id: generateUUID(),
      name: '',
      description: '',
      hours: 0,
      category: '',
      status: 'Not started',
      sortOrder: tasks.length,
    };
    setTasks(prev => [...prev, newTask]);
    setDirty(true);
  };

  const updateTask = (id: string, patch: Partial<PersonalTask>) => {
    setTasks(prev => prev.map(task => task.id === id ? { ...task, ...patch } : task));
    setDirty(true);
  };

  const deleteRow = (id: string) => {
    setTasks(prev => prev.filter(task => task.id !== id));
    if (monthlyTaskId === id) setMonthlyTaskId('');
    setDirty(true);
  };

  const addCategory = async () => {
    const name = newCategory.trim();
    if (!name) return;
    try {
      const { data } = await api.post<{ id: string; name: string }>('/personal-timesheet/categories', { name });
      setCategories(prev => Array.from(new Set([...prev, data.name])));
      setNewCategory('');
      setMessage(`✅ Категория «${data.name}» добавлена`);
    } catch (error) {
      console.error(error);
      setMessage('❌ Не удалось добавить категорию');
    } finally {
      window.setTimeout(() => setMessage(null), 2200);
    }
  };

  const save = async () => {
    setSaving(true);
    setMessage(null);
    try {
      await api.put('/personal-timesheet', {
        year,
        month,
        periodStart,
        periodEnd,
        monthlyTaskId: monthlyTaskId || null,
        tasks: tasks.map((task, index) => ({
          id: task.id,
          name: task.name,
          description: task.description,
          hours: Number(task.hours) || 0,
          category: task.category,
          status: task.status || 'Not started',
          sortOrder: index,
        })),
      });
      setMessage('✅ Табель сохранён');
      setDirty(false);
      await load();
    } catch (error) {
      console.error(error);
      setMessage('❌ Ошибка сохранения табеля');
    } finally {
      setSaving(false);
      window.setTimeout(() => setMessage(null), 2500);
    }
  };

  const shiftMonth = (delta: number) => {
    let nextMonth = month + delta;
    let nextYear = year;
    if (nextMonth < 1) { nextMonth = 12; nextYear -= 1; }
    if (nextMonth > 12) { nextMonth = 1; nextYear += 1; }
    setYear(nextYear);
    setMonth(nextMonth);
  };

  const monthTaskOptions = tasks.filter(task => task.name.trim());

  if (loading) {
    return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;
  }

  return (
    <div className="min-h-full bg-[radial-gradient(circle_at_top_left,#fff1f7_0%,#fff8fb_34%,#f8f7fb_100%)] text-slate-800 font-sans">
      <div className="mx-auto w-full max-w-[1440px] px-3 py-4 md:px-6 md:py-6">
        <div className="overflow-hidden rounded-[30px] border border-[#f0d7e2] bg-white shadow-[0_18px_60px_rgba(110,55,78,0.10)]">
          <div className="relative overflow-hidden bg-gradient-to-r from-[#cc91ac] via-[#dd9fbb] to-[#efb5c9] px-5 py-6 md:px-8 md:py-8">
            <div className="absolute -right-10 -top-16 h-44 w-44 rounded-full bg-white/15 blur-2xl" />
            <div className="absolute -bottom-20 left-1/3 h-48 w-48 rounded-full bg-white/10 blur-3xl" />
            <div className="relative flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
              <div>
                <div className="text-[10px] font-black uppercase tracking-[0.3em] text-[#6f2948]">Персональный табель</div>
                <h1 className="mt-1 text-4xl font-black tracking-tight text-[#1a1020] md:text-5xl">{new Date(year, month - 1, 1).toLocaleDateString('ru-RU', { month: 'long' }).toUpperCase()}</h1>
                <p className="mt-2 text-sm font-medium text-[#5f3045]">Задачи, часы и статус выполнения за выбранный период</p>
              </div>
              <div className="flex items-center gap-2 self-start rounded-2xl bg-white/65 p-1.5 shadow-sm backdrop-blur md:self-auto">
                <button type="button" onClick={() => shiftMonth(-1)} className="grid h-10 w-10 place-items-center rounded-xl text-xl font-bold text-[#6d2949] transition hover:bg-white hover:shadow-sm" aria-label="Предыдущий месяц">‹</button>
                <div className="min-w-[150px] px-2 text-center text-sm font-extrabold capitalize text-[#53233a]">{monthLabel}</div>
                <button type="button" onClick={() => shiftMonth(1)} className="grid h-10 w-10 place-items-center rounded-xl text-xl font-bold text-[#6d2949] transition hover:bg-white hover:shadow-sm" aria-label="Следующий месяц">›</button>
              </div>
            </div>
          </div>

          <div className="space-y-6 p-4 md:p-7">
            <div className="grid grid-cols-1 gap-5 xl:grid-cols-[minmax(0,1fr)_430px]">
              <div className="rounded-3xl border border-slate-200 bg-white p-5 shadow-[0_8px_25px_rgba(15,23,42,0.05)] md:p-6">
                <div className="grid gap-5 md:grid-cols-[minmax(0,1fr)_180px]">
                  <div className="space-y-4">
                    <div>
                      <div className="text-[11px] font-extrabold uppercase tracking-[0.18em] text-slate-400">Отраженный период</div>
                      <div className="mt-2 flex flex-col gap-2 sm:flex-row sm:items-center">
                        <input type="date" value={periodStart} onChange={e => { setPeriodStart(e.target.value); setDirty(true); }} className="w-full rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm font-bold text-slate-700 shadow-inner outline-none transition focus:border-[#dc78a2] focus:bg-white focus:ring-4 focus:ring-[#f8dce7]" />
                        <span className="hidden font-bold text-slate-300 sm:block">—</span>
                        <input type="date" value={periodEnd} onChange={e => { setPeriodEnd(e.target.value); setDirty(true); }} className="w-full rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm font-bold text-slate-700 shadow-inner outline-none transition focus:border-[#dc78a2] focus:bg-white focus:ring-4 focus:ring-[#f8dce7]" />
                      </div>
                    </div>

                    <div className="grid gap-4 sm:grid-cols-2">
                      <div className="rounded-2xl bg-[#fff3f8] p-4">
                        <div className="text-[11px] font-extrabold uppercase tracking-[0.18em] text-[#b45379]">Задача месяца</div>
                        <select value={monthlyTaskId} onChange={e => { setMonthlyTaskId(e.target.value); setDirty(true); }} className="mt-2 w-full rounded-xl border border-[#f0c9d9] bg-white px-3 py-3 text-sm font-semibold text-slate-700 shadow-sm outline-none transition focus:border-[#d86f99] focus:ring-4 focus:ring-[#f7dbe6]">
                          <option value="">Выберите задачу</option>
                          {monthTaskOptions.map(task => <option key={task.id} value={task.id}>{task.name}</option>)}
                        </select>
                      </div>
                      <div className="rounded-2xl bg-slate-50 p-4">
                        <div className="text-[11px] font-extrabold uppercase tracking-[0.18em] text-slate-400">Потрачено часов</div>
                        <div className="mt-2 text-3xl font-black tracking-tight text-slate-800">{totalHours.toLocaleString('ru-RU')}</div>
                      </div>
                    </div>
                  </div>

                  <div className="flex min-h-[170px] flex-col justify-between rounded-3xl bg-gradient-to-b from-[#dca0ba] to-[#cb87a8] p-5 text-center shadow-[0_12px_30px_rgba(195,102,145,0.22)]">
                    <div className="text-sm font-black uppercase tracking-[0.24em] text-[#5e2942]">Всего</div>
                    <div className="text-6xl font-black leading-none tracking-tight text-[#183e4a]">{totalTasks}</div>
                    <div className="text-xs font-bold uppercase tracking-widest text-[#6e304a]">задач</div>
                  </div>
                </div>
              </div>

              <PinkStatusChart tasks={normalizedTasks} />
            </div>

            <div className="flex flex-col gap-3 rounded-3xl border border-[#f0d7e2] bg-[#fffafd] p-4 md:flex-row md:items-center md:justify-between md:p-5">
              <div className="flex items-center gap-3">
                <div className="grid h-11 w-11 place-items-center rounded-2xl bg-[#f7d8e6] text-xl">📋</div>
                <div>
                  <div className="font-black text-slate-800">Задачи месяца</div>
                  <div className="text-xs text-slate-500">Добавляйте строки, выбирайте категорию и меняйте статус.</div>
                </div>
              </div>
              <div className="flex flex-col gap-2 md:flex-row md:items-center md:justify-end">
                {dirty && <span className="rounded-full bg-amber-100 px-3 py-1.5 text-xs font-bold text-amber-700">Есть несохранённые изменения</span>}
                <div className="flex items-center gap-1 rounded-2xl border border-slate-200 bg-white p-1 shadow-sm">
                  <input
                    value={newCategory}
                    onChange={e => setNewCategory(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); void addCategory(); } }}
                    className="w-[180px] rounded-xl border-0 px-3 py-2 text-sm outline-none placeholder:text-slate-400"
                    placeholder="Своя категория"
                    aria-label="Новая категория"
                  />
                  <button type="button" onClick={() => void addCategory()} className="grid h-9 w-9 place-items-center rounded-xl bg-[#d96f9b] text-lg font-bold text-white shadow-sm transition hover:bg-[#c85d89]" title="Добавить категорию">＋</button>
                </div>
                <button type="button" onClick={addRow} className="rounded-2xl bg-[#d96f9b] px-5 py-3 text-sm font-extrabold text-white shadow-[0_8px_20px_rgba(217,111,155,0.22)] transition hover:-translate-y-0.5 hover:bg-[#c95f8a]">＋ Добавить строку</button>
                <button type="button" onClick={save} disabled={saving} className="rounded-2xl bg-[#173f4c] px-5 py-3 text-sm font-extrabold text-white shadow-[0_8px_20px_rgba(23,63,76,0.18)] transition hover:-translate-y-0.5 hover:bg-[#123540] disabled:cursor-not-allowed disabled:opacity-60">
                  {saving ? 'Сохраняем…' : 'Сохранить табель'}
                </button>
              </div>
            </div>

            <div className="overflow-hidden rounded-3xl border border-[#e7d3dd] bg-white shadow-[0_10px_30px_rgba(30,20,25,0.05)]">
              <div className="overflow-x-auto">
                <table className="w-full min-w-[980px] border-collapse text-sm">
                  <thead>
                    <tr className="bg-gradient-to-r from-[#d96f9b] via-[#e17cac] to-[#d96f9b] text-white">
                      <th className="border-r border-white/20 px-4 py-4 text-left text-[11px] font-black uppercase tracking-[0.16em] w-[24%]">Задача</th>
                      <th className="border-r border-white/20 px-4 py-4 text-left text-[11px] font-black uppercase tracking-[0.16em] w-[30%]">Описание</th>
                      <th className="border-r border-white/20 px-4 py-4 text-center text-[11px] font-black uppercase tracking-[0.16em] w-[12%]">Часы</th>
                      <th className="border-r border-white/20 px-4 py-4 text-left text-[11px] font-black uppercase tracking-[0.16em] w-[16%]">Категория</th>
                      <th className="border-r border-white/20 px-4 py-4 text-left text-[11px] font-black uppercase tracking-[0.16em] w-[15%]">Статус</th>
                      <th className="px-3 py-4 w-[3%]"></th>
                    </tr>
                  </thead>
                  <tbody>
                    {tasks.map((task, index) => (
                      <tr key={task.id} className={`${index % 2 ? 'bg-[#fffafd]' : 'bg-white'} transition hover:bg-[#fff4f8]`}>
                        <td className="border-b border-slate-200 p-1.5 align-top">
                          <textarea value={task.name} onChange={e => updateTask(task.id, { name: e.target.value })} className="min-h-[92px] w-full resize-y rounded-2xl border border-transparent bg-transparent px-3 py-3 text-sm font-bold leading-relaxed outline-none transition focus:border-[#edbfd1] focus:bg-white focus:ring-4 focus:ring-[#fae6ef]" placeholder="Название задачи" rows={3} />
                        </td>
                        <td className="border-b border-slate-200 p-1.5 align-top">
                          <textarea value={task.description} onChange={e => updateTask(task.id, { description: e.target.value })} className="min-h-[92px] w-full resize-y rounded-2xl border border-transparent bg-transparent px-3 py-3 text-sm leading-relaxed outline-none transition focus:border-[#edbfd1] focus:bg-white focus:ring-4 focus:ring-[#fae6ef]" placeholder="Что необходимо сделать / результат" />
                        </td>
                        <td className="border-b border-slate-200 p-1.5 align-top">
                          <input type="number" min="0" step="0.5" value={task.hours || ''} onChange={e => updateTask(task.id, { hours: Number(e.target.value) || 0 })} className="h-[58px] w-full rounded-2xl border border-slate-200 bg-slate-50 px-3 text-center text-lg font-black outline-none transition focus:border-[#edbfd1] focus:bg-white focus:ring-4 focus:ring-[#fae6ef]" placeholder="0" />
                        </td>
                        <td className="border-b border-slate-200 p-1.5 align-top">
                          <select value={task.category} onChange={e => updateTask(task.id, { category: e.target.value })} className="h-[58px] w-full rounded-2xl border border-slate-200 bg-slate-50 px-3 text-sm font-semibold text-slate-700 outline-none transition focus:border-[#edbfd1] focus:bg-white focus:ring-4 focus:ring-[#fae6ef]">
                            <option value="">Категория</option>
                            {categories.map(option => <option key={option} value={option}>{option}</option>)}
                          </select>
                        </td>
                        <td className="border-b border-slate-200 p-1.5 align-top">
                          <select value={task.status} onChange={e => updateTask(task.id, { status: e.target.value })} className="h-[58px] w-full rounded-2xl border border-slate-200 bg-slate-50 px-3 text-sm font-bold text-slate-700 outline-none transition focus:border-[#edbfd1] focus:bg-white focus:ring-4 focus:ring-[#fae6ef]">
                            {PERSONAL_STATUS_OPTIONS.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
                          </select>
                          <div className="mt-1 px-2 text-[11px] font-semibold text-[#a54873]">{PERSONAL_STATUS_LABELS[task.status] || task.status}</div>
                        </td>
                        <td className="border-b border-slate-200 p-1.5 text-center align-top">
                          <button type="button" onClick={() => deleteRow(task.id)} className="grid h-9 w-9 place-items-center rounded-xl text-slate-300 transition hover:bg-red-50 hover:text-red-500" title="Удалить строку">✕</button>
                        </td>
                      </tr>
                    ))}
                    {tasks.length === 0 && (
                      <tr>
                        <td colSpan={6} className="px-6 py-14 text-center">
                          <div className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-[#fff0f6] text-2xl">＋</div>
                          <div className="mt-3 font-black text-slate-700">Пока нет задач</div>
                          <div className="mt-1 text-sm text-slate-400">Добавьте первую строку, чтобы начать вести табель.</div>
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      </div>
      {message && (
        <div className="fixed bottom-5 right-5 z-50 rounded-2xl bg-[#173f4c] px-5 py-3.5 text-sm font-bold text-white shadow-2xl">
          {message}
        </div>
      )}
    </div>
  );
}

export function TimesheetPage() {
  const [user, setUser] = useState<UserDto | null>(null);
  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) {
      try { setUser(JSON.parse(stored)); } catch { /* ignore */ }
    }
  }, []);
  return user?.login === PERSONAL_TIMESHEET_LOGIN ? <PersonalTimesheetPage /> : <StandardTimesheetPage />;
}
