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

export function TimesheetPage() {
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