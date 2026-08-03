import * as XLSX from 'xlsx';
import jsPDF from 'jspdf';
import autoTable from 'jspdf-autotable';
import type { PayrollExportEmployeeDto, SalaryRecordDto } from '../types';
import { monthName } from './utils';
import { ensurePdfFont } from './pdfFont';

// ═══════════════════════════════════════════════════════
// 📊 ЭКСПОРТ В EXCEL (остаётся без изменений)
// ═══════════════════════════════════════════════════════

export function exportPayrollToExcel(
  employees: PayrollExportEmployeeDto[],
  year: number,
  month: number
) {
  const header = [
    ['ОТЧЁТ ПО ЗАРПЛАТЕ'],
    [`Период: ${monthName(month)} ${year}`],
    [`Сформирован: ${new Date().toLocaleString('ru-RU')}`],
    [],
  ];

  const columns = [
    'Сотрудник', 'Должность', 'Роль', 'Часы', 'Рабочих дней',
    'Фикс, ₽', 'Почасовая, ₽', 'Сдельная, ₽', 'Бонус, ₽',
    'Итого ЗП, ₽', 'Расходы, ₽', 'Командировок',
  ];

  const rows = employees.map(e => [
    e.name, e.position || '—', e.role,
    e.totalHours.toFixed(1), e.workDays,
    e.fixed, e.hourly, e.piece, e.bonus,
    e.salaryTotal, e.expensesTotal, e.tripsCount,
  ]);

  const totals = [
    'ИТОГО', '', '',
    employees.reduce((s, e) => s + e.totalHours, 0).toFixed(1),
    employees.reduce((s, e) => s + e.workDays, 0),
    employees.reduce((s, e) => s + e.fixed, 0),
    employees.reduce((s, e) => s + e.hourly, 0),
    employees.reduce((s, e) => s + e.piece, 0),
    employees.reduce((s, e) => s + e.bonus, 0),
    employees.reduce((s, e) => s + e.salaryTotal, 0),
    employees.reduce((s, e) => s + e.expensesTotal, 0),
    employees.reduce((s, e) => s + e.tripsCount, 0),
  ];

  const ws = XLSX.utils.aoa_to_sheet([...header, columns, ...rows, [], totals]);
  ws['!cols'] = [
    { wch: 30 }, { wch: 20 }, { wch: 12 }, { wch: 10 }, { wch: 12 },
    { wch: 12 }, { wch: 12 }, { wch: 12 }, { wch: 12 }, { wch: 14 },
    { wch: 12 }, { wch: 12 },
  ];

  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, 'Зарплата');

  const currencyData: any[][] = [['Сотрудник', 'Валюта', 'Сумма']];
  employees.forEach(e => {
    Object.entries(e.expensesByCurrency).forEach(([cur, amount]) => {
      if (amount > 0) currencyData.push([e.name, cur, amount]);
    });
  });
  if (currencyData.length > 1) {
    const ws2 = XLSX.utils.aoa_to_sheet(currencyData);
    ws2['!cols'] = [{ wch: 30 }, { wch: 10 }, { wch: 15 }];
    XLSX.utils.book_append_sheet(wb, ws2, 'Расходы по валютам');
  }

  XLSX.writeFile(wb, `payroll_${year}_${String(month).padStart(2, '0')}.xlsx`);
}

export function exportMySalaryToExcel(records: SalaryRecordDto[]) {
  const columns = ['Год', 'Месяц', 'Фикс', 'Почасовая', 'Сдельная', 'Бонус', 'Итого', 'Статус'];
  const rows = records.map(r => [
    r.year, monthName(r.month), r.fixed, r.hourly, r.piece, r.bonus, r.total,
    r.status === 'paid' ? 'Выплачено' : r.status === 'approved' ? 'Утверждено' : 'Черновик',
  ]);
  const ws = XLSX.utils.aoa_to_sheet([columns, ...rows]);
  ws['!cols'] = [{ wch: 8 }, { wch: 12 }, { wch: 12 }, { wch: 12 }, { wch: 12 }, { wch: 12 }, { wch: 14 }, { wch: 14 }];
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, 'Мои расчёты');
  XLSX.writeFile(wb, 'my_salary_history.xlsx');
}

// ═══════════════════════════════════════════════════════
// 📄 ЭКСПОРТ В PDF (с поддержкой кириллицы)
// ═══════════════════════════════════════════════════════

/**
 * Экспорт сводного отчёта по ЗП в PDF
 */
export async function exportPayrollToPdf(
  employees: PayrollExportEmployeeDto[],
  year: number,
  month: number
) {
  const doc = new jsPDF({ orientation: 'landscape', unit: 'mm', format: 'a4' });

  // 🔥 КРИТИЧЕСКИ ВАЖНО: загружаем шрифт с кириллицей
  await ensurePdfFont(doc);

  // Заголовок
  doc.setFont('Roboto', 'bold');
  doc.setFontSize(18);
  doc.setTextColor(40, 40, 40);
  doc.text('Отчёт по зарплате', 14, 18);

  doc.setFont('Roboto', 'normal');
  doc.setFontSize(11);
  doc.setTextColor(100, 100, 100);
  doc.text(`Период: ${monthName(month)} ${year}`, 14, 26);
  doc.text(`Сформирован: ${new Date().toLocaleString('ru-RU')}`, 14, 32);
  doc.text(`Сотрудников: ${employees.length}`, 14, 38);

  // Итоги справа
  const totalSalary = employees.reduce((s, e) => s + e.salaryTotal, 0);
  const totalExpenses = employees.reduce((s, e) => s + e.expensesTotal, 0);
  const totalHours = employees.reduce((s, e) => s + e.totalHours, 0);

  doc.setFontSize(10);
  doc.setTextColor(60, 60, 60);
  const pageWidth = doc.internal.pageSize.getWidth();
  doc.text(`Итого ЗП: ${totalSalary.toLocaleString('ru-RU')} ₽`, pageWidth - 14, 26, { align: 'right' });
  doc.text(`Итого расходов: ${totalExpenses.toLocaleString('ru-RU')} ₽`, pageWidth - 14, 32, { align: 'right' });
  doc.text(`Всего часов: ${totalHours.toFixed(1)}`, pageWidth - 14, 38, { align: 'right' });

  // Таблица
  autoTable(doc, {
    startY: 44,
    head: [[
      'Сотрудник', 'Должность', 'Часы', 'Дней',
      'Фикс', 'Почасовая', 'Сдельная', 'Бонус',
      'Итого ЗП', 'Расходы', 'Ком.'
    ]],
    body: employees.map(e => [
      e.name,
      e.position || '—',
      e.totalHours.toFixed(1),
      e.workDays.toString(),
      formatNum(e.fixed),
      formatNum(e.hourly),
      formatNum(e.piece),
      formatNum(e.bonus),
      formatNum(e.salaryTotal),
      formatNum(e.expensesTotal),
      e.tripsCount.toString(),
    ]),
    foot: [[
      'ИТОГО', '',
      totalHours.toFixed(1),
      employees.reduce((s, e) => s + e.workDays, 0).toString(),
      formatNum(employees.reduce((s, e) => s + e.fixed, 0)),
      formatNum(employees.reduce((s, e) => s + e.hourly, 0)),
      formatNum(employees.reduce((s, e) => s + e.piece, 0)),
      formatNum(employees.reduce((s, e) => s + e.bonus, 0)),
      formatNum(totalSalary),
      formatNum(totalExpenses),
      employees.reduce((s, e) => s + e.tripsCount, 0).toString(),
    ]],
    styles: {
      font: 'Roboto',  // 🔥 Используем кириллический шрифт
      fontSize: 8,
      cellPadding: 2,
    },
    headStyles: {
      fillColor: [79, 70, 229],
      textColor: 255,
      fontStyle: 'bold',
      font: 'Roboto',
    },
    footStyles: {
      fillColor: [241, 245, 249],
      textColor: [15, 23, 42],
      fontStyle: 'bold',
      font: 'Roboto',
    },
    alternateRowStyles: { fillColor: [248, 250, 252] },
    columnStyles: {
      0: { cellWidth: 45 },
      1: { cellWidth: 35 },
      2: { halign: 'right', cellWidth: 16 },
      3: { halign: 'right', cellWidth: 14 },
      4: { halign: 'right', cellWidth: 22 },
      5: { halign: 'right', cellWidth: 22 },
      6: { halign: 'right', cellWidth: 22 },
      7: { halign: 'right', cellWidth: 22 },
      8: { halign: 'right', cellWidth: 25, fontStyle: 'bold' },
      9: { halign: 'right', cellWidth: 22 },
      10: { halign: 'center', cellWidth: 14 },
    },
    margin: { left: 10, right: 10 },
  });

  // Футер на каждой странице
  const pageCount = (doc as any).getNumberOfPages();
  for (let i = 1; i <= pageCount; i++) {
    doc.setPage(i);
    doc.setFont('Roboto', 'normal');
    doc.setFontSize(8);
    doc.setTextColor(150, 150, 150);
    doc.text(
      `Proles Timesheet • Страница ${i} из ${pageCount}`,
      doc.internal.pageSize.getWidth() / 2,
      doc.internal.pageSize.getHeight() - 8,
      { align: 'center' }
    );
  }

  doc.save(`payroll_${year}_${String(month).padStart(2, '0')}.pdf`);
}

/**
 * Экспорт личной истории ЗП в PDF
 */
export async function exportMySalaryToPdf(records: SalaryRecordDto[]) {
  const doc = new jsPDF({ unit: 'mm', format: 'a4' });

  // 🔥 Загружаем шрифт
  await ensurePdfFont(doc);

  doc.setFont('Roboto', 'bold');
  doc.setFontSize(18);
  doc.text('История начислений зарплаты', 14, 18);

  doc.setFont('Roboto', 'normal');
  doc.setFontSize(11);
  doc.setTextColor(100, 100, 100);
  doc.text(`Периодов: ${records.length}`, 14, 26);
  doc.text(`Сформирован: ${new Date().toLocaleString('ru-RU')}`, 14, 32);

  autoTable(doc, {
    startY: 38,
    head: [['Год', 'Месяц', 'Фикс', 'Почасовая', 'Сдельная', 'Бонус', 'Итого', 'Статус']],
    body: records.map(r => [
      r.year.toString(),
      monthName(r.month),
      formatNum(r.fixed),
      formatNum(r.hourly),
      formatNum(r.piece),
      formatNum(r.bonus),
      formatNum(r.total),
      r.status === 'paid' ? '✓ Выплачено' : r.status === 'approved' ? 'Утверждено' : 'Черновик',
    ]),
    styles: { font: 'Roboto', fontSize: 9, cellPadding: 3 },
    headStyles: { fillColor: [79, 70, 229], textColor: 255, font: 'Roboto' },
    alternateRowStyles: { fillColor: [248, 250, 252] },
  });

  doc.save('my_salary_history.pdf');
}

function formatNum(n: number): string {
  return n > 0 ? n.toLocaleString('ru-RU') : '—';
}