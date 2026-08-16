import { useState, useEffect, useMemo } from 'react';
import { PieChart, Pie, Cell, ResponsiveContainer, Legend, Tooltip, Sector } from 'recharts';
import api from '../api/client';
import type { ExpenseDto, UserDto } from '../types';
import { formatMoney } from '../lib/utils';
import './AnalyticsPage.css';

// ═══════════════════════════════════════════════════════
// 🎨 Цветовая палитра (зеленые тона + акценты)
// ═══════════════════════════════════════════════════════
const COLORS = [
  '#10b981', // emerald-500
  '#34d399', // emerald-400
  '#6ee7b7', // emerald-300
  '#a7f3d0', // emerald-200
  '#059669', // emerald-600
  '#047857', // emerald-700
  '#06b6d4', // cyan-500
  '#3b82f6', // blue-500
  '#8b5cf6', // violet-500
  '#f59e0b', // amber-500
];

// ═══════════════════════════════════════════════════════
// 💱 Курсы валют (можно заменить на API)
// ═══════════════════════════════════════════════════════
const EXCHANGE_RATES: Record<string, number> = {
  RUB: 1,
  USD: 92,
  EUR: 100,
  BYN: 28,
};

interface ProjectExpense {
  projectId: string;
  projectName: string;
  total: number;
  totalRub: number;
  expenses: ExpenseDto[];
}

interface EmployeeExpense {
  userId: string;
  userName: string;
  total: number;
  totalRub: number;
  expenses: ExpenseDto[];
}

interface ChartDataItem {
  name: string;
  fill: string;
  total: number;
  totalRub: number;
  expenses: ExpenseDto[];
  projectId?: string;
  projectName?: string;
  userId?: string;
  userName?: string;
}

type DrillLevel = 'projects' | 'employees' | 'details';

export function AnalyticsPage() {
  const [expenses, setExpenses] = useState<ExpenseDto[]>([]);
  const [users, setUsers] = useState<UserDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [convertToRub, setConvertToRub] = useState(false);
  
  // ═══════════════════════════════════════════════════════
  // 📊 Состояние навигации по уровням детализации
  // ═══════════════════════════════════════════════════════
  const [drillLevel, setDrillLevel] = useState<DrillLevel>('projects');
  const [selectedProject, setSelectedProject] = useState<ProjectExpense | null>(null);
  const [selectedEmployee, setSelectedEmployee] = useState<EmployeeExpense | null>(null);
  const [highlightedExpenseId, setHighlightedExpenseId] = useState<string | null>(null);

  useEffect(() => {
    loadData();
  }, []);

  async function loadData() {
    try {
      const userStr = localStorage.getItem('proles_user');
      if (!userStr) return;

      const [expensesRes, usersRes] = await Promise.all([
        api.get<ExpenseDto[]>('/expenses/all'),
        api.get<UserDto[]>('/users'),
      ]);

      setExpenses(expensesRes.data);
      setUsers(usersRes.data);
    } catch (err) {
      console.error('Ошибка загрузки:', err);
    } finally {
      setLoading(false);
    }
  }

  // ═══════════════════════════════════════════════════════
  // 💰 Агрегация данных по проектам
  // ═══════════════════════════════════════════════════════
  const projectData = useMemo(() => {
    const map = new Map<string, ProjectExpense>();
    
    expenses.forEach((exp) => {
      if (!map.has(exp.projectId)) {
        map.set(exp.projectId, {
          projectId: exp.projectId,
          projectName: exp.projectName,
          total: 0,
          totalRub: 0,
          expenses: [],
        });
      }
      const proj = map.get(exp.projectId)!;
      proj.total += exp.amount;
      proj.totalRub += exp.amount * (EXCHANGE_RATES[exp.currency] || 1);
      proj.expenses.push(exp);
    });

    return Array.from(map.values())
      .filter((p) => p.total > 0)
      .sort((a, b) => b.totalRub - a.totalRub);
  }, [expenses]);

  // ═══════════════════════════════════════════════════════
  // 👥 Агрегация данных по сотрудникам внутри проекта
  // ═══════════════════════════════════════════════════════
  const employeeData = useMemo(() => {
    if (!selectedProject) return [];

    const map = new Map<string, EmployeeExpense>();
    
    selectedProject.expenses.forEach((exp) => {
      const user = users.find((u) => u.id === exp.userId);
      const userName = user ? `${user.lastName} ${user.firstName}` : 'Неизвестный';
      
      if (!map.has(exp.userId)) {
        map.set(exp.userId, {
          userId: exp.userId,
          userName,
          total: 0,
          totalRub: 0,
          expenses: [],
        });
      }
      const emp = map.get(exp.userId)!;
      emp.total += exp.amount;
      emp.totalRub += exp.amount * (EXCHANGE_RATES[exp.currency] || 1);
      emp.expenses.push(exp);
    });

    return Array.from(map.values())
      .filter((e) => e.total > 0)
      .sort((a, b) => b.totalRub - a.totalRub);
  }, [selectedProject, users]);

  // ═══════════════════════════════════════════════════════
  // 📋 Список расходов для отображения
  // ═══════════════════════════════════════════════════════
  const displayExpenses = useMemo(() => {
    if (drillLevel === 'details' && selectedEmployee) {
      return selectedEmployee.expenses;
    }
    if (drillLevel === 'employees' && selectedProject) {
      return selectedProject.expenses;
    }
    return expenses;
  }, [drillLevel, selectedProject, selectedEmployee, expenses]);

  // ═══════════════════════════════════════════════════════
  // 🧮 Общая сумма
  // ═══════════════════════════════════════════════════════
  const totalAmount = useMemo(() => {
    const data = drillLevel === 'projects' 
      ? projectData 
      : drillLevel === 'employees' 
        ? employeeData 
        : selectedEmployee?.expenses || [];
    
    return data.reduce((sum, item: any) => sum + (convertToRub ? item.totalRub : item.total), 0);
  }, [drillLevel, projectData, employeeData, selectedEmployee, convertToRub]);

  // ═══════════════════════════════════════════════════════
  // 🔙 Навигация назад
  // ═══════════════════════════════════════════════════════
  function handleBack() {
    if (drillLevel === 'details') {
      setDrillLevel('employees');
      setSelectedEmployee(null);
      setHighlightedExpenseId(null);
    } else if (drillLevel === 'employees') {
      setDrillLevel('projects');
      setSelectedProject(null);
    }
  }

  // ═══════════════════════════════════════════════════════
  // 🍕 Кастомный активный сектор (3D эффект)
  // ═══════════════════════════════════════════════════════
  const renderActiveShape = (props: any) => {
    const { cx, cy, midAngle, innerRadius, outerRadius, startAngle, endAngle, fill, payload } = props;
    const RADIAN = Math.PI / 180;
    const cos = Math.cos(-RADIAN * midAngle);
    const sin = Math.sin(-RADIAN * midAngle);
    const sx = cx + (outerRadius + 10) * cos;
    const sy = cy + (outerRadius + 10) * sin;
    const mx = cx + (outerRadius + 30) * cos;
    const my = cy + (outerRadius + 30) * sin;
    const ex = mx + (cos >= 0 ? 1 : -1) * 22;
    const ey = my;
    const textAnchor = cos >= 0 ? 'start' : 'end';
    // Отображаем валюту из первого расхода проекта/сотрудника
    const originalCurrency = payload.expenses?.[0]?.currency || 'RUB';
    const value = convertToRub ? payload.totalRub : payload.total;
    const displayCurrency = convertToRub ? 'RUB' : originalCurrency;

    return (
      <g className="pie-sector">
        {/* Основной сектор с 3D тенью */}
        <Sector
          cx={cx}
          cy={cy}
          innerRadius={innerRadius}
          outerRadius={outerRadius}
          startAngle={startAngle}
          endAngle={endAngle}
          fill={fill}
          style={{ filter: 'drop-shadow(0 4px 6px rgba(0,0,0,0.3))' }}
        />
        {/* Вынесенный сектор для эффекта 3D */}
        <Sector
          cx={cx}
          cy={cy}
          innerRadius={innerRadius - 2}
          outerRadius={outerRadius + 8}
          startAngle={startAngle}
          endAngle={endAngle}
          fill={fill}
          opacity={0.3}
          style={{ transform: `translate(${cos * 4}px, ${sin * 4}px)` }}
        />
        {/* Линия-выноска с анимацией */}
        <path 
          d={`M${sx},${sy}L${mx},${my}L${ex},${ey}`} 
          stroke={fill} 
          fill="none" 
          strokeWidth={2} 
          className="callout-line"
        />
        {/* Кружок на конце выноски */}
        <circle cx={ex} cy={ey} r={3} fill={fill} />
        {/* Текст с суммой */}
        <text x={ex + (cos >= 0 ? 12 : -12)} y={ey} textAnchor={textAnchor} fill="#374151" fontSize={14} fontWeight={600}>
          {formatMoney(Math.round(value), displayCurrency)}
        </text>
        {/* Название проекта/сотрудника */}
        <text x={ex + (cos >= 0 ? 12 : -12)} y={ey + 18} textAnchor={textAnchor} fill="#6b7280" fontSize={12}>
          {payload.name}
        </text>
      </g>
    );
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-emerald-500"></div>
      </div>
    );
  }

  const chartData: ChartDataItem[] = drillLevel === 'projects' 
    ? projectData.map((p, i) => ({ ...p, name: p.projectName, fill: COLORS[i % COLORS.length] }))
    : employeeData.map((e, i) => ({ ...e, name: e.userName, fill: COLORS[i % COLORS.length] }));

  // ═══════════════════════════════════════════════════════
  // 🎭 Ключ для анимации при смене уровня
  // ═══════════════════════════════════════════════════════
  const chartKey = `${drillLevel}-${selectedProject?.projectId || ''}-${selectedEmployee?.userId || ''}`;

  return (
    <div className="p-6 space-y-6">
      {/* ═══════════════════════════════════════════════════════
          📌 Заголовок и управление
          ═══════════════════════════════════════════════════════ */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900 dark:text-white">
            {drillLevel === 'projects' && 'Распределение затрат по проектам'}
            {drillLevel === 'employees' && `Затраты по проекту: ${selectedProject?.projectName}`}
            {drillLevel === 'details' && `Расходы: ${selectedEmployee?.userName}`}
          </h1>
          <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">
            {displayExpenses.length} записей
          </p>
        </div>

        <div className="flex items-center gap-3">
          {/* Кнопка "Назад" */}
          {drillLevel !== 'projects' && (
            <button
              onClick={handleBack}
              className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 dark:bg-gray-800 dark:text-gray-200 dark:border-gray-600 dark:hover:bg-gray-700"
            >
              ← Назад
            </button>
          )}

          {/* Переключатель конвертации */}
          <label className="flex items-center gap-2 text-sm text-gray-700 dark:text-gray-300 cursor-pointer">
            <input
              type="checkbox"
              checked={convertToRub}
              onChange={(e) => setConvertToRub(e.target.checked)}
              className="w-4 h-4 text-emerald-600 rounded focus:ring-emerald-500"
            />
            Конвертировать в ₽
          </label>
        </div>
      </div>

      {/* ═══════════════════════════════════════════════════════
          📊 Основная диаграмма
          ═══════════════════════════════════════════════════════ */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Круговая диаграмма */}
        <div className="lg:col-span-2 bg-white dark:bg-gray-800 rounded-2xl shadow-lg p-6 chart-container">
          <h2 className="text-lg font-semibold text-gray-900 dark:text-white mb-4">
            {drillLevel === 'projects' ? 'По проектам' : 'По сотрудникам'}
          </h2>
          
          <div className="h-80" key={chartKey}>
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie
                  data={chartData}
                  cx="50%"
                  cy="50%"
                  labelLine={false}
                  outerRadius={100}
                  innerRadius={60}
                  paddingAngle={2}
                  dataKey={convertToRub ? 'totalRub' : 'total'}
                  nameKey="name"
                  activeShape={renderActiveShape}
                  onClick={(data: any) => {
                    if (drillLevel === 'projects') {
                      setSelectedProject(data.payload);
                      setDrillLevel('employees');
                    } else if (drillLevel === 'employees') {
                      setSelectedEmployee(data.payload);
                      setDrillLevel('details');
                    }
                  }}
                >
                  {chartData.map((entry, index) => (
                    <Cell 
                      key={`cell-${index}`} 
                      fill={entry.fill}
                      className="pie-sector"
                      style={{ 
                        filter: highlightedExpenseId && drillLevel === 'details'
                          ? entry.expenses?.some((e: ExpenseDto) => e.id === highlightedExpenseId)
                            ? 'brightness(1.2) drop-shadow(0 4px 8px rgba(0,0,0,0.4))'
                            : 'brightness(0.7) grayscale(0.5)'
                          : undefined
                      }}
                    />
                  ))}
                </Pie>
                <Tooltip 
                  formatter={(value: any) => {
                    return [
                      formatMoney(Math.round(Number(value)), 'RUB'),
                    ];
                  }}
                  contentStyle={{
                    backgroundColor: 'rgba(255, 255, 255, 0.95)',
                    border: 'none',
                    borderRadius: '12px',
                    boxShadow: '0 10px 40px rgba(0,0,0,0.2)',
                    padding: '16px',
                  }}
                />
                <Legend 
                  verticalAlign="bottom" 
                  height={40}
                  formatter={(value) => (
                    <span className="text-sm text-gray-700 dark:text-gray-300">{value}</span>
                  )}
                />
              </PieChart>
            </ResponsiveContainer>
          </div>

          {/* Итого */}
          <div className="mt-4 pt-4 border-t border-gray-200 dark:border-gray-700 text-center">
            <p className="text-sm text-gray-500 dark:text-gray-400">Общая сумма</p>
            <p className="text-3xl font-bold text-emerald-600 dark:text-emerald-400">
              {formatMoney(Math.round(totalAmount), convertToRub ? 'RUB' : 'RUB')}
            </p>
            {!convertToRub && (
              <p className="text-xs text-gray-400 dark:text-gray-500 mt-1">
                (в рублях по курсу)
              </p>
            )}
          </div>
        </div>

        {/* ═══════════════════════════════════════════════════════
            📋 Детальный список расходов
            ═══════════════════════════════════════════════════════ */}
        <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-lg p-6 overflow-hidden">
          <h2 className="text-lg font-semibold text-gray-900 dark:text-white mb-4">
            {drillLevel === 'details' ? 'Расходы сотрудника' : 'Все расходы'}
          </h2>
          
          <div className="space-y-3 max-h-[500px] overflow-y-auto pr-2">
            {displayExpenses.length === 0 ? (
              <p className="text-gray-500 dark:text-gray-400 text-center py-8">Нет данных</p>
            ) : (
              displayExpenses.map((exp, idx) => {
                const amountRub = exp.amount * (EXCHANGE_RATES[exp.currency] || 1);
                const displayAmount = convertToRub ? amountRub : exp.amount;
                const isHighlighted = highlightedExpenseId === exp.id;

                return (
                  <div
                    key={exp.id}
                    onClick={() => {
                      if (drillLevel === 'details') {
                        setHighlightedExpenseId(exp.id);
                      }
                    }}
                    className={`expense-item p-4 rounded-xl border transition-all cursor-pointer ${
                      isHighlighted
                        ? 'border-emerald-500 bg-emerald-50 dark:bg-emerald-900/20 shadow-md highlighted'
                        : 'border-gray-200 dark:border-gray-700 hover:border-emerald-300 dark:hover:border-emerald-600'
                    }`}
                    style={{ animationDelay: `${idx * 0.05}s` }}
                  >
                    <div className="flex items-start justify-between gap-3">
                      <div className="flex-1 min-w-0">
                        <p className="font-medium text-gray-900 dark:text-white truncate">
                          {exp.name}
                        </p>
                        <p className="text-sm text-gray-500 dark:text-gray-400">
                          {new Date(exp.date).toLocaleDateString('ru-RU', {
                            day: 'numeric',
                            month: 'long',
                            year: 'numeric',
                          })}
                        </p>
                        {drillLevel !== 'details' && (
                          <p className="text-xs text-gray-400 dark:text-gray-500 mt-1">
                            {users.find(u => u.id === exp.userId)?.name || 'Неизвестный'}
                          </p>
                        )}
                        {exp.comment && (
                          <p className="text-xs text-gray-400 dark:text-gray-500 mt-1 line-clamp-2">
                            {exp.comment}
                          </p>
                        )}
                      </div>
                      <div className="text-right">
                        <p className={`font-bold ${isHighlighted ? 'text-emerald-600 dark:text-emerald-400' : 'text-gray-900 dark:text-white'}`}>
                          {formatMoney(Math.round(displayAmount), convertToRub ? 'RUB' : exp.currency)}
                        </p>
                        {convertToRub && exp.currency !== 'RUB' && (
                          <p className="text-xs text-gray-400 dark:text-gray-500">
                            {formatMoney(exp.amount, exp.currency)}
                          </p>
                        )}
                      </div>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
