export type NavigationAction = 'create' | 'view';

export interface NavigationItem {
  to: string;
  label: string;
  shortLabel?: string;
  description: string;
  icon: string;
  permission?: string;
  action?: NavigationAction;
  section: 'workspace' | 'management';
}

export const NAVIGATION_ITEMS: NavigationItem[] = [
  {
    to: '/',
    label: 'Обзор',
    description: 'Главная панель и рабочая сводка',
    icon: '⌂',
    section: 'workspace',
  },
  {
    to: '/timesheet',
    label: 'Рабочее время',
    shortLabel: 'Табель',
    description: 'Табель и учёт рабочего времени',
    icon: '◷',
    permission: 'timesheet',
    action: 'view',
    section: 'workspace',
  },
  {
    to: '/trips',
    label: 'Командировки',
    description: 'Поездки, суточные и документы',
    icon: '✈',
    section: 'workspace',
  },
  {
    to: '/expenses',
    label: 'Финансы',
    description: 'Расходы и доходы проектов',
    icon: '₽',
    section: 'workspace',
  },
  {
    to: '/tickets',
    label: 'Билеты',
    description: 'Билеты и сопроводительные файлы',
    icon: '▣',
    permission: 'tickets',
    action: 'view',
    section: 'workspace',
  },
  {
    to: '/vacations',
    label: 'Отпуска',
    description: 'Отпуска и отсутствия',
    icon: '☼',
    section: 'workspace',
  },
  {
    to: '/notifications',
    label: 'Уведомления',
    description: 'События и сообщения системы',
    icon: '◉',
    permission: 'notifications',
    action: 'view',
    section: 'workspace',
  },
  {
    to: '/management',
    label: 'Управление',
    description: 'Проекты, сотрудники и настройки',
    icon: '◇',
    section: 'management',
  },
];

export interface PageMeta {
  title: string;
  description: string;
  parent?: string;
}

const PAGE_META: Record<string, PageMeta> = {
  '/': {
    title: 'Рабочий стол',
    description: 'Ключевые показатели и быстрые действия',
  },
  '/timesheet': {
    title: 'Рабочее время',
    description: 'Учёт часов по проектам и задачам',
  },
  '/hours-calendar': {
    title: 'Календарь часов',
    description: 'Рабочее время в календарном представлении',
    parent: 'Рабочее время',
  },
  '/trips': {
    title: 'Командировки',
    description: 'Управление поездками и суточными',
  },
  '/expenses': {
    title: 'Финансы',
    description: 'Расходы и доходы по проектам',
  },
  '/tickets': {
    title: 'Билеты',
    description: 'Документы и транспортные расходы',
  },
  '/vacations': {
    title: 'Отпуска',
    description: 'Заявки на отпуск и история отсутствий',
  },
  '/notifications': {
    title: 'Уведомления',
    description: 'Системные события и сообщения',
  },
  '/notification-settings': {
    title: 'Настройки уведомлений',
    description: 'Каналы и правила доставки сообщений',
    parent: 'Уведомления',
  },
  '/management': {
    title: 'Управление',
    description: 'Проекты, сотрудники, должности и доступы',
  },
  '/projects': {
    title: 'Проекты',
    description: 'Активные проекты и подпроекты',
    parent: 'Управление',
  },
  '/employees': {
    title: 'Сотрудники',
    description: 'Команда и данные сотрудников',
    parent: 'Управление',
  },
  '/positions': {
    title: 'Должности',
    description: 'Организационная структура',
    parent: 'Управление',
  },
  '/payroll': {
    title: 'Расчёт зарплаты',
    description: 'Начисления, удержания и выплаты',
    parent: 'Управление',
  },
  '/analytics': {
    title: 'Аналитика',
    description: 'Показатели работы компании',
    parent: 'Управление',
  },
  '/cost-calculation': {
    title: 'Себестоимость',
    description: 'Расчёт затрат и рентабельности',
    parent: 'Управление',
  },
  '/employee-stats': {
    title: 'Статистика сотрудников',
    description: 'Показатели рабочего времени команды',
    parent: 'Управление',
  },
  '/admin': {
    title: 'Права доступа',
    description: 'Роли и разрешения пользователей',
    parent: 'Управление',
  },
  '/profile': {
    title: 'Профиль',
    description: 'Личные данные и настройки аккаунта',
  },
};

export function getPageMeta(pathname: string): PageMeta {
  if (pathname.startsWith('/projects/')) {
    return {
      title: 'Карточка проекта',
      description: 'Данные, участники и документы проекта',
      parent: 'Проекты',
    };
  }

  return PAGE_META[pathname] ?? {
    title: 'ProLes',
    description: 'Корпоративная информационная система',
  };
}