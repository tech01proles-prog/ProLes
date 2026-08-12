import { useState, useEffect, useCallback } from 'react';
import { createPortal } from 'react-dom';
import api from '../api/client';
import type {
  SalaryComponentDto, SalaryBreakdownResponse, SalaryRecordDto,
  PayrollExportResponse, UserDto, ProjectDto
} from '../types';
import { generateUUID, formatMoney, monthName } from '../lib/utils';
import { exportPayrollToExcel, exportPayrollToPdf, exportMySalaryToExcel, exportMySalaryToPdf } from '../lib/export';
import { usePermissions } from '../hooks/usePermissions';

const COMPONENT_TYPES = {
  FIXED: { label: '💼 Фикс', color: 'bg-blue-100 text-blue-700 border-blue-200' },
  HOURLY: { label: '⏱ Почасовая', color: 'bg-indigo-100 text-indigo-700 border-indigo-200' },
  PIECE: { label: '🔨 Сдельная', color: 'bg-orange-100 text-orange-700 border-orange-200' },
  BONUS: { label: '🎁 Бонус', color: 'bg-emerald-100 text-emerald-700 border-emerald-200' },
  PENALTY: { label: '⚠️ Штраф', color: 'bg-red-100 text-red-700 border-red-200' },
  MARGIN_PERCENT: { label: '% от маржи', color: 'bg-purple-100 text-purple-700 border-purple-200' },
};

export function PayrollPage() {
  const [user, setUser] = useState<UserDto | null>(null);
  const [isAdmin, setIsAdmin] = useState(false);
  const [isManager, setIsManager] = useState(false);
  const [tab, setTab] = useState<'my' | 'all'>('my');

  // Личные данные
  const [myComponents, setMyComponents] = useState<SalaryComponentDto[]>([]);
  const [myRecords, setMyRecords] = useState<SalaryRecordDto[]>([]);
  const [breakdown, setBreakdown] = useState<SalaryBreakdownResponse | null>(null);
  const [calcPeriod, setCalcPeriod] = useState(() => {
    const d = new Date();
    return { year: d.getFullYear(), month: d.getMonth() + 1 };
  });

  // Админ данные
  const [allRecords, setAllRecords] = useState<SalaryRecordDto[]>([]);
  const [exportData, setExportData] = useState<PayrollExportResponse | null>(null);
  const [allUsers, setAllUsers] = useState<UserDto[]>([]);
  const [allProjects, setAllProjects] = useState<ProjectDto[]>([]);
  const [exportPeriod, setExportPeriod] = useState(() => {
    const d = new Date();
    return { year: d.getFullYear(), month: d.getMonth() + 1 };
  });

  // Форма добавления компонента
  const [showForm, setShowForm] = useState(false);
  const [editingComponentId, setEditingComponentId] = useState<string | null>(null);
  const [editingForUserId, setEditingForUserId] = useState<string | null>(null);
  const [form, setForm] = useState({
    type: 'HOURLY' as keyof typeof COMPONENT_TYPES,
    amount: '',
    projectId: '',
    ratePerHour: '',
    ratePerUnit: '',
    description: '',
    effectiveFrom: new Date().toISOString().slice(0, 10),
  });
  const [loading, setLoading] = useState(true);
  const [calculating, setCalculating] = useState(false);
  const [saving, setSaving] = useState(false);

  const { can } = usePermissions();

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) {
      try {
        const u = JSON.parse(stored);
        setUser(u);
        setIsAdmin(['admin', 'director', 'superadmin'].includes(u.role));
        setIsManager(u.role === 'manager');
      } catch {}
    }
  }, []);

  const loadMyData = useCallback(async () => {
    if (!user) return;
    setLoading(true);

    const [projRes, usersRes] = await Promise.allSettled([
      api.get<ProjectDto[]>('/projects'),
      isAdmin ? api.get<UserDto[]>('/users') : Promise.resolve({ data: [] }),
    ]);
    setAllProjects(projRes.status === 'fulfilled' ? projRes.value.data : []);
    setAllUsers(usersRes.status === 'fulfilled' ? usersRes.value.data : []);

    try {
      const [compRes, recRes] = await Promise.allSettled([
        api.get<SalaryComponentDto[]>(`/payroll/components/${user.id}`),
        api.get<SalaryRecordDto[]>('/payroll/records'),
      ]);
      setMyComponents(compRes.status === 'fulfilled' ? compRes.value.data : []);
      if (recRes.status === 'fulfilled') {
        setMyRecords(recRes.value.data.filter(r => r.userId === user.id));
        setAllRecords(recRes.value.data);
      }
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [user]);

  const loadAdminData = useCallback(async () => {
    if (!isAdmin) return;
    setLoading(true);
    try {
      const [usersRes, projRes, recRes] = await Promise.allSettled([
        api.get<UserDto[]>('/users'),
        api.get<ProjectDto[]>('/projects'),
        api.get<SalaryRecordDto[]>('/payroll/records'),
      ]);
      setAllUsers(usersRes.status === 'fulfilled' ? usersRes.value.data : []);
      setAllProjects(projRes.status === 'fulfilled' ? projRes.value.data : []);
      setAllRecords(recRes.status === 'fulfilled' ? recRes.value.data : []);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [isAdmin]);

  useEffect(() => {
    if (tab === 'my') loadMyData();
    else loadAdminData();
  }, [tab, loadMyData, loadAdminData]);

  const handleCalculate = async () => {
    if (!user) return;
    setCalculating(true);
    try {
      const { data } = await api.post<SalaryBreakdownResponse>('/payroll/calculate', {
        userId: user.id,
        year: calcPeriod.year,
        month: calcPeriod.month,
      });
      setBreakdown(data);
      await loadMyData();
    } catch (err) {
      alert('Ошибка расчёта зарплаты');
    } finally {
      setCalculating(false);
    }
  };

  const handleLoadExport = async () => {
    setLoading(true);
    try {
      const { data } = await api.get<PayrollExportResponse>('/payroll/export', {
        params: { year: exportPeriod.year, month: exportPeriod.month },
      });
      setExportData(data);
    } catch (err) {
      alert('Ошибка загрузки экспорта');
    } finally {
      setLoading(false);
    }
  };

  const handleAddComponent = async () => {
    const targetUserId = editingForUserId || user?.id;
    if (!targetUserId) return;
    setSaving(true);
    try {
      const payload = {
        id: editingComponentId || generateUUID(),
        userId: targetUserId,
        type: form.type,
        amount: parseFloat(form.amount) || 0,
        projectId: form.projectId || null,
        ratePerHour: form.ratePerHour ? parseFloat(form.ratePerHour) : null,
        ratePerUnit: form.ratePerUnit ? parseFloat(form.ratePerUnit) : null,
        description: form.description,
        effectiveFrom: form.effectiveFrom,
        effectiveTo: null,
        isActive: true,
      };
      
      if (editingComponentId) {
        // Редактирование существующего компонента
        await api.put(`/payroll/components/${editingComponentId}`, payload);
      } else {
        // Создание нового компонента
        await api.post('/payroll/components', payload);
      }
      
      setShowForm(false);
      setEditingComponentId(null);
      setEditingForUserId(null);
      setForm({ type: 'HOURLY', amount: '', projectId: '', ratePerHour: '', ratePerUnit: '', description: '', effectiveFrom: new Date().toISOString().slice(0, 10) });
      if (tab === 'my') await loadMyData();
      else await loadAdminData();
    } catch (err) {
      alert('Ошибка сохранения компонента');
    } finally {
      setSaving(false);
    }
  };

  const handleEditComponent = (component: SalaryComponentDto) => {
    setEditingComponentId(component.id);
    setEditingForUserId(component.userId);
    setForm({
      type: component.type,
      amount: component.amount.toString(),
      projectId: component.projectId || '',
      ratePerHour: component.ratePerHour?.toString() || '',
      ratePerUnit: component.ratePerUnit?.toString() || '',
      description: component.description,
      effectiveFrom: component.effectiveFrom,
    });
    setShowForm(true);
  };

  const handleDeleteComponent = async (id: string) => {
    if (!confirm('Удалить компонент?')) return;
    await api.delete(`/payroll/components/${id}`);
    if (tab === 'my') await loadMyData();
    else await loadAdminData();
  };

  const handleUpdateStatus = async (recordId: string, status: string) => {
    await api.put(`/payroll/records/${recordId}/status`, { status });
    await loadAdminData();
    await loadMyData();
  };

  return (
    <div className="space-y-6 max-w-6xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold text-slate-900">💰 Зарплата</h1>
        <p className="text-sm text-slate-500 mt-1">Расчёт, компоненты и история выплат</p>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 bg-white rounded-xl p-1 border border-slate-200 w-fit">
        <button onClick={() => setTab('my')} className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${tab === 'my' ? 'bg-slate-900 text-white' : 'text-slate-600'}`}>
          👤 Моя зарплата
        </button>
        {isAdmin && (
          <button onClick={() => setTab('all')} className={`px-4 py-2 rounded-lg text-sm font-medium transition-all ${tab === 'all' ? 'bg-slate-900 text-white' : 'text-slate-600'}`}>
            👥 Все сотрудники
          </button>
        )}
      </div>

      {loading ? (
        <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>
      ) : tab === 'my' ? (
        <>
          {/* Калькулятор */}
          <div className="card p-6 bg-gradient-to-br from-emerald-50 to-green-50 border-emerald-200">
            <h3 className="font-bold text-slate-900 mb-4 flex items-center gap-2">
              <span className="w-1.5 h-5 bg-emerald-500 rounded-full"></span>
              Рассчитать зарплату
            </h3>
            <div className="flex flex-col sm:flex-row gap-3 items-start sm:items-end">
              <div className="space-y-1.5">
                <label className="text-xs font-medium text-slate-600">Год</label>
                <input type="number" value={calcPeriod.year} onChange={(e) => setCalcPeriod({ ...calcPeriod, year: parseInt(e.target.value) })} className="input w-32" />
              </div>
              <div className="space-y-1.5">
                <label className="text-xs font-medium text-slate-600">Месяц</label>
                <select value={calcPeriod.month} onChange={(e) => setCalcPeriod({ ...calcPeriod, month: parseInt(e.target.value) })} className="input w-40 bg-white">
                  {Array.from({ length: 12 }, (_, i) => i + 1).map(m => (
                    <option key={m} value={m}>{monthName(m)}</option>
                  ))}
                </select>
              </div>
              <button onClick={handleCalculate} disabled={calculating} className="btn-primary px-6 py-2.5">
                {calculating ? '⏳ Расчёт...' : '🧮 Рассчитать'}
              </button>
            </div>

            {breakdown && (
              <div className="mt-6 grid grid-cols-2 md:grid-cols-5 gap-3 animate-fade-in">
                <div className="bg-white rounded-xl p-3 border border-blue-100">
                  <div className="text-[10px] text-blue-600 font-bold uppercase">Фикс</div>
                  <div className="text-lg font-bold text-blue-900">{formatMoney(breakdown.fixed)}</div>
                </div>
                <div className="bg-white rounded-xl p-3 border border-indigo-100">
                  <div className="text-[10px] text-indigo-600 font-bold uppercase">Почасовая</div>
                  <div className="text-lg font-bold text-indigo-900">{formatMoney(breakdown.hourly)}</div>
                </div>
                <div className="bg-white rounded-xl p-3 border border-orange-100">
                  <div className="text-[10px] text-orange-600 font-bold uppercase">Сдельная</div>
                  <div className="text-lg font-bold text-orange-900">{formatMoney(breakdown.piece)}</div>
                </div>
                <div className="bg-white rounded-xl p-3 border border-emerald-100">
                  <div className="text-[10px] text-emerald-600 font-bold uppercase">Бонус</div>
                  <div className="text-lg font-bold text-emerald-900">{formatMoney(breakdown.bonus)}</div>
                </div>
                <div className="bg-gradient-to-br from-emerald-500 to-green-500 rounded-xl p-3 text-white col-span-2 md:col-span-1">
                  <div className="text-[10px] font-bold uppercase opacity-90">Итого</div>
                  <div className="text-2xl font-black">{formatMoney(breakdown.total)}</div>
                </div>
              </div>
            )}
          </div>

          {/* ═══════════ КОМПОНЕНТЫ ЗАРПЛАТЫ (с RBAC) ═══════════ */}
          <div>
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-sm font-bold text-slate-900 dark:text-slate-100 flex items-center gap-2">
                <span className="w-1 h-4 bg-indigo-500 rounded-full"></span>
                Мои компоненты ({myComponents.length})
              </h3>
              {/* 🔐 Кнопка видна только при наличии права payroll.create */}
              {can('payroll', 'create') && (
                <button onClick={() => { setEditingForUserId(user!.id); setShowForm(!showForm); }} className="btn-primary px-4 py-2 text-sm">
                  {showForm ? '✕' : '＋ Добавить'}
                </button>
              )}
            </div>

            {/* 🌲 PROLES MODAL: Компонент зарплаты */}
            {showForm && can('payroll', 'create') && createPortal(
              <div className="proles-modal-backdrop" onClick={() => setShowForm(false)}>
                <div className="proles-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '36rem' }}>
                  <div className="proles-modal-header">
                    <div className="proles-modal-title">
                      <div className="proles-modal-icon">💰</div>
                      <div>
                        <div>{editingComponentId 
                          ? '✏️ Редактирование компонента'
                          : editingForUserId && editingForUserId !== user?.id
                          ? `Компонент для: ${allUsers.find(u => u.id === editingForUserId)?.name || ''}`
                          : 'Новый компонент'}</div>
                        <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                          Настройка параметров оплаты
                        </div>
                      </div>
                    </div>
                    <button onClick={() => setShowForm(false)} className="proles-modal-close">✕</button>
                  </div>
                  <div className="proles-modal-body">
                    <div className="proles-modal-section">
                      <div className="proles-modal-section-title">Тип компонента</div>
                      <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-6 gap-2">
                        {Object.entries(COMPONENT_TYPES).map(([key, cfg]) => {
                          // 🔐 MARGIN_PERCENT доступен только менеджерам и админам
                          const isMarginPercent = key === 'MARGIN_PERCENT';
                          const canSelectMargin = isAdmin || isManager;
                          
                          if (isMarginPercent && !canSelectMargin) return null;
                          
                          return (
                            <button
                              key={key}
                              onClick={() => setForm({ ...form, type: key as any })}
                              style={{
                                padding: '0.75rem',
                                borderRadius: '0.75rem',
                                border: form.type === key ? '2px solid #10b981' : '2px solid #e2e8f0',
                                background: form.type === key ? 'linear-gradient(135deg, rgba(16, 185, 129, 0.1) 0%, rgba(5, 150, 105, 0.1) 100%)' : 'transparent',
                                fontSize: '0.75rem',
                                fontWeight: 600,
                                cursor: canSelectMargin || !isMarginPercent ? 'pointer' : 'not-allowed',
                                transition: 'all 0.15s',
                                textAlign: 'left',
                                color: form.type === key ? '#047857' : '#64748b',
                                opacity: isMarginPercent && !canSelectMargin ? 0.5 : 1
                              }}
                              disabled={isMarginPercent && !canSelectMargin}
                              title={isMarginPercent && !canSelectMargin ? 'Доступно только менеджерам' : ''}
                            >
                              {cfg.label}
                            </button>
                          );
                        })}
                      </div>
                    </div>
                    <div className="proles-modal-section">
                      <div className="proles-modal-section-title">Параметры</div>
                      <div className="proles-modal-grid">
                        {(form.type === 'FIXED' || form.type === 'BONUS' || form.type === 'PENALTY') && (
                          <div className="proles-input-group" style={{ gridColumn: 'span 2' }}>
                            <label>{form.type === 'FIXED' ? 'Фиксированная сумма' : form.type === 'PENALTY' ? 'Сумма штрафа' : 'Сумма бонуса'}</label>
                            <input type="number" step="0.01" placeholder="0.00" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} className="input" />
                          </div>
                        )}
                        {form.type === 'MARGIN_PERCENT' && (
                          <div className="proles-input-group" style={{ gridColumn: 'span 2' }}>
                            <label>% от маржи</label>
                            <input type="number" step="0.01" placeholder="0.00" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} className="input" />
                          </div>
                        )}
                        {form.type === 'HOURLY' && (
                          <div className="proles-input-group" style={{ gridColumn: 'span 2' }}>
                            <label>Ставка за час</label>
                            <input type="number" step="0.01" placeholder="0.00" value={form.ratePerHour} onChange={(e) => setForm({ ...form, ratePerHour: e.target.value })} className="input" />
                          </div>
                        )}
                        {form.type === 'PIECE' && (
                          <>
                            <div className="proles-input-group">
                              <label>Ставка за единицу</label>
                              <input type="number" step="0.01" placeholder="0.00" value={form.ratePerUnit} onChange={(e) => setForm({ ...form, ratePerUnit: e.target.value })} className="input" />
                            </div>
                            <div className="proles-input-group">
                              <label>Количество единиц</label>
                              <input type="number" step="1" placeholder="0" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} className="input" />
                            </div>
                          </>
                        )}
                        <div className="proles-input-group" style={{ gridColumn: 'span 2' }}>
                          <label>Проект (опционально)</label>
                          <select value={form.projectId} onChange={(e) => setForm({ ...form, projectId: e.target.value })} className="input bg-white dark:bg-slate-900">
                            <option value="">Без проекта</option>
                            {allProjects.filter(p => p.isActive).map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
                          </select>
                        </div>
                        <div className="proles-input-group">
                          <label>Действует с</label>
                          <input type="date" value={form.effectiveFrom} onChange={(e) => setForm({ ...form, effectiveFrom: e.target.value })} className="input" />
                        </div>
                        <div className="proles-input-group" style={{ gridColumn: 'span 2' }}>
                          <label>Описание</label>
                          <input type="text" placeholder="Например: Ставка за выходные дни" value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} className="input" />
                        </div>
                      </div>
                    </div>
                  </div>
                  <div className="proles-modal-footer">
                    <button onClick={() => setShowForm(false)} className="proles-btn-cancel">Отмена</button>
                    <button onClick={handleAddComponent} disabled={saving} className="proles-btn-save">
                      {saving ? '⏳...' : '💾 Сохранить'}
                    </button>
                  </div>
                </div>
              </div>,
              document.body
            )}

            {/* Список компонентов */}
            {myComponents.length === 0 ? (
              <div className="card p-8 text-center text-slate-400 dark:text-slate-500 border-dashed">
                <div className="text-4xl mb-2 opacity-50">💼</div>
                <p>Нет компонентов зарплаты</p>
                {!can('payroll', 'create') && <p className="text-xs mt-1">Нет прав на добавление</p>}
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {myComponents.map(c => {
                  const cfg = COMPONENT_TYPES[c.type as keyof typeof COMPONENT_TYPES];
                  // 🔐 Редактирование/удаление: свои компоненты или при наличии прав
                  const canEdit = can('payroll', 'edit') || c.userId === user?.id;
                  const canDelete = can('payroll', 'delete') || c.userId === user?.id;
                  return (
                    <div key={c.id} className="card p-4 group hover:shadow-md transition-all">
                      <div className="flex items-start justify-between mb-2">
                        <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold border ${cfg.color}`}>{cfg.label}</span>
                        <div className="flex gap-1">
                          {canEdit && (
                            <button onClick={() => handleEditComponent(c)} className="opacity-0 group-hover:opacity-100 text-blue-500 hover:text-blue-700 transition-opacity text-sm" title="Редактировать">✏️</button>
                          )}
                          {canDelete && (
                            <button onClick={() => handleDeleteComponent(c.id)} className="opacity-0 group-hover:opacity-100 text-red-500 hover:text-red-700 transition-opacity text-sm" title="Удалить">🗑</button>
                          )}
                        </div>
                      </div>
                      <div className="text-2xl font-bold text-slate-900 dark:text-slate-100">{formatMoney(c.amount)}</div>
                      {c.ratePerHour != null && c.ratePerHour > 0 && (
                        <div className="text-xs text-slate-500 dark:text-slate-400 mt-1">⏱ {formatMoney(c.ratePerHour)}/час</div>
                      )}
                      {c.ratePerUnit != null && c.ratePerUnit > 0 && (
                        <div className="text-xs text-slate-500 dark:text-slate-400 mt-1">🔨 {formatMoney(c.ratePerUnit)}/ед.</div>
                      )}
                      {c.description && <div className="text-xs text-slate-600 dark:text-slate-400 mt-1 truncate">{c.description}</div>}
                      <div className="text-[10px] text-slate-400 mt-2 pt-2 border-t border-slate-100 dark:border-slate-800">
                        С {new Date(c.effectiveFrom).toLocaleDateString('ru-RU')}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {/* История расчётов */}
          <div>
            <div className="flex items-center justify-between mb-3">
              <h3 className="text-sm font-bold text-slate-900 mb-3 flex items-center gap-2">
                <span className="w-1 h-4 bg-indigo-500 rounded-full"></span>
                История расчётов
              </h3>
              {myRecords.length > 0 && (
                <div className="flex gap-1">
                  <button
                      onClick={async () => {
                        try {
                          exportMySalaryToExcel(myRecords);
                        } catch (err) {
                          console.error(err);
                          alert('Ошибка формирования Excel');
                        }
                      }}
                      className="btn-ghost px-2 py-1 text-xs gap-1 text-emerald-600 dark:text-emerald-400"
                      title="Экспорт в Excel"
                    >
                      📊 XLS
                    </button>
                    <button
                      onClick={async () => {
                        try {
                          await exportMySalaryToPdf(myRecords);
                        } catch (err) {
                          console.error(err);
                          alert('Ошибка формирования PDF');
                        }
                      }}
                      className="btn-ghost px-2 py-1 text-xs gap-1 text-red-600 dark:text-red-400"
                      title="Экспорт в PDF"
                    >
                      📄 PDF
                    </button>
                </div>
              )}
            </div>
            {myRecords.length === 0 ? (
              <div className="card p-6 text-center text-slate-400 text-sm">Нет расчётов</div>
            ) : (
              <div className="space-y-2">
                {myRecords.map(r => (
                  <div key={r.id} className="card p-4 flex items-center justify-between">
                    <div>
                      <div className="font-bold text-slate-900">{monthName(r.month)} {r.year}</div>
                      <div className="text-xs text-slate-500">
                        Фикс: {formatMoney(r.fixed)} • Часы: {formatMoney(r.hourly)} • Сдельная: {formatMoney(r.piece)} • Бонус: {formatMoney(r.bonus)}
                      </div>
                    </div>
                    <div className="text-right">
                      <div className="text-xl font-bold text-emerald-700">{formatMoney(r.total)}</div>
                      <span className={`text-[10px] px-2 py-0.5 rounded-full font-bold ${
                        r.status === 'paid' ? 'bg-green-100 text-green-700' :
                        r.status === 'approved' ? 'bg-blue-100 text-blue-700' :
                        'bg-slate-100 text-slate-700'
                      }`}>
                        {r.status === 'paid' ? '✓ Выплачено' : r.status === 'approved' ? '✓ Утверждено' : 'Черновик'}
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </>
      ) : (
        // ═══════════ АДМИН-ВКЛАДКА ═══════════
        <>
          <div className="card p-5">
            <h3 className="font-bold text-slate-900 dark:text-slate-100 mb-3">📊 Экспорт за период</h3>
            <div className="flex flex-col sm:flex-row gap-3 items-start sm:items-end">
              <input type="number" value={exportPeriod.year} onChange={(e) => setExportPeriod({ ...exportPeriod, year: parseInt(e.target.value) })} className="input w-32" />
              <select value={exportPeriod.month} onChange={(e) => setExportPeriod({ ...exportPeriod, month: parseInt(e.target.value) })} className="input w-40 bg-white dark:bg-slate-900">
                {Array.from({ length: 12 }, (_, i) => i + 1).map(m => <option key={m} value={m}>{monthName(m)}</option>)}
              </select>
              <button onClick={handleLoadExport} className="btn-primary px-5 py-2.5">📥 Загрузить данные</button>
            </div>

            {/* 🆕 Кнопки экспорта */}
            {exportData && (
              <div className="mt-4 pt-4 border-t border-slate-200 dark:border-slate-700 flex flex-wrap gap-2">
                <button
                  onClick={async () => {
                    try {
                      exportPayrollToExcel(exportData.employees, exportData.year, exportData.month);
                    } catch (err) {
                      console.error('Excel export failed:', err);
                      alert('Ошибка формирования Excel');
                    }
                  }}
                  className="btn-outline px-4 py-2 text-sm gap-2 border-emerald-300 text-emerald-700 dark:text-emerald-400 dark:border-emerald-700 hover:bg-emerald-50 dark:hover:bg-emerald-950/30"
                >
                  📊 Скачать Excel
                </button>
                <button
                  onClick={async () => {
                    try {
                      await exportPayrollToPdf(exportData.employees, exportData.year, exportData.month);
                    } catch (err) {
                      console.error('PDF export failed:', err);
                      alert('Ошибка формирования PDF');
                    }
                  }}
                  className="btn-outline px-4 py-2 text-sm gap-2 border-red-300 text-red-700 dark:text-red-400 dark:border-red-700 hover:bg-red-50 dark:hover:bg-red-950/30"
                >
                  📄 Скачать PDF
                </button>
                <div className="ml-auto text-xs text-slate-500 dark:text-slate-400 self-center">
                  💡 {exportData.employees.length} сотрудников в отчёте
                </div>
              </div>
            )}
          </div>

          {exportData && (
            <div className="card overflow-hidden">
              <div className="p-4 bg-slate-50 border-b border-slate-200 flex items-center justify-between">
                <h3 className="font-bold text-slate-900">
                  📋 {monthName(exportData.month)} {exportData.year} — {exportData.employees.length} сотрудников
                </h3>
                <div className="text-sm font-bold text-emerald-700">
                  Σ {formatMoney(exportData.employees.reduce((s, e) => s + e.salaryTotal, 0))}
                </div>
              </div>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-slate-50 border-b border-slate-200">
                    <tr>
                      <th className="text-left p-3 font-semibold text-slate-600">Сотрудник</th>
                      <th className="text-right p-3 font-semibold text-slate-600">Часы</th>
                      <th className="text-right p-3 font-semibold text-slate-600">Дней</th>
                      <th className="text-right p-3 font-semibold text-slate-600">Зарплата</th>
                      <th className="text-right p-3 font-semibold text-slate-600">Расходы</th>
                      <th className="text-center p-3 font-semibold text-slate-600">Ком.</th>
                    </tr>
                  </thead>
                  <tbody>
                    {exportData.employees.map(emp => (
                      <tr key={emp.userId} className="border-b border-slate-100 hover:bg-slate-50">
                        <td className="p-3">
                          <div className="font-medium text-slate-900">{emp.name}</div>
                          <div className="text-xs text-slate-500">{emp.position}</div>
                        </td>
                        <td className="p-3 text-right tabular-nums">{emp.totalHours.toFixed(1)}</td>
                        <td className="p-3 text-right tabular-nums">{emp.workDays}</td>
                        <td className="p-3 text-right font-bold text-emerald-700 tabular-nums">{formatMoney(emp.salaryTotal)}</td>
                        <td className="p-3 text-right text-orange-600 tabular-nums">{formatMoney(emp.expensesTotal)}</td>
                        <td className="p-3 text-center">{emp.tripsCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          <div>
            <h3 className="text-sm font-bold text-slate-900 mb-3">📜 Все расчёты</h3>
            <div className="space-y-2">
              {allRecords.map(r => (
                <div key={r.id} className="card p-4 flex items-center justify-between">
                  <div>
                    <div className="font-bold text-slate-900">{r.userName}</div>
                    <div className="text-xs text-slate-500">{monthName(r.month)} {r.year} • Итого: <span className="font-bold text-emerald-700">{formatMoney(r.total)}</span></div>
                  </div>
                  <div className="flex gap-1">
                    <button onClick={() => handleUpdateStatus(r.id, 'approved')} className="px-2 py-1 text-xs rounded bg-blue-50 text-blue-700 hover:bg-blue-100">Утвердить</button>
                    <button onClick={() => handleUpdateStatus(r.id, 'paid')} className="px-2 py-1 text-xs rounded bg-green-50 text-green-700 hover:bg-green-100">Выплачено</button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  );
}