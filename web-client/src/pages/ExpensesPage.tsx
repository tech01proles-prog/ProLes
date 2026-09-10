import { useState, useEffect, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { createPortal } from 'react-dom';
import api from '../api/client';
import type { IncomeDto, ExpenseDto, ProjectDto, UserDto } from '../types';
import { generateUUID, formatDate, formatMoney } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';
import { ScopeTabs } from '../components/ScopeTabs';
import * as XLSX from 'xlsx';
import { saveAs } from 'file-saver';

const INCOME_TYPES = [
  { key: 'HOUSEHOLD', label: 'Хоз.нужды', icon: '🏠', color: 'bg-blue-100 text-blue-700 border-blue-200 dark:bg-blue-950/40 dark:text-blue-400 dark:border-blue-900' },
  { key: 'CARD', label: 'По карте', icon: '💳', color: 'bg-purple-100 text-purple-700 border-purple-200 dark:bg-purple-950/40 dark:text-purple-400 dark:border-purple-900' },
  { key: 'CASH', label: 'Наличными', icon: '💵', color: 'bg-emerald-100 text-emerald-700 border-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-400 dark:border-emerald-900' },
];

const EXPENSE_TYPES = [
  { key: 'HOUSEHOLD', label: 'Хоз.нужды', icon: '🏠' },
  { key: 'CONTRACTORS', label: 'Подрядчики', icon: '👷' },
  { key: 'ROAD', label: 'Дорога', icon: '🚗' },
  { key: 'PER_DIEM', label: 'Суточные', icon: '💵' },
  { key: 'PER_DIEM_EXTRA', label: 'Суточные сверх.', icon: '🔴' },
  { key: 'CASH', label: 'Наличные', icon: '💰' },
  { key: 'CARD', label: 'Карта', icon: '💳' },
  { key: 'OTHER', label: 'Прочее', icon: '📦' },
];

const findIncomeType = (key: string) => INCOME_TYPES.find(t => t.key === key);
const findExpenseType = (key: string) => EXPENSE_TYPES.find(t => t.key === key);

type ReportPreset = '1' | '2' | '3';
const PER_DIEM_TYPES = new Set(['PER_DIEM']);
const EXTRA_PER_DIEM_TYPES = new Set(['PER_DIEM_EXTRA']);
const isPerDiem = (entry: CombinedEntry) =>
  entry.type === 'EXPENSE' && PER_DIEM_TYPES.has(entry.subcategory || '');
const isExtraPerDiem = (entry: CombinedEntry) =>
  entry.type === 'EXPENSE' && (
    EXTRA_PER_DIEM_TYPES.has(entry.subcategory || '') ||
    (entry.name || '').toLowerCase().replace(/ё/g, 'е').includes('суточные сверх')
  );
const isHouseholdExpense = (entry: CombinedEntry) => {
  if (entry.type !== 'EXPENSE') return false;
  const type = (entry.subcategory || '').toUpperCase();
  const text = `${entry.name || ''} ${entry.comment || ''}`.toLowerCase().replace(/ё/g, 'е');
  return type === 'HOUSEHOLD' || /хоз\s*\.?\s*нужд/.test(text);
};

interface CombinedEntry {
  id: string;
  type: 'INCOME' | 'EXPENSE';
  userId: string;
  userName?: string;
  projectId: string;
  projectName: string;
  date: string;
  name: string;
  category: string;
  subcategory?: string;       // 🆕 Подкатегория типа расхода
  amount: number;
  currency: string;
  comment: string;
  hasReceipt?: boolean;
  entryCategory: 'WORK' | 'PERSONAL';  // 🆕 Надкатегория
}

interface ExpenseReceipt {
  id: string;
  expenseId: string;
  url: string;
  fileName: string;
  uploadedAt: number;
}

const fileToBase64 = (file: File): Promise<string> => new Promise((resolve, reject) => {
  const reader = new FileReader();
  reader.onload = () => {
    const value = String(reader.result || '');
    resolve(value.includes(',') ? value.split(',')[1] : value);
  };
  reader.onerror = () => reject(reader.error || new Error('Не удалось прочитать файл'));
  reader.readAsDataURL(file);
});


const ReceiptIcon = ({ className = '' }: { className?: string }) => (
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">
    <path d="M6 2h9l3 3v15l-3-1.5L12 20l-3-1.5L6 20V2Z" />
    <path d="M9 8h6M9 12h6M9 16h4" />
  </svg>
);


function PendingReceiptPreview({ file }: { file: File }) {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!file.type.startsWith('image/')) return;
    const objectUrl = URL.createObjectURL(file);
    setUrl(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [file]);

  if (url) {
    return <img src={url} alt={file.name} className="w-16 h-16 rounded-md object-cover border border-slate-200 dark:border-slate-700 shrink-0" />;
  }
  return <div className="w-16 h-16 rounded-md border border-slate-200 dark:border-slate-700 flex items-center justify-center text-2xl shrink-0">📄</div>;
}

export function ExpensesPage() {
  const [searchParams] = useSearchParams();
  const [user, setUser] = useState<UserDto | null>(null);

  useEffect(() => {
    const stored = localStorage.getItem('proles_user');
    if (stored) {
      try { setUser(JSON.parse(stored)); } catch { /* ignore malformed cached user */ }
    }
  }, []);
  const { can } = usePermissions();
  const canViewAll = can('expenses_all', 'view');

  const [scope, setScope] = useState<'my' | 'all'>('my');
  const effectiveScope = canViewAll ? scope : 'my';

  const [myIncomes, setMyIncomes] = useState<IncomeDto[]>([]);
  const [allIncomes, setAllIncomes] = useState<IncomeDto[]>([]);
  const [myExpenses, setMyExpenses] = useState<ExpenseDto[]>([]);
  const [allExpenses, setAllExpenses] = useState<ExpenseDto[]>([]);
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [users, setUsers] = useState<UserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [formType, setFormType] = useState<'INCOME' | 'EXPENSE'>('INCOME');

  const [sortField, setSortField] = useState<'date' | 'amount' | 'type'>('date');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');
  const [filterUser, setFilterUser] = useState(searchParams.get('userId') || 'all');
  const [filterEntryType, setFilterEntryType] = useState<'all' | 'INCOME' | 'EXPENSE'>('all');
  const [hidePerDiem, setHidePerDiem] = useState(false);
  const [filterReceipt, setFilterReceipt] = useState<'all' | 'with' | 'without'>('all');
  const [filterCategory, setFilterCategory] = useState<'all' | 'WORK' | 'PERSONAL'>('all');  // 🆕 Фильтр по надкатегории
  const [filterSubcategory, setFilterSubcategory] = useState('all');  // 🆕 Фильтр по подкатегории (типу расхода)
  const [reportPreset, setReportPreset] = useState<ReportPreset | null>(null);
  
  // Автоматически переключаем на 'all' если в URL есть userId или scope=all
  useEffect(() => {
    const userIdFromUrl = searchParams.get('userId');
    const scopeFromUrl = searchParams.get('scope');
    if ((userIdFromUrl || scopeFromUrl === 'all') && canViewAll) {
      setScope('all');
      setFilterUser(userIdFromUrl || 'all');
    }
  }, [searchParams, canViewAll]);
  
  // Фильтр по датам
  const now = new Date();
  const currentMonthStart = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`;
  const currentMonthEnd = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-31`;
  const [dateFrom, setDateFrom] = useState(searchParams.get('dateFrom') || currentMonthStart);
  const [dateTo, setDateTo] = useState(searchParams.get('dateTo') || currentMonthEnd);
  const [datePreset, setDatePreset] = useState<'current' | 'last' | 'custom'>(
    searchParams.get('dateFrom') ? 'custom' : 'current'
  );

  const [form, setForm] = useState({
    projectId: '',
    type: 'ROAD',
    amount: '',
    currency: 'RUB',
    date: new Date().toISOString().slice(0, 10),
    comment: '',
    name: '',
  });
  const [saving, setSaving] = useState(false);
  const [pendingReceiptFiles, setPendingReceiptFiles] = useState<File[]>([]);
  const [receiptViewerExpenseId, setReceiptViewerExpenseId] = useState<string | null>(null);
  const [receiptViewerItems, setReceiptViewerItems] = useState<ExpenseReceipt[]>([]);
  const [receiptLoading, setReceiptLoading] = useState(false);
  const loadReceipts = useCallback(async (expenseId: string) => {
    setReceiptLoading(true);
    try {
      const res = await api.get<ExpenseReceipt[]>('/expenses/receipts', { params: { expenseId } });
      setReceiptViewerItems(res.data);
      setReceiptViewerExpenseId(expenseId);
    } catch {
      alert('Не удалось загрузить прикреплённые чеки');
    } finally {
      setReceiptLoading(false);
    }
  }, []);

  const uploadFilesToExpense = useCallback(async (expenseId: string, files: File[]) => {
    for (const file of files) {
      const base64 = await fileToBase64(file);
      await api.post('/expenses/upload-attachment', {
        expenseId,
        fileBase64: base64,
        fileName: file.name,
        mimeType: file.type || 'application/octet-stream',
      });
    }
  }, []);

  const removeReceipt = useCallback(async (receiptId: string) => {
    if (!confirm('Удалить этот чек?')) return;
    try {
      await api.delete('/expenses/receipt', { params: { receiptId } });
      if (receiptViewerExpenseId) await loadReceipts(receiptViewerExpenseId);
      await loadData();
    } catch {
      alert('Не удалось удалить чек');
    }
  }, [receiptViewerExpenseId, loadReceipts]);

  const loadData = useCallback(async () => {
    if (!user) return;
    setLoading(true);
    const [myIncRes, allIncRes, myExpRes, allExpRes, projRes, usersRes] = await Promise.allSettled([
      api.get<IncomeDto[]>(`/incomes?userId=${user.id}`),
      canViewAll ? api.get<IncomeDto[]>('/incomes/all') : Promise.resolve({ data: [] }),
      api.get<ExpenseDto[]>(`/expenses?userId=${user.id}`),
      canViewAll ? api.get<ExpenseDto[]>('/expenses/all') : Promise.resolve({ data: [] }),
      api.get<ProjectDto[]>('/projects'),
      canViewAll ? api.get<UserDto[]>('/users') : Promise.resolve({ data: [] }),
    ]);
    setMyIncomes(myIncRes.status === 'fulfilled' ? myIncRes.value.data : []);
    setAllIncomes(allIncRes.status === 'fulfilled' ? allIncRes.value.data : []);
    setMyExpenses(myExpRes.status === 'fulfilled' ? myExpRes.value.data : []);
    setAllExpenses(allExpRes.status === 'fulfilled' ? allExpRes.value.data : []);
    setProjects(projRes.status === 'fulfilled' ? projRes.value.data.filter(p => p.isActive) : []);
    setUsers(usersRes.status === 'fulfilled' ? usersRes.value.data : []);
    setLoading(false);
  }, [user, canViewAll]);

  useEffect(() => { loadData(); }, [loadData]);

  const incomes = effectiveScope === 'all' ? allIncomes : myIncomes;
  const expenses = effectiveScope === 'all' ? allExpenses : myExpenses;
  const myCount = myIncomes.length + myExpenses.length;
  const allCount = allIncomes.length + allExpenses.length;

  // Объединяем доходы и расходы в одну таблицу
  const combinedEntries: CombinedEntry[] = [
    ...incomes.map(i => ({
      id: i.id,
      type: 'INCOME' as const,
      userId: i.userId,
      projectId: i.projectId,
      projectName: i.projectName,
      date: i.date,
      name: findIncomeType(i.name)?.label || i.name,
      category: i.category,  // 🆕 Используем надкатегорию из DTO
      subcategory: i.type,   // 🆕 Тип дохода как подкатегория для фильтрации
      amount: i.amount,
      currency: i.currency,
      comment: i.comment || '',
      entryCategory: i.category as 'WORK' | 'PERSONAL',  // 🆕 Надкатегория
    })),
    ...expenses.map(e => ({
      id: e.id,
      type: 'EXPENSE' as const,
      userId: e.userId,
      projectId: e.projectId,
      projectName: e.projectName,
      date: e.date,
      name: e.name || findExpenseType(e.type)?.label || e.type,
      category: e.category,  // 🆕 Надкатегория из DTO
      subcategory: e.type,   // 🆕 Тип расхода как подкатегория для фильтрации
      amount: e.amount,
      currency: e.currency,
      comment: e.comment || '',
      hasReceipt: e.receiptSubmitted || e.hasReceiptPhoto,
      entryCategory: e.category as 'WORK' | 'PERSONAL',  // 🆕 Надкатегория
    })),
  ];

  const filtered = combinedEntries
    .filter(entry => effectiveScope !== 'all' || filterUser === 'all' || entry.userId === filterUser)
    .filter(entry => {
      if (reportPreset === '1') return true;
      if (reportPreset === '2') return entry.type === 'EXPENSE' && (isHouseholdExpense(entry) || isPerDiem(entry));
      if (reportPreset === '3') return entry.type === 'INCOME' || (
        entry.type === 'EXPENSE' && !isPerDiem(entry) && (!entry.hasReceipt || isExtraPerDiem(entry))
      );
      return filterEntryType === 'all' || entry.type === filterEntryType;
    })
    .filter(entry => reportPreset ? true : (filterCategory === 'all' || entry.entryCategory === filterCategory))
    .filter(entry => reportPreset ? true : (filterSubcategory === 'all' || entry.subcategory === filterSubcategory))
    .filter(entry => reportPreset ? true : (!hidePerDiem || !isPerDiem(entry)))
    .filter(entry => {
      if (reportPreset) return true;
      if (filterReceipt === 'all') return true;
      if (entry.type !== 'EXPENSE') return false;
      if (filterReceipt === 'with') return entry.hasReceipt;
      if (filterReceipt === 'without') return !entry.hasReceipt;
      return true;
    })
    .filter(entry => entry.date >= dateFrom && entry.date <= dateTo)
    .sort((a, b) => {
      const dir = sortDir === 'asc' ? 1 : -1;
      if (sortField === 'date') return a.date.localeCompare(b.date) * dir;
      if (sortField === 'amount') return (a.amount - b.amount) * dir;
      if (sortField === 'type') return a.type.localeCompare(b.type) * dir;
      return 0;
    });


  // Расчет сальдо: доходы минус расходы (в валютах)
  const saldoByCurrency = filtered.reduce((acc, entry) => {
    if (!acc[entry.currency]) acc[entry.currency] = 0;
    if (entry.type === 'INCOME') {
      acc[entry.currency] += entry.amount;
    } else {
      acc[entry.currency] -= entry.amount;
    }
    return acc;
  }, {} as Record<string, number>);

  const handleCreate = async () => {
    if (!user || !form.amount) return;
    setSaving(true);
    try {
      if (formType === 'INCOME') {
        await api.post('/incomes', {
          id: generateUUID(),
          userId: user.id,
          projectId: form.projectId || null,
          projectName: projects.find(p => p.id === form.projectId)?.name || '',
          date: form.date,
          name: form.type,
          amount: parseFloat(form.amount),
          currency: form.currency,
          comment: form.comment,
        });
      } else {
        const expenseResponse = await api.post<ExpenseDto>('/expenses', {
          id: generateUUID(),
          userId: user.id,
          projectId: form.projectId || null,
          projectName: projects.find(p => p.id === form.projectId)?.name || '',
          date: form.date,
          type: form.type,
          name: form.type === 'ROAD' ? 'Дорога' : form.name,
          amount: parseFloat(form.amount),
          currency: form.currency,
          comment: form.comment,
        });
        if (pendingReceiptFiles.length) {
          await uploadFilesToExpense(expenseResponse.data.id, pendingReceiptFiles);
        }
      }
      setShowForm(false);
      setPendingReceiptFiles([]);
      setForm({ ...form, projectId: '', amount: '', comment: '', name: '' });
      await loadData();
    } catch {
      alert(`Ошибка создания ${formType === 'INCOME' ? 'дохода' : 'расхода'} или загрузки чеков`);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: string, type: 'INCOME' | 'EXPENSE') => {
    if (type === 'INCOME') {
      if (!confirm('Удалить доход?')) return;
      await api.delete('/incomes', { params: { incomeId: id } });
    } else {
      if (!confirm('Удалить расход?')) return;
      await api.delete('/expenses', { params: { expenseId: id } });
    }
    await loadData();
  };

  const applyReportPreset = (preset: ReportPreset) => {
    setReportPreset(preset);
    if (canViewAll) setScope('all');
    setFilterUser('all');
    setFilterEntryType('all');
    setFilterCategory('all');
    setFilterSubcategory('all');
    setFilterReceipt('all');
    setHidePerDiem(false);
  };

  const handleExportXLSX = () => {
    // Создаем данные для экспорта с учетом фильтров
    const exportData = filtered.map(entry => ({
      'Дата': entry.date,
      'Тип': entry.type === 'INCOME' ? 'Доход' : 'Расход',
      'Проект': entry.projectName || '',
      'Сотрудник': effectiveScope === 'all' ? (users.find(u => u.id === entry.userId)?.name || '') : '',
      'Название': entry.name,
      'Категория': entry.category,
      'Сумма': entry.amount,
      'Валюта': entry.currency,
      'Комментарий': entry.comment || '',
      'Чек': entry.type === 'EXPENSE' ? (entry.hasReceipt ? 'Да' : 'Нет') : '-',
    }));

    // Добавляем итоговую строку с сальдо по валютам
    Object.entries(saldoByCurrency).forEach(([currency, amount]) => {
      exportData.push({
        'Дата': '',
        'Тип': `Сальдо ${currency}`,
        'Проект': '',
        'Сотрудник': '',
        'Название': '',
        'Категория': '',
        'Сумма': amount,
        'Валюта': currency,
        'Комментарий': '',
        'Чек': '-',
      });
    });

    // Создаем workbook и worksheet
    const wb = XLSX.utils.book_new();
    const ws = XLSX.utils.json_to_sheet(exportData);

    // Настраиваем ширину колонок
    const colWidths = [
      { wch: 12 }, // Дата
      { wch: 10 }, // Тип
      { wch: 25 }, // Проект
      { wch: 20 }, // Сотрудник
      { wch: 25 }, // Название
      { wch: 15 }, // Категория
      { wch: 15 }, // Сумма
      { wch: 8 },  // Валюта
      { wch: 30 }, // Комментарий
      { wch: 6 },  // Чек
    ];
    ws['!cols'] = colWidths;

    // Добавляем стили заголовка
    const range = XLSX.utils.decode_range(ws['!ref'] || 'A1');
    for (let C = range.s.c; C <= range.e.c; ++C) {
      const address = XLSX.utils.encode_col(C) + '1';
      if (!ws[address]) continue;
      ws[address].s = {
        font: { bold: true, color: { rgb: 'FFFFFF' } },
        fill: { fgColor: { rgb: '4F46E5' } },
        alignment: { horizontal: 'center', vertical: 'center' },
        border: {
          top: { style: 'thin', color: { rgb: '000000' } },
          bottom: { style: 'thin', color: { rgb: '000000' } },
          left: { style: 'thin', color: { rgb: '000000' } },
          right: { style: 'thin', color: { rgb: '000000' } },
        },
      };
    }

    // Добавляем worksheet в workbook
    XLSX.utils.book_append_sheet(wb, ws, 'Доходы и Расходы');

    // Генерируем имя файла с датой
    const now = new Date();
    const dateStr = now.toISOString().split('T')[0];
    const fileName = `Доходы_Расходы_${dateStr}.xlsx`;

    // Сохраняем файл
    const wbout = XLSX.write(wb, { bookType: 'xlsx', type: 'array' });
    saveAs(new Blob([wbout], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), fileName);
  };

  const toggleSort = (field: 'date' | 'amount' | 'type') => {
    if (sortField === field) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortField(field); setSortDir('desc'); }
  };

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      {/* Заголовок и Сальдо */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">📊 Доходы и Расходы</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            {filtered.length} записей за период с {formatDate(dateFrom)} по {formatDate(dateTo)}
          </p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={handleExportXLSX}
            className="px-4 py-2.5 bg-blue-600 hover:bg-blue-700 text-white rounded-xl font-semibold transition-all shadow-md shadow-blue-200 dark:shadow-blue-900/30 flex items-center gap-2"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
            </svg>
            Экспорт в XLSX
          </button>
          <button onClick={() => { setFormType('INCOME'); setShowForm(!showForm); }} className={`bg-green-600 hover:bg-green-700 text-white px-5 py-2.5 rounded-xl font-semibold transition-all shadow-md shadow-green-200 dark:shadow-green-900/30 ${!showForm || formType === 'INCOME' ? 'shadow-indigo-200' : ''}`}>
            {showForm && formType === 'INCOME' ? '✕ Закрыть' : '＋ Новый доход'}
          </button>
          <button onClick={() => { setFormType('EXPENSE'); setShowForm(true); }} className="bg-red-600 hover:bg-red-700 text-white px-5 py-2.5 rounded-xl font-semibold transition-all shadow-md shadow-red-200 dark:shadow-red-900/30">
            {showForm && formType === 'EXPENSE' ? '✕ Закрыть' : '－ Новый расход'}
          </button>
        </div>
      </div>

      {/* Сальдо по валютам */}
      <div className="card p-4 bg-gradient-to-r from-indigo-50 to-purple-50 dark:from-indigo-950/30 dark:to-purple-950/30 border-indigo-200 dark:border-indigo-800">
        <div className="flex items-center gap-2 mb-2">
          <span className="text-lg font-bold text-indigo-700 dark:text-indigo-300">💰 Сальдо</span>
          <span className="text-xs text-indigo-500 dark:text-indigo-400">(Доходы − Расходы)</span>
        </div>
        <div className="flex flex-wrap gap-4">
          {Object.entries(saldoByCurrency).map(([currency, amount]) => (
            <div key={currency} className="flex items-baseline gap-2">
              <span className={`text-2xl font-bold ${amount >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-600 dark:text-red-400'}`}>
                {amount >= 0 ? '+' : ''}{formatMoney(amount, currency)}
              </span>
            </div>
          ))}
          {Object.keys(saldoByCurrency).length === 0 && (
            <span className="text-slate-400 dark:text-slate-500">Нет данных за выбранный период</span>
          )}
        </div>
      </div>

      <ScopeTabs scope={effectiveScope} onChange={setScope} canViewAll={canViewAll} myCount={myCount} allCount={allCount} />

      {/* 🌲 PROLES MODAL: Новый доход / Новый расход */}
      {showForm && createPortal(
        <div className="proles-modal-backdrop" onClick={() => !saving && setShowForm(false)}>
          <div className="proles-modal" onClick={(e) => e.stopPropagation()}>
            <div className="proles-modal-header">
              <div className="proles-modal-title">
                <div className="proles-modal-icon">{formType === 'INCOME' ? '💵' : '💸'}</div>
                <div>
                  <div>{formType === 'INCOME' ? 'Новый доход' : 'Новый расход'}</div>
                  <div style={{ fontSize: '0.75rem', fontWeight: 500, opacity: 0.85, marginTop: 2 }}>
                    {formType === 'INCOME' ? 'Поступление по проекту' : 'Списание средств'}
                  </div>
                </div>
              </div>
              <button onClick={() => setShowForm(false)} className="proles-modal-close">✕</button>
            </div>
            <div className="proles-modal-body">
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">Тип и проект</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group">
                    <label>{formType === 'INCOME' ? 'Тип дохода' : 'Тип расхода'}</label>
                    <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value })} className="input bg-white dark:bg-slate-900">
                      {(formType === 'INCOME' ? INCOME_TYPES : EXPENSE_TYPES).map(t => <option key={t.key} value={t.key}>{t.icon} {t.label}</option>)}
                    </select>
                  </div>
                  <div className="proles-input-group">
                    <label>Дата</label>
                    <input type="date" value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} className="input" />
                  </div>
                  <div className="proles-input-group" style={{ gridColumn: 'span 2' }}>
                    <label>Проект (опционально)</label>
                    <select value={form.projectId} onChange={(e) => setForm({ ...form, projectId: e.target.value })} className="input bg-white dark:bg-slate-900">
                      <option value="">Без проекта</option>
                      {projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
                    </select>
                  </div>
                  {formType === 'EXPENSE' && form.type === 'OTHER' && (
                    <div className="proles-input-group" style={{ gridColumn: 'span 2' }}>
                      <label>Название *</label>
                      <input type="text" placeholder="Введите название расхода" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className="input" />
                    </div>
                  )}
                </div>
              </div>
              <div className="proles-modal-section">
                <div className="proles-modal-section-title">{formType === 'INCOME' ? 'Сумма поступления' : 'Сумма расхода'}</div>
                <div className="proles-modal-grid">
                  <div className="proles-input-group">
                    <label>Сумма *</label>
                    <input type="number" step="0.01" placeholder="0.00" value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} className="input" />
                  </div>
                  <div className="proles-input-group">
                    <label>Валюта</label>
                    <select value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })} className="input bg-white dark:bg-slate-900">
                      <option>RUB</option><option>USD</option><option>EUR</option><option>BYN</option>
                    </select>
                  </div>
                </div>
              </div>
            </div>
            {formType === 'EXPENSE' && (
              <div className="proles-modal-section w-full">
                <div className="proles-modal-section-title proles-receipts-section-title">Чеки и подтверждающие документы</div>
                <div className="proles-receipts-content w-full flex flex-wrap items-center gap-2">
                  <label className="px-3 py-2 rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 cursor-pointer text-sm font-medium hover:border-indigo-400">
                    📎 Добавить файлы / фото
                    <input
                      type="file"
                      accept="image/*,application/pdf"
                      multiple
                      className="hidden"
                      onChange={async (e) => {
                        const files = Array.from(e.target.files || []);
                        if (!files.length) return;
                        setPendingReceiptFiles(prev => [...prev, ...files]);
                        e.target.value = '';
                      }}
                    />
                  </label>
                  {pendingReceiptFiles.length > 0 && (
                    <span className="text-sm text-slate-600 dark:text-slate-400">Выбрано файлов: {pendingReceiptFiles.length}</span>
                  )}
                </div>
                {pendingReceiptFiles.length > 0 && (
                  <div className="mt-3 w-full grid grid-cols-1 sm:grid-cols-2 gap-2">
                    {pendingReceiptFiles.map((file, index) => (
                      <div key={`${file.name}-${index}`} className="flex items-center gap-3 rounded-lg border border-slate-200 dark:border-slate-700 px-3 py-2 min-w-0">
                        <PendingReceiptPreview file={file} />
                        <div className="min-w-0 flex-1">
                          <div className="truncate text-sm font-medium">{file.name}</div>
                          <div className="text-xs text-slate-500 mt-1">{(file.size / 1024 / 1024).toFixed(2)} МБ</div>
                        </div>
                        <button type="button" className="text-red-500 shrink-0" onClick={() => setPendingReceiptFiles(prev => prev.filter((_, i) => i !== index))}>✕</button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
            <div className="proles-modal-footer">
              <button onClick={() => setShowForm(false)} className="proles-btn-cancel">Отмена</button>
              <button onClick={handleCreate} disabled={saving || !form.amount || (formType === 'EXPENSE' && form.type === 'OTHER' && !form.name)} className={`proles-btn-save ${formType === 'EXPENSE' ? 'bg-red-600 hover:bg-red-700' : ''}`}>
                {saving ? '⏳ Сохранение...' : '💾 Сохранить'}
              </button>
            </div>
          </div>
        </div>,
        document.body
      )}

      {/* Фильтры */}
      <div className="card p-4 animate-fade-in">
        <div className="mb-4">
          <div className="text-sm font-semibold text-slate-700 dark:text-slate-300 mb-2">Шаблоны отчетов</div>
          <div className="flex flex-wrap gap-2">
            {([
              ['1', '📊 Отчет 1', 'Все доходы и расходы'],
              ['2', '🏠 Отчет 2', 'Хоз.нужды + суточные'],
              ['3', '🧾 Отчет 3', 'Доходы + расходы без чека + сверхсуточные'],
            ] as const).map(([preset, label, hint]) => (
              <button
                key={preset}
                type="button"
                onClick={() => applyReportPreset(preset)}
                title={hint}
                className={`px-3 py-2 rounded-lg text-xs font-semibold transition-all border ${
                  reportPreset === preset
                    ? 'bg-indigo-600 text-white border-indigo-600 shadow-md shadow-indigo-200 dark:shadow-indigo-900/30'
                    : 'bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300 border-slate-200 dark:border-slate-700 hover:border-indigo-300 hover:bg-indigo-50 dark:hover:bg-indigo-950/30'
                }`}
              >
                {label}
              </button>
            ))}
            {reportPreset && (
              <button
                type="button"
                onClick={() => setReportPreset(null)}
                className="px-3 py-2 rounded-lg text-xs font-semibold bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-700 transition-all"
              >
                Сбросить отчет
              </button>
            )}
          </div>
        </div>

        {/* Предустановки дат */}
        <div className="flex flex-wrap gap-2 mb-3">
          <button
            onClick={() => {
              const now = new Date();
              const year = now.getFullYear();
              const month = String(now.getMonth() + 1).padStart(2, '0');
              setDateFrom(`${year}-${month}-01`);
              setDateTo(`${year}-${month}-31`);
              setDatePreset('current');
            }}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${datePreset === 'current' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}
          >
            Текущий месяц
          </button>
          <button
            onClick={() => {
              const now = new Date();
              const lastMonth = new Date(now.getFullYear(), now.getMonth() - 1, 1);
              const year = lastMonth.getFullYear();
              const month = String(lastMonth.getMonth() + 1).padStart(2, '0');
              const lastDay = new Date(year, lastMonth.getMonth() + 1, 0).getDate();
              setDateFrom(`${year}-${month}-01`);
              setDateTo(`${year}-${month}-${lastDay}`);
              setDatePreset('last');
            }}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${datePreset === 'last' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}
          >
            Прошлый месяц
          </button>
          <button
            onClick={() => setDatePreset('custom')}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${datePreset === 'custom' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}
          >
            Произвольный
          </button>
        </div>
        
        {/* Поля ввода дат */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-3">
          <div>
            <label className="text-xs text-slate-500 dark:text-slate-400 block mb-1">С даты</label>
            <input type="date" value={dateFrom} onChange={(e) => { setDateFrom(e.target.value); setDatePreset('custom'); }} className="input text-sm" />
          </div>
          <div>
            <label className="text-xs text-slate-500 dark:text-slate-400 block mb-1">По дату</label>
            <input type="date" value={dateTo} onChange={(e) => { setDateTo(e.target.value); setDatePreset('custom'); }} className="input text-sm" />
          </div>
        </div>

        {/* Остальные фильтры — только в режиме «Все» */}
        {effectiveScope === 'all' && (
          <div className="grid grid-cols-1 md:grid-cols-5 gap-3">
            <select value={filterUser} onChange={(e) => setFilterUser(e.target.value)} className="input bg-white dark:bg-slate-900">
              <option value="all">Все сотрудники</option>
              {users.map(u => <option key={u.id} value={u.id}>{u.name}</option>)}
            </select>
            <select value={filterEntryType} onChange={(e) => setFilterEntryType(e.target.value as 'all' | 'INCOME' | 'EXPENSE')} className="input bg-white dark:bg-slate-900">
              <option value="all">Все типы записей</option>
              <option value="INCOME">📈 Доходы</option>
              <option value="EXPENSE">📉 Расходы</option>
            </select>
            <select value={filterCategory} onChange={(e) => setFilterCategory(e.target.value as 'all' | 'WORK' | 'PERSONAL')} className="input bg-white dark:bg-slate-900">
              <option value="all">Все надкатегории</option>
              <option value="WORK">💼 Рабочие</option>
              <option value="PERSONAL">🏠 Иные</option>
            </select>
            <select value={filterSubcategory} onChange={(e) => setFilterSubcategory(e.target.value)} className="input bg-white dark:bg-slate-900">
              <option value="all">Все типы</option>
              {[...INCOME_TYPES, ...EXPENSE_TYPES].map(t => <option key={t.key} value={t.key}>{t.icon} {t.label}</option>)}
            </select>
            <select value={filterReceipt} onChange={(e) => setFilterReceipt(e.target.value as 'all' | 'with' | 'without')} className="input bg-white dark:bg-slate-900">
              <option value="all">Все чеки</option>
              <option value="with">✓ С чеком</option>
              <option value="without">✕ Без чека</option>
            </select>
          </div>
        )}
        
        {/* Галочка "Скрыть суточные" */}
        <div className="flex items-center gap-2 mt-3 pt-3 border-t border-slate-200 dark:border-slate-700">
          <input 
            type="checkbox" 
            id="hidePerDiem"
            checked={hidePerDiem}
            onChange={(e) => setHidePerDiem(e.target.checked)}
            className="w-4 h-4 text-indigo-600 border-slate-300 rounded focus:ring-indigo-500"
          />
          <label htmlFor="hidePerDiem" className="text-sm font-medium text-slate-700 dark:text-slate-300 cursor-pointer select-none">
            🚫 Скрыть суточные
          </label>
        </div>
      </div>

      {/* Сортировка */}
      <div className="flex gap-2 flex-wrap">
        <button onClick={() => toggleSort('date')} className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${sortField === 'date' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}>
          📅 Дата {sortField === 'date' ? (sortDir === 'desc' ? '↓' : '↑') : ''}
        </button>
        <button onClick={() => toggleSort('amount')} className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${sortField === 'amount' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}>
          💰 Сумма {sortField === 'amount' ? (sortDir === 'desc' ? '↓' : '↑') : ''}
        </button>
        <button onClick={() => toggleSort('type')} className={`px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${sortField === 'type' ? 'bg-slate-900 text-white dark:bg-slate-100 dark:text-slate-900' : 'bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-700'}`}>
          🏷 Тип {sortField === 'type' ? (sortDir === 'desc' ? '↓' : '↑') : ''}
        </button>
      </div>

      {/* Таблица */}
      {loading ? (
        <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>
      ) : filtered.length === 0 ? (
        <div className="card p-12 text-center border-dashed border-2 border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-900/50">
          <div className="text-5xl mb-4 opacity-50">📊</div>
          <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Нет записей</h3>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-2">Измените параметры фильтра или добавьте новую запись</p>
        </div>
      ) : (
        <div className="card overflow-hidden animate-fade-in">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-slate-50 dark:bg-slate-800 border-b border-slate-200 dark:border-slate-700">
                <tr>
                  <th className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">Дата</th>
                  <th className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">Тип</th>
                  <th className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">Проект</th>
                  <th className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">Сотрудник</th>
                  <th className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">Название</th>
                  <th className="px-4 py-3 text-right font-semibold text-slate-700 dark:text-slate-300">Сумма</th>
                  <th className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">Валюта</th>
                  <th className="px-4 py-3 text-left font-semibold text-slate-700 dark:text-slate-300">Комментарий</th>
                  <th className="px-4 py-3 text-center font-semibold text-slate-700 dark:text-slate-300">Чек</th>
                  <th className="px-4 py-3 text-right font-semibold text-slate-700 dark:text-slate-300"></th>
                </tr>
              </thead>
              <tbody>
                {filtered.map(entry => {
                  const userName = effectiveScope === 'all' ? users.find(u => u.id === entry.userId)?.name : null;
                  // Цвет фона: доходы - белый, расходы с чеком - зеленоватый, без чека - красноватый
                  let rowBgClass = 'bg-white dark:bg-slate-900';
                  if (entry.type === 'EXPENSE') {
                    rowBgClass = entry.hasReceipt 
                      ? 'bg-emerald-50 dark:bg-emerald-950/20' 
                      : 'bg-red-50 dark:bg-red-950/20';
                  }
                  return (
                    <tr key={entry.id} className={`border-b border-slate-100 dark:border-slate-800 hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors ${rowBgClass}`}>
                      <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{formatDate(entry.date)}</td>
                      <td className="px-4 py-3">
                        <span className={`px-2 py-1 rounded-full text-xs font-bold ${entry.type === 'INCOME' ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-400' : 'bg-red-100 text-red-700 dark:bg-red-950/40 dark:text-red-400'}`}>
                          {entry.type === 'INCOME' ? '📈 Доход' : '📉 Расход'}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-slate-900 dark:text-slate-100 font-medium">{entry.projectName || '—'}</td>
                      <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{userName || '—'}</td>
                      <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{entry.name}</td>
                      <td className={`px-4 py-3 text-right font-bold ${entry.type === 'INCOME' ? 'text-emerald-600 dark:text-emerald-400' : 'text-red-600 dark:text-red-400'}`}>
                        {entry.type === 'INCOME' ? '+' : '-'}{entry.amount.toFixed(2)}
                      </td>
                      <td className="px-4 py-3 text-slate-600 dark:text-slate-400">{entry.currency}</td>
                      <td className="px-4 py-3 text-slate-600 dark:text-slate-400 max-w-xs truncate" title={entry.comment}>
                        {entry.comment || '—'}
                      </td>
                      <td className="px-4 py-3 text-center">
                        {entry.type === 'EXPENSE' ? (
                          entry.hasReceipt ? (
                            <button
                              type="button"
                              onClick={() => void loadReceipts(entry.id)}
                              className="text-emerald-500 text-lg hover:scale-110 transition-transform"
                              title="Открыть чеки"
                            ><ReceiptIcon className="w-5 h-5" /></button>
                          ) : (
                            effectiveScope === 'my' && entry.userId === user?.id ? (
                              <button
                                type="button"
                                onClick={() => void loadReceipts(entry.id)}
                                className="text-red-500 text-lg hover:scale-110 transition-transform"
                                title="Добавить чек"
                              ><ReceiptIcon className="w-5 h-5" /></button>
                            ) : (
                              <span className="text-red-500" title="Чека нет"><ReceiptIcon className="w-5 h-5" /></span>
                            )
                          )
                        ) : (
                          <span className="text-slate-300 dark:text-slate-600">—</span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <button 
                          onClick={() => handleDelete(entry.id, entry.type)}
                          className="text-xs font-medium text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30 px-2 py-1 rounded-lg transition-all"
                          title="Удалить"
                        >
                          🗑
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {receiptViewerExpenseId && createPortal(
        <div className="fixed inset-0 z-[70] bg-black/60 flex items-center justify-center p-4" onClick={() => setReceiptViewerExpenseId(null)}>
          <div className="bg-white dark:bg-slate-900 rounded-2xl w-full max-w-4xl max-h-[90vh] overflow-auto shadow-2xl" onClick={e => e.stopPropagation()}>
            <div className="flex items-center justify-between p-5 border-b border-slate-200 dark:border-slate-800">
              <div>
                <h2 className="text-lg font-bold">Чеки расхода</h2>
                <p className="text-sm text-slate-500">{receiptViewerItems.length} файл(ов)</p>
              </div>
              <button onClick={() => setReceiptViewerExpenseId(null)} className="text-slate-500 text-xl">✕</button>
            </div>
            <div className="p-5">
              {receiptLoading ? (
                <div className="py-12 text-center">⏳ Загрузка...</div>
              ) : (
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {receiptViewerItems.map(receipt => (
                    <div key={receipt.id} className="rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
                      <div className="bg-slate-100 dark:bg-slate-800 aspect-[4/3] flex items-center justify-center">
                        {/\.(pdf)$/i.test(receipt.url) ? (
                          <iframe title={receipt.fileName} src={receipt.url} className="w-full h-full" />
                        ) : (
                          <img src={receipt.url} alt={receipt.fileName} className="max-h-full max-w-full object-contain" />
                        )}
                      </div>
                      <div className="p-3 flex items-center justify-between gap-2">
                        <span className="truncate text-sm">{receipt.fileName}</span>
                        <div className="flex items-center gap-2 shrink-0">
                          <label className="text-indigo-600 hover:text-indigo-800 text-sm cursor-pointer" title="Заменить файл">
                            Заменить
                            <input
                              type="file"
                              accept="image/*,application/pdf"
                              className="hidden"
                              onChange={async e => {
                                const file = e.target.files?.[0];
                                if (!file) return;
                                try {
                                  await uploadFilesToExpense(receipt.expenseId, [file]);
                                  await api.delete('/expenses/receipt', { params: { receiptId: receipt.id } });
                                  await loadReceipts(receipt.expenseId);
                                  await loadData();
                                } catch {
                                  alert('Не удалось заменить чек');
                                } finally {
                                  e.target.value = '';
                                }
                              }}
                            />
                          </label>
                          <button onClick={() => void removeReceipt(receipt.id)} className="text-red-500" title="Удалить">🗑</button>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}

              <div className="mt-5 flex flex-wrap gap-2">
                <label className="px-3 py-2 rounded-lg border border-slate-300 dark:border-slate-700 cursor-pointer text-sm font-medium">
                  📎 Добавить файлы
                  <input
                    type="file"
                    accept="image/*,application/pdf"
                    multiple
                    className="hidden"
                    onChange={async e => {
                      const files = Array.from(e.target.files || []);
                      if (!files.length) return;
                      try {
                        await uploadFilesToExpense(receiptViewerExpenseId, files);
                        await loadReceipts(receiptViewerExpenseId);
                        await loadData();
                      } catch {
                        alert('Не удалось загрузить один или несколько файлов');
                      } finally {
                        e.target.value = '';
                      }
                    }}
                  />
                </label>
              </div>
            </div>
          </div>
        </div>,
        document.body
      )}

    </div>
  );
}
