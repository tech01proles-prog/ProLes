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

const PERSONAL_CATEGORIES = ['Командировки', 'Китай', 'HR', 'Проекты', 'Документы', 'Отчёты', 'Другое'];
const PERSONAL_STATUSES = ['Completed', 'In progress', 'Not started', 'Blocked'];

function toIsoDate(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function getMonthBounds(year: number, month: number) {
  const start = new Date(year, month - 1, 1);
  const end = new Date(year, month, 0);
  return { start: toIsoDate(start), end: toIsoDate(end) };
}

function PinkStatusChart({ tasks }: { tasks: PersonalTask[] }) {
  const counted = PERSONAL_STATUSES.map(status => ({
    status,
    count: tasks.filter(t => t.name.trim() && t.status === status).length,
  }));
  const max = Math.max(1, ...counted.map(item => item.count));
  const step = Math.max(1, Math.ceil(max / 4));
  const ticks = [step * 4, step * 3, step * 2, step, 0];

  return (
    <div className="rounded-none border border-[#b9b9b9] bg-white px-3 py-2 min-h-[220px]">
      <div className="text-[18px] font-medium tracking-tight text-slate-600 uppercase">СТАТУС</div>
      <div className="mt-2 grid grid-cols-[28px_1fr] gap-2">
        <div className="h-[155px] flex flex-col justify-between text-[10px] text-slate-500 text-right">
          {ticks.map(tick => <span key={tick}>{tick}</span>)}
        </div>
        <div className="relative h-[155px] border-b border-slate-300">
          {[0, 25, 50, 75, 100].map(percent => (
            <div key={percent} className="absolute left-0 right-0 border-t border-slate-200" style={{ top: `${percent}%` }} />
          ))}
          <div className="absolute inset-0 flex items-end gap-3 px-2">
            {counted.map(item => (
              <div key={item.status} className="flex-1 h-full flex flex-col items-center justify-end">
                <div
                  className="w-9 bg-[#e07aa9]"
                  style={{ height: `${Math.max(item.count ? 3 : 1, (item.count / (step * 4)) * 145)}px` }}
                  title={`${item.status}: ${item.count}`}
                />
                <div className="mt-1 text-[9px] text-slate-500 text-center leading-tight">{item.status}</div>
              </div>
            ))}
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

  const monthLabel = new Date(year, month - 1, 1).toLocaleDateString('ru-RU', { month: 'long', year: 'numeric' });

  const normalizedTasks = tasks.map(task => ({ ...task, hours: Number.isFinite(task.hours) ? task.hours : 0 }));
  const totalHours = normalizedTasks.reduce((sum, task) => sum + task.hours, 0);
  const realTasks = normalizedTasks.filter(task => task.name.trim());
  const totalTasks = realTasks.length;

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await api.get<{
        periodStart: string;
        periodEnd: string;
        monthlyTaskId: string | null;
        tasks: PersonalTask[];
      }>('/personal-timesheet', { params: { year, month } });

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
    <div className="min-h-full bg-[#fffafc] text-slate-800">
      <div className="max-w-[1220px] mx-auto px-3 md:px-5 py-4">
        <div className="bg-white border border-slate-300 shadow-sm">
          <div className="bg-[#d49ab5] h-[70px] md:h-[86px] flex items-center justify-center">
            <div className="text-[38px] md:text-[46px] font-black tracking-wide text-black uppercase">{new Date(year, month - 1, 1).toLocaleDateString('ru-RU', { month: 'long' }).toUpperCase()}</div>
          </div>

          <div className="p-4 md:p-6">
            <div className="grid grid-cols-1 lg:grid-cols-[1fr_360px] gap-5">
              <div className="min-w-0">
                <div className="grid grid-cols-[180px_1fr_150px] gap-y-3 items-end text-[15px]">
                  <div className="font-medium">Отраженный период</div>
                  <div className="flex items-center gap-2 border-b border-slate-400 pb-1">
                    <input type="date" value={periodStart} onChange={e => { setPeriodStart(e.target.value); setDirty(true); }} className="w-full border-0 bg-transparent text-center font-bold outline-none" />
                    <span>—</span>
                    <input type="date" value={periodEnd} onChange={e => { setPeriodEnd(e.target.value); setDirty(true); }} className="w-full border-0 bg-transparent text-center font-bold outline-none" />
                  </div>
                  <div className="bg-[#d49ab5] px-4 py-2 text-center text-xl font-bold">
                    <div>ВСЕГО</div>
                    <div className="text-[38px] leading-none text-[#173f4c]">{totalTasks}</div>
                  </div>

                  <div className="font-medium">Задача месяца:</div>
                  <div className="border-b border-slate-400 pb-1">
                    <select value={monthlyTaskId} onChange={e => { setMonthlyTaskId(e.target.value); setDirty(true); }} className="w-full border-0 bg-transparent text-center font-semibold outline-none">
                      <option value="">Выберите задачу</option>
                      {monthTaskOptions.map(task => <option key={task.id} value={task.id}>{task.name}</option>)}
                    </select>
                  </div>
                  <div />

                  <div className="font-medium">Потрачено часов:</div>
                  <div className="border-b border-slate-400 pb-1 text-center text-lg font-semibold">{totalHours}</div>
                  <div />
                </div>

                <div className="mt-3 flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <button type="button" onClick={() => shiftMonth(-1)} className="px-3 py-1 border border-slate-300 bg-white">‹</button>
                    <div className="min-w-[170px] text-center font-bold capitalize">{monthLabel}</div>
                    <button type="button" onClick={() => shiftMonth(1)} className="px-3 py-1 border border-slate-300 bg-white">›</button>
                  </div>
                  <div className="flex items-center gap-2">
                    {dirty && <span className="text-xs text-[#b44d76]">Есть несохранённые изменения</span>}
                    <button type="button" onClick={addRow} className="px-4 py-2 bg-[#d96f9b] text-white font-semibold border border-[#c05f89]">＋ Добавить строку</button>
                    <button type="button" onClick={save} disabled={saving} className="px-4 py-2 bg-[#173f4c] text-white font-semibold disabled:opacity-60">
                      {saving ? 'Сохраняем…' : 'Сохранить'}
                    </button>
                  </div>
                </div>

                <div className="mt-3 overflow-auto border border-slate-400 bg-white">
                  <table className="w-full border-collapse text-sm">
                    <thead>
                      <tr className="bg-[#e27bab] text-black">
                        <th className="border border-slate-500 px-2 py-2 text-center w-[20%]">Задача</th>
                        <th className="border border-slate-500 px-2 py-2 text-center w-[29%]">Описание</th>
                        <th className="border border-slate-500 px-2 py-2 text-center w-[12%]">Количество часов</th>
                        <th className="border border-slate-500 px-2 py-2 text-center w-[16%]">Входит в</th>
                        <th className="border border-slate-500 px-2 py-2 text-center w-[15%]">Статус</th>
                        <th className="border border-slate-500 px-2 py-2 w-[8%]"></th>
                      </tr>
                    </thead>
                    <tbody>
                      {tasks.map((task, index) => (
                        <tr key={task.id} className={index % 2 ? 'bg-[#f7f7f7]' : 'bg-white'}>
                          <td className="border border-slate-300 p-0">
                            <input value={task.name} onChange={e => updateTask(task.id, { name: e.target.value })} className="w-full min-h-[44px] px-2 py-2 border-0 bg-transparent text-center outline-none focus:bg-[#fff2f7]" placeholder="Новая задача" />
                          </td>
                          <td className="border border-slate-300 p-0">
                            <textarea value={task.description} onChange={e => updateTask(task.id, { description: e.target.value })} className="w-full min-h-[44px] px-2 py-2 border-0 bg-transparent text-center outline-none resize-y focus:bg-[#fff2f7]" placeholder="Описание" />
                          </td>
                          <td className="border border-slate-300 p-0">
                            <input type="number" min="0" step="0.5" value={task.hours || ''} onChange={e => updateTask(task.id, { hours: Number(e.target.value) || 0 })} className="w-full min-h-[44px] px-2 py-2 border-0 bg-transparent text-center outline-none focus:bg-[#fff2f7]" placeholder="0" />
                          </td>
                          <td className="border border-slate-300 p-0">
                            <select value={task.category} onChange={e => updateTask(task.id, { category: e.target.value })} className="w-full min-h-[44px] px-2 py-2 border-0 bg-transparent text-center outline-none focus:bg-[#fff2f7]">
                              <option value="">Категория</option>
                              {PERSONAL_CATEGORIES.map(option => <option key={option} value={option}>{option}</option>)}
                            </select>
                          </td>
                          <td className="border border-slate-300 p-0">
                            <select value={task.status} onChange={e => updateTask(task.id, { status: e.target.value })} className="w-full min-h-[44px] px-2 py-2 border-0 bg-transparent text-center outline-none focus:bg-[#fff2f7]">
                              {PERSONAL_STATUSES.map(option => <option key={option} value={option}>{option}</option>)}
                            </select>
                          </td>
                          <td className="border border-slate-300 text-center">
                            <button type="button" onClick={() => deleteRow(task.id)} className="text-slate-400 hover:text-red-600 px-2" title="Удалить строку">✕</button>
                          </td>
                        </tr>
                      ))}
                      {tasks.length === 0 && (
                        <tr>
                          <td colSpan={6} className="border border-slate-300 py-8 text-center text-slate-400">Добавьте первую строку задачи</td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>

              <div className="min-w-0">
                <PinkStatusChart tasks={normalizedTasks} />
              </div>
            </div>
          </div>
        </div>
      </div>
      {message && (
        <div className="fixed right-5 bottom-5 bg-[#173f4c] text-white px-4 py-3 shadow-xl">
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
