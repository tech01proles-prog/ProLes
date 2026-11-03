export function generateUUID(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function toISODateLocal(date: Date = new Date()): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function formatDate(date: string): string {
  const value = String(date ?? '').trim();
  if (!value) return '—';

  // API dates are ISO YYYY-MM-DD. Read the calendar parts directly to avoid
  // timezone offsets shifting a displayed date to the previous/next day.
  const yearFirstMatch = value.match(/^(\d{4})[./-](\d{2})[./-](\d{2})/);
  if (yearFirstMatch) {
    return `${yearFirstMatch[3]}.${yearFirstMatch[2]}.${yearFirstMatch[1]}`;
  }

  const dayFirstMatch = value.match(/^(\d{2})[./-](\d{2})[./-](\d{4})/);
  if (dayFirstMatch) {
    return `${dayFirstMatch[1]}.${dayFirstMatch[2]}.${dayFirstMatch[3]}`;
  }

  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return `${String(parsed.getDate()).padStart(2, '0')}.${String(parsed.getMonth() + 1).padStart(2, '0')}.${parsed.getFullYear()}`;
}

export function formatDateTimeExact(timestamp: number): string {
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) return '—';
  const datePart = formatDate(toISODateLocal(date));
  const timePart = `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  return `${datePart} ${timePart}`;
}

export function formatDateTime(timestamp: number): string {
  const d = new Date(timestamp);
  if (Number.isNaN(d.getTime())) return '—';
  const now = new Date();
  const diffMs = now.getTime() - d.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);
  const diffDays = Math.floor(diffMs / 86400000);

  if (diffMins < 1) return 'только что';
  if (diffMins < 60) return `${diffMins} мин назад`;
  if (diffHours < 24) return `${diffHours} ч назад`;
  if (diffDays < 7) return `${diffDays} дн назад`;
  return formatDateTimeExact(timestamp);
}

export function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.readAsDataURL(file);
    reader.onload = () => {
      const result = reader.result as string;
      const base64 = result.split(',')[1];
      resolve(base64);
    };
    reader.onerror = reject;
  });
}

export function formatMoney(amount: number, currency: string = 'RUB'): string {
  if (amount === 0) return '—';
  const symbols: Record<string, string> = { RUB: '₽', USD: '$', EUR: '€', BYN: 'Br' };
  return `${amount.toLocaleString('ru-RU')} ${symbols[currency] || currency}`;
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} Б`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} КБ`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} МБ`;
}

export function daysBetween(start: string, end: string): number {
  const s = new Date(start);
  const e = new Date(end);
  return Math.floor((e.getTime() - s.getTime()) / (1000 * 60 * 60 * 24)) + 1;
}

export function monthName(month: number): string {
  const names = ['Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь',
    'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь'];
  return names[month - 1] || '';
}

export function dayOfWeekRu(dateStr: string): string {
  const d = new Date(dateStr);
  const names = ['Воскресенье', 'Понедельник', 'Вторник', 'Среда', 'Четверг', 'Пятница', 'Суббота'];
  return names[d.getDay()];
}

export function isWeekend(dateStr: string): boolean {
  const d = new Date(dateStr);
  return d.getDay() === 0 || d.getDay() === 6;
}

export async function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string;
      const base64 = result.split(',')[1];
      resolve(base64);
    };
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}