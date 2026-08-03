import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/client';
import { createPortal } from 'react-dom';
import type { ProjectDto, TimeEntryDto, ExpenseDto } from '../types';
import { formatMoney, generateUUID } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';

const STATUS_CONFIG: Record<string, { label: string; class: string }> = {
  new: { label: '🆕 Новый', class: 'badge-indigo' },
  in_progress: { label: '🔄 В работе', class: 'badge-warning' },
  completed: { label: '✅ Завершён', class: 'badge-success' },
  cancelled: { label: '❌ Отменён', class: 'badge-danger' },
};

export function ProjectsPage() {
  const navigate = useNavigate();
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [entries, setEntries] = useState<TimeEntryDto[]>([]);
  const [expenses, setExpenses] = useState<ExpenseDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');

  const { can } = usePermissions();
  const canCreateProjects = can('projects', 'create');
  const [showForm, setShowForm] = useState(false);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState({
    name: '',
    client: '',
    location: '',
    productService: '',
    quantity: '1',
    deliveryDate: '',
    contract: '',
    status: 'new',
    isActive: true,
  });

  useEffect(() => {
    (async () => {
      setLoading(true);
      const [projRes, entRes, expRes] = await Promise.allSettled([
        api.get<ProjectDto[]>('/projects'),
        api.get<TimeEntryDto[]>('/entries/all'),
        api.get<ExpenseDto[]>('/expenses/all'),
      ]);
      setProjects(projRes.status === 'fulfilled' ? projRes.value.data : []);
      setEntries(entRes.status === 'fulfilled' ? entRes.value.data : []);
      setExpenses(expRes.status === 'fulfilled' ? expRes.value.data : []);
      setLoading(false);
    })();
  }, []);

  const handleCreate = async () => {
    if (!form.name.trim()) return;
    setSaving(true);
    try {
      await api.post('/projects', {
        id: generateUUID(),
        name: form.name.trim(),
        isActive: form.isActive,
        status: form.status,
        lead: '',
        revenue: 0,
        expenses: 0,
        cost: 0,
        profit: 0,
        projectNumber: '',
        subProjectNumber: '',
        client: form.client.trim(),
        location: form.location.trim(),
        productService: form.productService.trim(),
        quantity: parseInt(form.quantity) || 1,
        deliveryDate: form.deliveryDate || null,
        contract: form.contract.trim(),
        notes: '',
        projectCode: '',
        completionDate: null,
        customer: '',
        productionCost: 0,
        transportToClient: 0,
        sellingPrice: 0,
      });
      setShowForm(false);
      setForm({ name: '', client: '', location: '', productService: '', quantity: '1', deliveryDate: '', contract: '', status: 'new', isActive: true });
      // Перезагружаем список
      const [projRes] = await Promise.allSettled([api.get<ProjectDto[]>('/projects')]);
      if (projRes.status === 'fulfilled') setProjects(projRes.value.data);
    } catch (err) {
      alert('Ошибка создания проекта');
    } finally {
      setSaving(false);
    }
  };

  // Агрегируем часы и расходы по проектам
  const hoursByProject = new Map<string, number>();
  entries.forEach(e => {
    hoursByProject.set(e.projectId, (hoursByProject.get(e.projectId) || 0) + e.hours);
  });

  const expensesByProject = new Map<string, number>();
  expenses.forEach(e => {
    expensesByProject.set(e.projectId, (expensesByProject.get(e.projectId) || 0) + e.amount);
  });

  const filtered = projects.filter(p => {
    const matchSearch = p.name.toLowerCase().includes(search.toLowerCase()) ||
      p.client.toLowerCase().includes(search.toLowerCase());
    const matchStatus = statusFilter === 'all' || p.status === statusFilter;
    return matchSearch && matchStatus;
  });

  const formatHours = (h: number) => {
    const hrs = Math.floor(h);
    const mins = Math.round((h - hrs) * 60);
    return mins > 0 ? `${hrs}ч ${mins}м` : `${hrs}ч`;
  };

  return (
    <div className="space-y-6 max-w-6xl mx-auto">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">📁 Проекты</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            Всего: {filtered.length} • Активных: {filtered.filter(p => p.isActive).length}
          </p>
        </div>
        {canCreateProjects && (
          <button onClick={() => setShowForm(!showForm)} className="btn-primary px-5 py-2.5 shadow-indigo-200 shadow-md">
            {showForm ? '✕ Закрыть' : '＋ Новый проект'}
          </button>
        )}
      </div>

      {/* 🌲 PROLES MODAL: Новый проект */}
      {showForm && createPortal(
        <div className="proles-modal-backdrop" onClick={() => !saving && setShowForm(false)}>
          <div className="proles-modal" onClick={(e) => e.stopPropagation()} style={{ maxWidth: '36rem' }}>
            <div className="proles-modal-header">
              <div className="proles-modal-title">
                <div className="proles-modal-icon">📁</div>
                <div>
                  <div>Новый проект</div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                    Создание карточки проекта
                  </div>
                </div>
              </div>
              <button onClick={() => setShowForm(false)} className="proles-modal-close">✕</button>
            </div>
            <div className="proles-modal-body">
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Основная информация</div>
                <div className="proles-input-group" style={{ marginBottom: '0.75rem' }}>
                  <label>Название проекта *</label>
                  <input type="text" placeholder="Например: Поставка оборудования ООО Ромашка" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className="input" autoFocus />
                </div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group">
                    <label>Клиент (заказчик)</label>
                    <input type="text" placeholder="ООО Ромашка" value={form.client} onChange={(e) => setForm({ ...form, client: e.target.value })} className="input" />
                  </div>
                  <div className="proles-input-group">
                    <label>Город / Локация</label>
                    <input type="text" placeholder="Москва" value={form.location} onChange={(e) => setForm({ ...form, location: e.target.value })} className="input" />
                  </div>
                </div>
              </div>
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Бизнес-детали</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group" style={{ gridColumn: 'span 2' }}>
                    <label>Товар или услуга</label>
                    <input type="text" placeholder="Поставка оборудования" value={form.productService} onChange={(e) => setForm({ ...form, productService: e.target.value })} className="input" />
                  </div>
                  <div className="proles-input-group">
                    <label>Количество</label>
                    <input type="number" step="1" min="1" placeholder="1" value={form.quantity} onChange={(e) => setForm({ ...form, quantity: e.target.value })} className="input" />
                  </div>
                  <div className="proles-input-group">
                    <label>Срок поставки</label>
                    <input type="date" value={form.deliveryDate} onChange={(e) => setForm({ ...form, deliveryDate: e.target.value })} className="input" />
                  </div>
                  <div className="proles-input-group" style={{ gridColumn: 'span 2' }}>
                    <label>Договор</label>
                    <input type="text" placeholder="№123 от 01.01.2026" value={form.contract} onChange={(e) => setForm({ ...form, contract: e.target.value })} className="input" />
                  </div>
                </div>
              </div>
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Настройки</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group">
                    <label>Статус</label>
                    <select value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value })} className="input bg-white dark:bg-slate-900">
                      <option value="new">🆕 Новый</option>
                      <option value="in_progress">🔄 В работе</option>
                      <option value="completed">✅ Завершён</option>
                      <option value="cancelled">❌ Отменён</option>
                    </select>
                  </div>
                  <div className="proles-input-group" style={{ display: 'flex', alignItems: 'center' }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', cursor: 'pointer' }}>
                      <input type="checkbox" checked={form.isActive} onChange={(e) => setForm({ ...form, isActive: e.target.checked })} className="rounded accent-emerald-600" />
                      <span style={{ fontSize: '0.875rem', fontWeight: 600 }}>Активный проект</span>
                    </label>
                  </div>
                </div>
              </div>
            </div>
            <div className="proles-modal-footer">
              <button onClick={() => setShowForm(false)} className="proles-btn-cancel">Отмена</button>
              <button onClick={handleCreate} disabled={saving || !form.name.trim()} className="proles-btn-save">
                {saving ? '⏳ Создание...' : '💾 Создать'}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* Фильтры */}
      <div className="card p-4 flex flex-col sm:flex-row gap-3">
        <div className="relative flex-1">
          <span className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400">🔍</span>
          <input type="text" placeholder="Поиск по названию или клиенту..." value={search} onChange={(e) => setSearch(e.target.value)} className="input pl-10" />
        </div>
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} className="input w-full sm:w-48 bg-white dark:bg-slate-900">
          <option value="all">Все статусы</option>
          <option value="new">🆕 Новый</option>
          <option value="in_progress">🔄 В работе</option>
          <option value="completed">✅ Завершён</option>
          <option value="cancelled">❌ Отменён</option>
        </select>
      </div>

      {/* Сетка проектов */}
      {loading ? (
        <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>
      ) : filtered.length === 0 ? (
        <div className="text-center py-20 text-slate-400 dark:text-slate-500">Проекты не найдены</div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {filtered.map(project => {
            const st = STATUS_CONFIG[project.status] || STATUS_CONFIG.new;
            const totalHours = hoursByProject.get(project.id) || 0;
            const totalExpenses = expensesByProject.get(project.id) || 0;

            return (
              <div key={project.id} onClick={() => navigate(`/projects/${project.id}`)} className="card p-5 flex flex-col gap-3 hover:shadow-lg hover:-translate-y-1 transition-all duration-300 group cursor-pointer">
                {/* Шапка */}
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <h3 className="font-bold text-slate-900 dark:text-slate-100 truncate group-hover:text-indigo-600 dark:group-hover:text-indigo-400 transition-colors">
                      {project.name}
                    </h3>
                    {(project.client || project.location) && (
                      <div className="text-xs text-slate-500 dark:text-slate-400 truncate mt-0.5 flex items-center gap-1">
                        📍 {[project.client, project.location].filter(Boolean).join(' • ')}
                      </div>
                    )}
                  </div>
                  <span className={`flex-shrink-0 ${st.class}`}>{st.label}</span>
                </div>

                {/* Метрики: Расходы + Часы */}
                <div className="grid grid-cols-2 gap-2 py-3 border-t border-b border-slate-100 dark:border-slate-800">
                  <div className="text-center p-2 bg-orange-50 dark:bg-orange-950/20 rounded-lg">
                    <div className="text-[10px] text-orange-600 dark:text-orange-400 uppercase font-bold">Расходы</div>
                    <div className="text-sm font-bold text-orange-800 dark:text-orange-300 truncate mt-0.5">
                      {totalExpenses > 0 ? formatMoney(totalExpenses) : '—'}
                    </div>
                  </div>
                  <div className="text-center p-2 bg-blue-50 dark:bg-blue-950/20 rounded-lg">
                    <div className="text-[10px] text-blue-600 dark:text-blue-400 uppercase font-bold">Часы</div>
                    <div className="text-sm font-bold text-blue-800 dark:text-blue-300 truncate mt-0.5">
                      {totalHours > 0 ? formatHours(totalHours) : '—'}
                    </div>
                  </div>
                </div>

                {/* Доп. инфо */}
                <div className="space-y-1 text-xs text-slate-500 dark:text-slate-400">
                  {project.productService && <div className="truncate">🎯 {project.productService}</div>}
                  {project.contract && <div className="truncate">📄 {project.contract}</div>}
                  {project.lead && <div className="truncate">👤 Лид: {project.lead}</div>}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}