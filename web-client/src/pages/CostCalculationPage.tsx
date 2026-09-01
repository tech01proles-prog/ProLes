import { useState, useEffect, useMemo } from 'react';
import api from '../api/client';
import type { ProjectDto, ExpenseDto, TimeEntryDto, UserDto, SalaryComponentDto } from '../types';
import { formatMoney } from '../lib/utils';
import { usePermissions } from '../hooks/usePermissions';
import * as XLSX from 'xlsx';
import { saveAs } from 'file-saver';

interface CostRow {
  project: ProjectDto;
  salePriceManual: number;
  materialsManual: number;
  techHours: number;
  techSalary: number;
  expenses: {
    employeeExpenses: number;
    household: number;
    advances: number;
    tickets: number;
    perDiem: number;
    other: number;
    total: number;
  };
  transport: {
    techTransport: number;
    other: number;
    total: number;
  };
  transportToClientManual: number;
  contractorsManual: number;
  creditPercentManual: number;
  totalCost: number;
  marginalIncome: number;
}

export function CostCalculationPage() {
  const { can, loading: permLoading } = usePermissions();
  const canView = !permLoading && can('cost_calculation', 'view');
  const canEdit = !permLoading && can('cost_calculation', 'edit');
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [allExpenses, setAllExpenses] = useState<ExpenseDto[]>([]);
  const [allTimeEntries, setAllTimeEntries] = useState<TimeEntryDto[]>([]);
  const [allUsers, setAllUsers] = useState<UserDto[]>([]);
  const [allSalaryComponents, setAllSalaryComponents] = useState<SalaryComponentDto[]>([]);
  const [loading, setLoading] = useState(true);
  // Глобальное состояние раскрытия колонок для всей таблицы
  const [showExpensesDetail, setShowExpensesDetail] = useState(false);
  const [showTransportDetail, setShowTransportDetail] = useState(false);
  // Состояние для фильтрации по периоду
  const [periodType, setPeriodType] = useState<'month' | 'quarter' | 'year'>('month');
  const [selectedPeriod, setSelectedPeriod] = useState<string>('');
  const [manualData, setManualData] = useState<Record<string, {
    salePrice: number;
    materials: number;
    transportToClient: number;
    contractors: number;
    creditPercent: number;
  }>>({});
  const [editingCell, setEditingCell] = useState<{ projectId: string; field: string } | null>(null);
  const [editValue, setEditValue] = useState('');

  // Инициализация выбранного периода - текущий месяц
  useEffect(() => {
    if (!selectedPeriod) {
      const now = new Date();
      if (periodType === 'month') {
        setSelectedPeriod(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`);
      } else if (periodType === 'quarter') {
        const quarter = Math.floor(now.getMonth() / 3) + 1;
        setSelectedPeriod(`${now.getFullYear()}-Q${quarter}`);
      } else {
        setSelectedPeriod(`${now.getFullYear()}`);
      }
    }
  }, [periodType]);

  // Фильтрация данных по выбранному периоду
  const { expenses, timeEntries } = useMemo(() => {
    if (!selectedPeriod) {
      return { expenses: allExpenses, timeEntries: allTimeEntries };
    }

    let startDate: Date;
    let endDate: Date;

    if (periodType === 'month') {
      const [year, month] = selectedPeriod.split('-').map(Number);
      startDate = new Date(year, month - 1, 1);
      endDate = new Date(year, month, 0, 23, 59, 59, 999);
    } else if (periodType === 'quarter') {
      const [year, quarterStr] = selectedPeriod.split('-Q');
      const quarter = parseInt(quarterStr, 10);
      const startMonth = (quarter - 1) * 3;
      startDate = new Date(+year, startMonth, 1);
      endDate = new Date(+year, startMonth + 3, 0, 23, 59, 59, 999);
    } else {
      const year = parseInt(selectedPeriod, 10);
      startDate = new Date(year, 0, 1);
      endDate = new Date(year, 11, 31, 23, 59, 59, 999);
    }

    const filteredExpenses = allExpenses.filter(e => {
      const entryDate = new Date(e.date);
      return entryDate >= startDate && entryDate <= endDate;
    });

    const filteredTimeEntries = allTimeEntries.filter(t => {
      const entryDate = new Date(t.date);
      return entryDate >= startDate && entryDate <= endDate;
    });

    return { expenses: filteredExpenses, timeEntries: filteredTimeEntries };
  }, [allExpenses, allTimeEntries, periodType, selectedPeriod]);

  useEffect(() => {
    if (!canView) return;
    (async () => {
      setLoading(true);
      const [projRes, expRes, timeRes, usersRes, salaryRes] = await Promise.allSettled([
        api.get<ProjectDto[]>('/projects'),
        api.get<ExpenseDto[]>('/expenses/all'),
        api.get<TimeEntryDto[]>('/entries/all'),
        api.get<UserDto[]>('/users'),
        api.get<SalaryComponentDto[]>('/payroll/components/all'),
      ]);
      setProjects(projRes.status === 'fulfilled' ? (projRes.value.data || []).filter((p: ProjectDto) => p.isActive) : []);
      setAllExpenses(expRes.status === 'fulfilled' ? (expRes.value.data || []) : []);
      setAllTimeEntries(timeRes.status === 'fulfilled' ? (timeRes.value.data || []) : []);
      setAllUsers(usersRes.status === 'fulfilled' ? (usersRes.value.data || []) : []);
      setAllSalaryComponents(salaryRes.status === 'fulfilled' ? (salaryRes.value.data || []) : []);
      
      // 🔍 DEBUG: проверяем загрузку компонентов зарплаты
      if (salaryRes.status === 'fulfilled') {
        console.log('📊 Salary components loaded:', salaryRes.status, (salaryRes.value.data || []).length, 'items');
        console.log('💾 Raw salary components data:', salaryRes.value.data);
        const salaryCompData = salaryRes.value.data || [];
        console.log('🧾 All salary components:', salaryCompData.map((c: SalaryComponentDto) => ({id: c.id, userId: c.userId, type: c.type, amount: c.amount, isActive: c.isActive})));
        
        // 🔍 DEBUG: проверяем совпадение userId компонента с employee users
        if (salaryCompData.length > 0 && usersRes.status === 'fulfilled') {
          const firstComp = salaryCompData[0];
          const empUsers = (usersRes.value.data || []).filter((u: UserDto) => u.role === 'employee');
          console.log('👷 Employee users:', empUsers.length, empUsers.map((u: UserDto) => ({id: u.id, name: u.name})));
          const employeeIds = empUsers.map((u: UserDto) => u.id);
          console.log('🔍 First component userId:', firstComp.userId);
          console.log('🔍 Employee user IDs:', employeeIds);
          console.log('🔍 Is component userId in employee IDs?', employeeIds.includes(firstComp.userId));
          
          // Проверяем все пользователи с этим userId
          const compUser = (usersRes.value.data || []).find((u: UserDto) => u.id === firstComp.userId);
          console.log('🔍 User with component userId:', compUser ? {id: compUser.id, name: compUser.name, role: compUser.role} : 'NOT FOUND');
        }
      }
      if (usersRes.status === 'fulfilled') {
        console.log('👥 Users loaded:', usersRes.status, (usersRes.value.data || []).length, 'items');
      }
      
      // Загружаем сохраненные ручные данные из проектов
      const manual: Record<string, { salePrice: number; materials: number; transportToClient: number; contractors: number; creditPercent: number }> = {};
      projRes.status === 'fulfilled' && projRes.value.data.forEach((p: ProjectDto) => {
        manual[p.id] = {
          salePrice: (p as any).salePrice || 0,
          materials: (p as any).materials || 0,
          transportToClient: p.transportToClient || 0,
          contractors: (p as any).contractors || 0,
          creditPercent: (p as any).creditPercent || 0,
        };
      });
      setManualData(manual);
      setLoading(false);
    })();
  }, [canView]);

  const projectData = useMemo((): CostRow[] => {
    return projects.map(project => {
      const projectExpenses = (expenses || []).filter(e => e.projectId === project.id);
      
      // Расходы сотрудников (все кроме HOUSEHOLD, PER_DIEM, ROAD)
      // Включая OTHER и остальные типы расходов
      const employeeExpenses = projectExpenses
        .filter(e => e.type !== 'HOUSEHOLD' && e.type !== 'PER_DIEM' && e.type !== 'ROAD')
        .reduce((sum, e) => sum + e.amount, 0);
      
      // Хоз.нужды
      const household = projectExpenses
        .filter(e => e.type === 'HOUSEHOLD')
        .reduce((sum, e) => sum + e.amount, 0);
      
      // Авансы (заглушка - 0)
      const advances = 0;
      
      // Билеты (расходы на билеты)
      const tickets = projectExpenses
        .filter(e => e.name.toLowerCase().includes('билет') || e.name.toLowerCase().includes('ticket'))
        .reduce((sum, e) => sum + e.amount, 0);
      
      // Проживание (суточные - PER_DIEM, per_diem, perdiem)
      const perDiem = projectExpenses
        .filter(e => {
          const typeUpper = e.type.toUpperCase();
          return typeUpper === 'PER_DIEM' || typeUpper === 'PERDIEM' || e.type.toLowerCase() === 'per_diem';
        })
        .reduce((sum, e) => sum + e.amount, 0);
      
      // Иные расходы (теперь пусто, т.к. всё включено в Расходы)
      const other = 0;
      
      const expensesTotal = employeeExpenses + household + advances + tickets + perDiem + other;
      
      // Транспорт ТО (ROAD)
      const techTransport = projectExpenses
        .filter(e => e.type === 'ROAD')
        .reduce((sum, e) => sum + e.amount, 0);
      
      // Транспорт другое (заглушка)
      const transportOther = 0;
      const transportTotal = techTransport + transportOther;
      
      // Часы ТО (сумма часов всех сотрудников роли employee по этому проекту)
      const techHours = (timeEntries || [])
        .filter(t => t.projectId === project.id)
        .reduce((sum, t) => sum + t.hours, 0);
      
      // Расчет зарплаты тех.отдела (сотрудники с ролью 'employee')
      // Включая все типы компонентов: FIXED, PIECE, HOURLY, BONUS (как в SalaryCalculator.kt)
      // Фильтруем компоненты зарплаты по периоду
      
      // Находим сотрудников тех.отдела (роль 'employee')
      const techEmployees = (allUsers || []).filter(u => u.role === 'employee');
      const techEmployeeIds = new Set(techEmployees.map(e => e.id));
      
      // Фильтруем компоненты зарплаты: только для тех.сотрудников и активные
      // Как в SalaryCalculator.kt — берем ВСЕ активные компоненты, без фильтрации по period
      // Период учитывается только при подсчете часов/дней для PIECE и HOURLY
      const techSalaryComponents = (allSalaryComponents || []).filter(c => 
        techEmployeeIds.has(c.userId) &&
        c.isActive
      );
      
      // Считаем зарплату по аналогии с SalaryCalculator.kt
      let techSalary = 0;
      
      // 🔍 DEBUG: лог для отладки расчета зарплаты
      console.log('🔢 Calculating techSalary for project:', project.name, 'techSalaryComponents count:', techSalaryComponents.length);
      console.log('  - FIXED components:', techSalaryComponents.filter(c => c.type === 'FIXED').length);
      console.log('  - BONUS components:', techSalaryComponents.filter(c => c.type === 'BONUS').length);
      console.log('  - PIECE components:', techSalaryComponents.filter(c => c.type === 'PIECE').length);
      console.log('  - HOURLY components:', techSalaryComponents.filter(c => c.type === 'HOURLY').length);
      
      // 1. FIXED (оклад) — суммируем все компоненты
      const fixedComponents = techSalaryComponents.filter(c => c.type === 'FIXED');
      for (const component of fixedComponents) {
        techSalary += component.amount || 0;
      }
      
      // 2. BONUS (премия) — суммируем все компоненты
      const bonusComponents = techSalaryComponents.filter(c => c.type === 'BONUS');
      for (const component of bonusComponents) {
        techSalary += component.amount || 0;
      }
      
      // 3. PIECE (сдельная) — ratePerUnit × количество записей (дней) за ПЕРИОД
      const pieceComponents = techSalaryComponents.filter(c => c.type === 'PIECE');
      for (const component of pieceComponents) {
        const projectId = component.projectId;
        const ratePerUnit = component.ratePerUnit || 0;
        
        // Считаем количество записей (дней с часами) за ПЕРИОД для этого сотрудника
        const entryCount = (timeEntries || [])
          .filter(t => 
            t.userId === component.userId && 
            (!projectId || t.projectId === projectId) &&
            t.hours > 0
          )
          .length;
        
        techSalary += entryCount * ratePerUnit;
      }
      
      // 4. HOURLY (почасовая) — ratePerHour × сумма часов за ПЕРИОД
      const hourlyComponents = techSalaryComponents.filter(c => c.type === 'HOURLY');
      for (const component of hourlyComponents) {
        const projectId = component.projectId;
        const ratePerHour = component.ratePerHour || 0;
        
        const totalHours = (timeEntries || [])
          .filter(t => 
            t.userId === component.userId && 
            (!projectId || t.projectId === projectId)
          )
          .reduce((sum, t) => sum + t.hours, 0);
        
        techSalary += totalHours * ratePerHour;
      }
      
      console.log('✅ Final techSalary for', project.name, ':', techSalary);
      
      // Ручные данные
      const manual = manualData[project.id] || { salePrice: 0, materials: 0, transportToClient: 0, contractors: 0, creditPercent: 0 };
      
      // Затраты на реализацию = материалы + расходы + транспорт ТО + зарплата тех.отдела
      const totalCost = manual.materials + expensesTotal + transportTotal + manual.transportToClient + manual.contractors + manual.creditPercent + techSalary;
      const marginalIncome = manual.salePrice - totalCost;
      
      return {
        project,
        salePriceManual: manual.salePrice,
        materialsManual: manual.materials,
        techHours,
        techSalary,
        expenses: {
          employeeExpenses,
          household,
          advances,
          tickets,
          perDiem,
          other,
          total: expensesTotal,
        },
        transport: {
          techTransport,
          other: transportOther,
          total: transportTotal,
        },
        transportToClientManual: manual.transportToClient,
        contractorsManual: manual.contractors,
        creditPercentManual: manual.creditPercent,
        totalCost,
        marginalIncome,
      };
    });
  }, [projects, expenses, timeEntries, manualData, allSalaryComponents, allUsers, periodType, selectedPeriod]);

  const totals = useMemo(() => {
    return projectData.reduce((acc, row) => ({
      salePriceManual: acc.salePriceManual + row.salePriceManual,
      materialsManual: acc.materialsManual + row.materialsManual,
      techHours: acc.techHours + row.techHours,
      techSalary: acc.techSalary + row.techSalary,
      expensesTotal: acc.expensesTotal + row.expenses.total,
      transportTotal: acc.transportTotal + row.transport.total,
      transportToClientManual: acc.transportToClientManual + row.transportToClientManual,
      contractorsManual: acc.contractorsManual + row.contractorsManual,
      creditPercentManual: acc.creditPercentManual + row.creditPercentManual,
      totalCost: acc.totalCost + row.totalCost,
      marginalIncome: acc.marginalIncome + row.marginalIncome,
    }), {
      salePriceManual: 0,
      materialsManual: 0,
      techHours: 0,
      techSalary: 0,
      expensesTotal: 0,
      transportTotal: 0,
      transportToClientManual: 0,
      contractorsManual: 0,
      creditPercentManual: 0,
      totalCost: 0,
      marginalIncome: 0,
    });
  }, [projectData]);

  const handleCellEdit = (projectId: string, field: string, currentValue: number) => {
    if (!canEdit) return;
    setEditingCell({ projectId, field });
    setEditValue(currentValue.toString());
  };

  const handleCellSave = async () => {
    if (!editingCell) return;
    try {
      const project = projects.find(p => p.id === editingCell.projectId);
      if (!project) return;

      const currentManual = manualData[editingCell.projectId] || { salePrice: 0, materials: 0, transportToClient: 0, contractors: 0, creditPercent: 0 };
      let updatedManual = { ...currentManual };

      if (editingCell.field === 'salePrice') {
        updatedManual.salePrice = parseFloat(editValue) || 0;
      } else if (editingCell.field === 'materials') {
        updatedManual.materials = parseFloat(editValue) || 0;
      } else if (editingCell.field === 'transportToClient') {
        updatedManual.transportToClient = parseFloat(editValue) || 0;
      } else if (editingCell.field === 'contractors') {
        updatedManual.contractors = parseFloat(editValue) || 0;
      } else if (editingCell.field === 'creditPercent') {
        updatedManual.creditPercent = parseFloat(editValue) || 0;
      }

      setManualData(prev => ({ ...prev, [editingCell.projectId]: updatedManual }));

      // Сохраняем в проект
      const updatedProject = {
        ...project,
        salePrice: updatedManual.salePrice,
        transportToClient: updatedManual.transportToClient,
        materials: updatedManual.materials,
        contractors: updatedManual.contractors,
        creditPercent: updatedManual.creditPercent,
      };
      await api.put('/projects', updatedProject);
      setProjects(prev => prev.map(p => p.id === updatedProject.id ? updatedProject : p));
    } catch (err) {
      console.error('Ошибка сохранения:', err);
      alert('Ошибка сохранения');
    } finally {
      setEditingCell(null);
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') {
      handleCellSave();
    } else if (e.key === 'Escape') {
      setEditingCell(null);
    }
  };

  const handleExportXLSX = () => {
    // Создаем данные для экспорта со всеми колонками (включая раскрытые детали)
    const exportData = projectData.map(row => ({
      'Проект': row.project.name,
      'Клиент': row.project.client || '',
      'Продажная стоимость': row.salePriceManual,
      'Материалы': row.materialsManual,
      'Часы ТО': row.techHours,
      'Зарплата ТО': row.techSalary,
      'Затраты на реализацию - Расходы': row.expenses.employeeExpenses,
      'Затраты на реализацию - Хоз.нужды': row.expenses.household,
      'Затраты на реализацию - Авансы': row.expenses.advances,
      'Затраты на реализацию - Билеты': row.expenses.tickets,
      'Затраты на реализацию - Проживание': row.expenses.perDiem,
      'Затраты на реализацию - Иные': row.expenses.other,
      'Транспорт - Транспорт ТО': row.transport.techTransport,
      'Транспорт - Другое': row.transport.other,
      'Транспорт до клиента': row.transportToClientManual,
      'Услуги подрядчиков': row.contractorsManual,
      '% по кредиту': row.creditPercentManual,
      'Итого с/с': row.totalCost,
      'Марж. доход': row.marginalIncome,
      '% менеджеру': 0, // Заглушка
      'Менеджер': 'ФИО менеджера', // Заглушка
    }));

    // Добавляем итоговую строку
    exportData.push({
      'Проект': 'ИТОГО',
      'Клиент': '',
      'Продажная стоимость': totals.salePriceManual,
      'Материалы': totals.materialsManual,
      'Часы ТО': totals.techHours,
      'Зарплата ТО': totals.techSalary,
      'Затраты на реализацию - Расходы': projectData.reduce((sum, r) => sum + r.expenses.employeeExpenses, 0),
      'Затраты на реализацию - Хоз.нужды': projectData.reduce((sum, r) => sum + r.expenses.household, 0),
      'Затраты на реализацию - Авансы': projectData.reduce((sum, r) => sum + r.expenses.advances, 0),
      'Затраты на реализацию - Билеты': projectData.reduce((sum, r) => sum + r.expenses.tickets, 0),
      'Затраты на реализацию - Проживание': projectData.reduce((sum, r) => sum + r.expenses.perDiem, 0),
      'Затраты на реализацию - Иные': projectData.reduce((sum, r) => sum + r.expenses.other, 0),
      'Транспорт - Транспорт ТО': projectData.reduce((sum, r) => sum + r.transport.techTransport, 0),
      'Транспорт - Другое': projectData.reduce((sum, r) => sum + r.transport.other, 0),
      'Транспорт до клиента': totals.transportToClientManual,
      'Услуги подрядчиков': totals.contractorsManual,
      '% по кредиту': totals.creditPercentManual,
      'Итого с/с': totals.totalCost,
      'Марж. доход': totals.marginalIncome,
      '% менеджеру': 0,
      'Менеджер': '',
    });

    // Создаем workbook и worksheet
    const wb = XLSX.utils.book_new();
    const ws = XLSX.utils.json_to_sheet(exportData);

    // Настраиваем ширину колонок
    const colWidths = [
      { wch: 30 }, // Проект
      { wch: 25 }, // Клиент
      { wch: 18 }, // Продажная стоимость
      { wch: 15 }, // Материалы
      { wch: 12 }, // Часы ТО
      { wch: 15 }, // Зарплата ТО
      { wch: 18 }, // Затраты - Расходы
      { wch: 15 }, // Затраты - Хоз.нужды
      { wch: 12 }, // Затраты - Авансы
      { wch: 12 }, // Затраты - Билеты
      { wch: 15 }, // Затраты - Проживание
      { wch: 12 }, // Затраты - Иные
      { wch: 18 }, // Транспорт - ТО
      { wch: 12 }, // Транспорт - Другое
      { wch: 18 }, // Транспорт до клиента
      { wch: 18 }, // Услуги подрядчиков
      { wch: 15 }, // % по кредиту
      { wch: 15 }, // Итого с/с
      { wch: 15 }, // Марж. доход
      { wch: 15 }, // % менеджеру
      { wch: 25 }, // Менеджер
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

    // Стили для итоговой строки
    const lastRow = exportData.length + 1;
    for (let C = range.s.c; C <= range.e.c; ++C) {
      const address = XLSX.utils.encode_col(C) + lastRow.toString();
      if (!ws[address]) continue;
      ws[address].s = {
        font: { bold: true },
        fill: { fgColor: { rgb: 'E0E7FF' } },
        alignment: { horizontal: 'center', vertical: 'center' },
        border: {
          top: { style: 'medium', color: { rgb: '000000' } },
          bottom: { style: 'thin', color: { rgb: '000000' } },
          left: { style: 'thin', color: { rgb: '000000' } },
          right: { style: 'thin', color: { rgb: '000000' } },
        },
      };
    }

    // Добавляем worksheet в workbook
    XLSX.utils.book_append_sheet(wb, ws, 'Расчёт себестоимости');

    // Генерируем имя файла с датой
    const now = new Date();
    const dateStr = now.toISOString().split('T')[0];
    const fileName = `Расчёт_себестоимости_${dateStr}.xlsx`;

    // Сохраняем файл
    const wbout = XLSX.write(wb, { bookType: 'xlsx', type: 'array' });
    saveAs(new Blob([wbout], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), fileName);
  };

  if (permLoading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;
  if (!canView) return (
    <div className="card p-12 text-center">
      <div className="text-5xl mb-4 opacity-50">🔒</div>
      <h3 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Нет доступа</h3>
      <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">У вас нет прав на просмотр себестоимости</p>
    </div>
  );
  if (loading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;

  return (
    <div className="space-y-6 max-w-full mx-auto">
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-slate-100">🧮 Расчёт себестоимости</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            {projectData.length} проектов • Нажмите на ячейку для редактирования (требуются права редактора)
          </p>
        </div>
        <div className="flex items-center gap-3">
          {/* Фильтр по периоду */}
          <div className="flex items-center gap-2 bg-white dark:bg-slate-800 px-3 py-2 rounded-lg shadow-sm border border-slate-200 dark:border-slate-700">
            <span className="text-xs font-medium text-slate-600 dark:text-slate-400">Период:</span>
            <select
              value={periodType}
              onChange={(e) => setPeriodType(e.target.value as 'month' | 'quarter' | 'year')}
              className="text-xs border border-slate-300 dark:border-slate-600 rounded px-2 py-1 bg-white dark:bg-slate-700 text-slate-900 dark:text-slate-100 focus:ring-2 focus:ring-indigo-500"
            >
              <option value="month">Месяц</option>
              <option value="quarter">Квартал</option>
              <option value="year">Год</option>
            </select>
            {periodType === 'month' && (
              <input
                type="month"
                value={selectedPeriod}
                onChange={(e) => setSelectedPeriod(e.target.value)}
                className="text-xs border border-slate-300 dark:border-slate-600 rounded px-2 py-1 bg-white dark:bg-slate-700 text-slate-900 dark:text-slate-100 focus:ring-2 focus:ring-indigo-500"
              />
            )}
            {periodType === 'quarter' && (
              <select
                value={selectedPeriod}
                onChange={(e) => setSelectedPeriod(e.target.value)}
                className="text-xs border border-slate-300 dark:border-slate-600 rounded px-2 py-1 bg-white dark:bg-slate-700 text-slate-900 dark:text-slate-100 focus:ring-2 focus:ring-indigo-500"
              >
                {Array.from({ length: 5 }, (_, i) => new Date().getFullYear() - i).map(year => (
                  <optgroup key={year} label={`${year}`}>
                    <option value={`${year}-Q1`}>Q1 (Янв - Мар)</option>
                    <option value={`${year}-Q2`}>Q2 (Апр - Июн)</option>
                    <option value={`${year}-Q3`}>Q3 (Июл - Сен)</option>
                    <option value={`${year}-Q4`}>Q4 (Окт - Дек)</option>
                  </optgroup>
                ))}
              </select>
            )}
            {periodType === 'year' && (
              <select
                value={selectedPeriod}
                onChange={(e) => setSelectedPeriod(e.target.value)}
                className="text-xs border border-slate-300 dark:border-slate-600 rounded px-2 py-1 bg-white dark:bg-slate-700 text-slate-900 dark:text-slate-100 focus:ring-2 focus:ring-indigo-500"
              >
                {Array.from({ length: 5 }, (_, i) => new Date().getFullYear() - i).map(year => (
                  <option key={year} value={`${year}`}>{year}</option>
                ))}
              </select>
            )}
          </div>
          <button
            onClick={handleExportXLSX}
            className="px-4 py-2 bg-green-600 hover:bg-green-700 text-white rounded-lg shadow-md transition-all duration-300 flex items-center gap-2 text-sm font-medium"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
            </svg>
            Экспорт в XLSX
          </button>
        </div>
      </div>

      {/* Таблица в стиле Excel */}
      <div className="card overflow-x-auto">
        <table className="w-full text-xs border-collapse">
          <thead>
            {/* Заголовок - строка 1: Группы колонок */}
            <tr className="bg-slate-100 dark:bg-slate-800">
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-left font-semibold text-slate-700 dark:text-slate-300 min-w-[200px]">Проект</th>
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-right font-semibold text-slate-700 dark:text-slate-300 w-[120px]">Продажная стоимость</th>
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-right font-semibold text-slate-700 dark:text-slate-300 w-[100px]">Материалы</th>
              <th colSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-center font-semibold text-slate-700 dark:text-slate-300 bg-green-50 dark:bg-green-900/30">Часы ТО и Зарплата</th>
              <th colSpan={showExpensesDetail ? 6 : 1} className={`border border-slate-300 dark:border-slate-600 p-2 ${showExpensesDetail ? 'text-center' : 'text-right'} font-semibold text-slate-700 dark:text-slate-300 bg-indigo-50 dark:bg-indigo-900/30 cursor-pointer select-none transition-all duration-300`} onClick={() => setShowExpensesDetail(!showExpensesDetail)}>
                Затраты на реализацию {showExpensesDetail ? '▼' : '▶'}
              </th>
              <th colSpan={showTransportDetail ? 2 : 1} className={`border border-slate-300 dark:border-slate-600 p-2 ${showTransportDetail ? 'text-center' : 'text-right'} font-semibold text-slate-700 dark:text-slate-300 bg-blue-50 dark:bg-blue-900/30 cursor-pointer select-none transition-all duration-300`} onClick={() => setShowTransportDetail(!showTransportDetail)}>
                Транспорт {showTransportDetail ? '▼' : '▶'}
              </th>
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-right font-semibold text-slate-700 dark:text-slate-300 w-[120px]">Транспорт до клиента</th>
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-right font-semibold text-slate-700 dark:text-slate-300 w-[120px]">Услуги подрядчиков</th>
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-right font-semibold text-slate-700 dark:text-slate-300 w-[120px]">% по кредиту</th>
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-right font-semibold text-slate-700 dark:text-slate-300 w-[120px] bg-emerald-50 dark:bg-emerald-900/30">Итого с/с</th>
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-right font-semibold text-slate-700 dark:text-slate-300 w-[120px] bg-amber-50 dark:bg-amber-900/30">Марж. доход</th>
              <th rowSpan={2} className="border border-slate-300 dark:border-slate-600 p-2 text-right font-semibold text-slate-700 dark:text-slate-300 w-[120px] bg-purple-50 dark:bg-purple-900/30">% менеджеру</th>
            </tr>
            {/* Заголовок - строка 2: Подколонки */}
            <tr className="bg-slate-50 dark:bg-slate-800/50">
              {/* Часы ТО и Зарплата - подколонки */}
              <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[80px]">Часы</th>
              <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[120px]">Зарплата</th>
              {/* Затраты на реализацию - подколонки */}
              {showExpensesDetail && (
                <>
                  <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[100px]">Расходы</th>
                  <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[90px]">Хоз.нужды</th>
                  <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[80px]">Авансы</th>
                  <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[80px]">Билеты</th>
                  <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[90px]">Проживание</th>
                  <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[80px]">Иные</th>
                </>
              )}
              {/* Транспорт - подколонки */}
              {showTransportDetail && (
                <>
                  <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[110px]">Транспорт ТО</th>
                  <th className="border border-slate-300 dark:border-slate-600 p-2 text-right font-medium text-slate-600 dark:text-slate-400 w-[80px]">Другое</th>
                </>
              )}
            </tr>
          </thead>
          <tbody>
            {projectData.map((row) => {
              return (
                <tr key={row.project.id} className="hover:bg-slate-50 dark:hover:bg-slate-800/30 transition-all duration-300">
                  {/* Проект */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2">
                    <div className="font-medium text-slate-900 dark:text-slate-100 truncate">{row.project.name}</div>
                    {row.project.client && <div className="text-xs text-slate-500 truncate">{row.project.client}</div>}
                  </td>
                  
                  {/* Продажная стоимость */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right">
                    {canEdit ? (
                      editingCell?.projectId === row.project.id && editingCell?.field === 'salePrice' ? (
                        <input
                          type="number"
                          step="0.01"
                          value={editValue}
                          onChange={(e) => setEditValue(e.target.value)}
                          onBlur={handleCellSave}
                          onKeyDown={handleKeyDown}
                          className="w-full text-right px-1 py-0.5 border border-indigo-400 rounded focus:outline-none focus:ring-2 focus:ring-indigo-500"
                          autoFocus
                        />
                      ) : (
                        <button
                          onClick={() => handleCellEdit(row.project.id, 'salePrice', row.salePriceManual)}
                          className="w-full text-right hover:bg-indigo-50 dark:hover:bg-indigo-900/30 rounded px-1 py-0.5 cursor-pointer"
                        >
                          {row.salePriceManual > 0 ? formatMoney(row.salePriceManual) : '—'}
                        </button>
                      )
                    ) : (
                      row.salePriceManual > 0 ? formatMoney(row.salePriceManual) : '—'
                    )}
                  </td>
                  
                  {/* Материалы */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right">
                    {canEdit ? (
                      editingCell?.projectId === row.project.id && editingCell?.field === 'materials' ? (
                        <input
                          type="number"
                          step="0.01"
                          value={editValue}
                          onChange={(e) => setEditValue(e.target.value)}
                          onBlur={handleCellSave}
                          onKeyDown={handleKeyDown}
                          className="w-full text-right px-1 py-0.5 border border-indigo-400 rounded focus:outline-none focus:ring-2 focus:ring-indigo-500"
                          autoFocus
                        />
                      ) : (
                        <button
                          onClick={() => handleCellEdit(row.project.id, 'materials', row.materialsManual)}
                          className="w-full text-right hover:bg-indigo-50 dark:hover:bg-indigo-900/30 rounded px-1 py-0.5 cursor-pointer"
                        >
                          {row.materialsManual > 0 ? formatMoney(row.materialsManual) : '—'}
                        </button>
                      )
                    ) : (
                      row.materialsManual > 0 ? formatMoney(row.materialsManual) : '—'
                    )}
                  </td>
                  
                  {/* Часы ТО */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-700 dark:text-slate-300">
                    {row.techHours > 0 ? row.techHours.toFixed(1) : '—'}
                  </td>
                  
                  {/* Зарплата ТО */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-700 dark:text-slate-300 bg-green-50/30 dark:bg-green-900/10">
                    {formatMoney(row.techSalary || 0)}
                  </td>
                  
                  {/* Затраты на реализацию - основная колонка (Σ) */}
                  {showExpensesDetail ? null : (
                    <td className="border border-slate-200 dark:border-slate-700 p-2 text-right bg-indigo-50/30 dark:bg-indigo-900/10">
                      <button
                        onClick={() => setShowExpensesDetail(true)}
                        className="w-full text-right font-medium hover:bg-indigo-100 dark:hover:bg-indigo-900/30 rounded px-1 py-0.5 flex items-center justify-end gap-1"
                      >
                        <span>{formatMoney(row.expenses.total)}</span>
                      </button>
                    </td>
                  )}
                  
                  {/* Скрытые колонки расходов */}
                  {showExpensesDetail && (
                    <>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.expenses.employeeExpenses > 0 ? formatMoney(row.expenses.employeeExpenses) : '—'}</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.expenses.household > 0 ? formatMoney(row.expenses.household) : '—'}</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.expenses.advances > 0 ? formatMoney(row.expenses.advances) : '—'}</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.expenses.tickets > 0 ? formatMoney(row.expenses.tickets) : '—'}</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.expenses.perDiem > 0 ? formatMoney(row.expenses.perDiem) : '—'}</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.expenses.other > 0 ? formatMoney(row.expenses.other) : '—'}</td>
                    </>
                  )}
                  
                  {/* Транспорт - основная колонка (Σ) */}
                  {showTransportDetail ? null : (
                    <td className="border border-slate-200 dark:border-slate-700 p-2 text-right bg-blue-50/30 dark:bg-blue-900/10">
                      <button
                        onClick={() => setShowTransportDetail(true)}
                        className="w-full text-right font-medium hover:bg-blue-100 dark:hover:bg-blue-900/30 rounded px-1 py-0.5 flex items-center justify-end gap-1"
                      >
                        <span>{formatMoney(row.transport.total)}</span>
                      </button>
                    </td>
                  )}
                  
                  {/* Скрытые колонки транспорта */}
                  {showTransportDetail && (
                    <>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.transport.techTransport > 0 ? formatMoney(row.transport.techTransport) : '—'}</td>
                      <td className="border border-slate-200 dark:border-slate-700 p-2 text-right tabular-nums text-slate-600 dark:text-slate-400">{row.transport.other > 0 ? formatMoney(row.transport.other) : '—'}</td>
                    </>
                  )}
                  
                  {/* Транспорт до клиента */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right">
                    {canEdit ? (
                      editingCell?.projectId === row.project.id && editingCell?.field === 'transportToClient' ? (
                        <input
                          type="number"
                          step="0.01"
                          value={editValue}
                          onChange={(e) => setEditValue(e.target.value)}
                          onBlur={handleCellSave}
                          onKeyDown={handleKeyDown}
                          className="w-full text-right px-1 py-0.5 border border-indigo-400 rounded focus:outline-none focus:ring-2 focus:ring-indigo-500"
                          autoFocus
                        />
                      ) : (
                        <button
                          onClick={() => handleCellEdit(row.project.id, 'transportToClient', row.transportToClientManual)}
                          className="w-full text-right hover:bg-indigo-50 dark:hover:bg-indigo-900/30 rounded px-1 py-0.5 cursor-pointer"
                        >
                          {row.transportToClientManual > 0 ? formatMoney(row.transportToClientManual) : '—'}
                        </button>
                      )
                    ) : (
                      row.transportToClientManual > 0 ? formatMoney(row.transportToClientManual) : '—'
                    )}
                  </td>
                  
                  {/* Услуги подрядчиков */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right">
                    {canEdit ? (
                      editingCell?.projectId === row.project.id && editingCell?.field === 'contractors' ? (
                        <input
                          type="number"
                          step="0.01"
                          value={editValue}
                          onChange={(e) => setEditValue(e.target.value)}
                          onBlur={handleCellSave}
                          onKeyDown={handleKeyDown}
                          className="w-full text-right px-1 py-0.5 border border-indigo-400 rounded focus:outline-none focus:ring-2 focus:ring-indigo-500"
                          autoFocus
                        />
                      ) : (
                        <button
                          onClick={() => handleCellEdit(row.project.id, 'contractors', row.contractorsManual)}
                          className="w-full text-right hover:bg-indigo-50 dark:hover:bg-indigo-900/30 rounded px-1 py-0.5 cursor-pointer"
                        >
                          {row.contractorsManual > 0 ? formatMoney(row.contractorsManual) : '—'}
                        </button>
                      )
                    ) : (
                      row.contractorsManual > 0 ? formatMoney(row.contractorsManual) : '—'
                    )}
                  </td>
                  
                  {/* % по кредиту */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right">
                    {canEdit ? (
                      editingCell?.projectId === row.project.id && editingCell?.field === 'creditPercent' ? (
                        <input
                          type="number"
                          step="0.01"
                          value={editValue}
                          onChange={(e) => setEditValue(e.target.value)}
                          onBlur={handleCellSave}
                          onKeyDown={handleKeyDown}
                          className="w-full text-right px-1 py-0.5 border border-indigo-400 rounded focus:outline-none focus:ring-2 focus:ring-indigo-500"
                          autoFocus
                        />
                      ) : (
                        <button
                          onClick={() => handleCellEdit(row.project.id, 'creditPercent', row.creditPercentManual)}
                          className="w-full text-right hover:bg-indigo-50 dark:hover:bg-indigo-900/30 rounded px-1 py-0.5 cursor-pointer"
                        >
                          {row.creditPercentManual > 0 ? formatMoney(row.creditPercentManual) : '—'}
                        </button>
                      )
                    ) : (
                      row.creditPercentManual > 0 ? formatMoney(row.creditPercentManual) : '—'
                    )}
                  </td>
                  
                  {/* Итого с/с */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right font-bold bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-400">
                    {formatMoney(row.totalCost)}
                  </td>
                  
                  {/* Марж. доход */}
                  <td className={`border border-slate-200 dark:border-slate-700 p-2 text-right font-bold bg-amber-50 dark:bg-amber-900/20 ${row.marginalIncome >= 0 ? 'text-green-700 dark:text-green-400' : 'text-red-700 dark:text-red-400'}`}>
                    {formatMoney(row.marginalIncome)}
                  </td>
                  
                  {/* % менеджеру - заглушка */}
                  <td className="border border-slate-200 dark:border-slate-700 p-2 text-right bg-purple-50 dark:bg-purple-900/20">
                    <div className="font-medium text-purple-700 dark:text-purple-400">—</div>
                    <div className="text-xs text-slate-500 dark:text-slate-400">Менеджер</div>
                  </td>
                </tr>
              );
            })}
          </tbody>
          <tfoot>
            <tr className="bg-slate-100 dark:bg-slate-800 font-bold">
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-left">ИТОГО</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(totals.salePriceManual)}</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(totals.materialsManual)}</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{totals.techHours.toFixed(1)}</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right bg-green-50/30 dark:bg-green-900/10">{formatMoney(totals.techSalary || 0)}</td>
              {showExpensesDetail ? (
                <>
                  <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(projectData.reduce((sum, row) => sum + row.expenses.employeeExpenses, 0))}</td>
                  <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(projectData.reduce((sum, row) => sum + row.expenses.household, 0))}</td>
                  <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(projectData.reduce((sum, row) => sum + row.expenses.advances, 0))}</td>
                  <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(projectData.reduce((sum, row) => sum + row.expenses.tickets, 0))}</td>
                  <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(projectData.reduce((sum, row) => sum + row.expenses.perDiem, 0))}</td>
                  <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(projectData.reduce((sum, row) => sum + row.expenses.other, 0))}</td>
                </>
              ) : (
                <td className="border border-slate-300 dark:border-slate-600 p-2 text-right bg-indigo-100 dark:bg-indigo-900/30">{formatMoney(totals.expensesTotal)}</td>
              )}
              {showTransportDetail ? (
                <>
                  <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(projectData.reduce((sum, row) => sum + row.transport.techTransport, 0))}</td>
                  <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(projectData.reduce((sum, row) => sum + row.transport.other, 0))}</td>
                </>
              ) : (
                <td className="border border-slate-300 dark:border-slate-600 p-2 text-right bg-blue-100 dark:bg-blue-900/30">{formatMoney(totals.transportTotal)}</td>
              )}
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(totals.transportToClientManual)}</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(totals.contractorsManual)}</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right">{formatMoney(totals.creditPercentManual)}</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400">{formatMoney(totals.totalCost)}</td>
              <td className={`border border-slate-300 dark:border-slate-600 p-2 text-right bg-amber-100 dark:bg-amber-900/30 ${totals.marginalIncome >= 0 ? 'text-green-700 dark:text-green-400' : 'text-red-700 dark:text-red-400'}`}>{formatMoney(totals.marginalIncome)}</td>
              <td className="border border-slate-300 dark:border-slate-600 p-2 text-right bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400">—</td>
            </tr>
          </tfoot>
        </table>
      </div>

      {/* Легенда */}
      <div className="card p-4 bg-slate-50 dark:bg-slate-800/50 text-xs text-slate-600 dark:text-slate-400">
        <div className="font-semibold mb-2">📋 Пояснения:</div>
        <ul className="space-y-1">
          <li><span className="font-medium">Материалы</span> — заполняется вручную администратором</li>
          <li><span className="font-medium">Часы ТО</span> — сумма часов тех.отдела (роль employee) из табеля</li>
          <li><span className="font-medium">Затраты на реализацию</span> — сумма: Материалы + Расходы + Транспорт ТО</li>
          <li><span className="font-medium">Расходы</span> — сумма расходов всех сотрудников к проекту (кроме хоз.нужд, суточных, транспорта)</li>
          <li><span className="font-medium">Хоз.нужды</span> — расходы типа HOUSEHOLD</li>
          <li><span className="font-medium">Авансы</span> — выплаты сотрудникам по авансам (пока заглушка 0)</li>
          <li><span className="font-medium">Билеты</span> — расходы по билетам</li>
          <li><span className="font-medium">Проживание</span> — суточные (тип PER_DIEM)</li>
          <li><span className="font-medium">Транспорт ТО</span> — расходы тех.отдела на дорогу (тип ROAD)</li>
          <li><span className="font-medium">Транспорт до клиента</span> — заполняется вручную администратором</li>
          <li><span className="font-medium">Услуги подрядчиков</span> — заполняется вручную администратором</li>
          <li><span className="font-medium">% по кредиту</span> — заполняется вручную администратором</li>
        </ul>
      </div>
    </div>
  );
}
