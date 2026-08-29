export interface UserDto {
  id: string;
  lastName: string;
  firstName: string;
  middleName: string;
  name: string;
  login: string;
  role: string;
  position: string;
  defaultRateType: string;
  defaultRate: number;
  defaultCurrency: string;
  newPassword?: string;
}

export interface AuthResponse {
  token: string;
  expiresAt: number;
  user: UserDto;
}

export interface LoginRequest {
  email: string;
  password: string;
  rememberMe: boolean;
}

export interface TimeEntryDto {
  id: string;
  userId: string;
  projectId: string;
  projectName: string;
  date: string;
  hours: number;
  country: string;
  comment: string;
  synced: boolean;
}

export interface ProjectDto {
  id: string;
  name: string;
  isActive: boolean;
  status: string;
  client: string;
  location: string;
  lead: string;
  revenue: number;
  expenses: number;
  profit: number;
  projectNumber: string;
  subProjectNumber: string;
  productService: string;
  quantity: number;
  deliveryDate: string | null;
  contract: string;
  notes: string;
  productionCost: number;      // 🆕
  transportToClient: number;   // 🆕
  sellingPrice: number;        // 🆕
  materials?: number;          // 🆕 Материалы (ручной ввод)
  contractors?: number;        // 🆕 Услуги подрядчиков (ручной ввод)
  creditPercent?: number;      // 🆕 % по кредиту (ручной ввод)
}

export interface ExpenseDto {
  id: string;
  userId: string;
  projectId: string;
  projectName: string;
  date: string;
  type: string;
  name: string;
  amount: number;
  currency: string;
  comment: string;
  receiptSubmitted: boolean;
  hasReceiptPhoto: boolean;
}

export interface WaypointDto {
  order: number;
  city: string;
  address: string;
}

export interface BusinessTripDto {
  id: string;
  userId: string;
  projectId: string;
  projectName: string;
  type: 'DEPARTURE' | 'TRANSFER' | 'COMPLETION';
  date: string;
  city: string;
  waypoints: WaypointDto[];
  participants: string[];
  transport: string;
  notes: string;
  createdAt: number;
  perDiemRate: number;  // 🆕 Размер суточных
}

export interface NotificationDto {
  id: string;
  targetUserId: string | null;
  senderUserId: string;
  senderName: string;
  type: string;
  title: string;
  message: string;
  payload: string;
  isRead: boolean;
  createdAt: number;
}

export interface RoleDto {
  id: string;
  name: string;
  displayName: string;
  description: string;
  permissions: RolePermissionDto[];
}

export interface RolePermissionDto {
  permission: string;
  canView: boolean;
  canCreate: boolean;
  canEdit: boolean;
  canDelete: boolean;
}

// ═══════════════════════════════════════════════════════
// 💰 PAYROLL
// ═══════════════════════════════════════════════════════
export interface SalaryComponentDto {
  id: string;
  userId: string;
  type: 'FIXED' | 'HOURLY' | 'PIECE' | 'BONUS' | 'PENALTY' | 'MARGIN_PERCENT';
  amount: number;
  projectId: string | null;
  ratePerHour: number | null;
  ratePerUnit: number | null;
  description: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  isActive: boolean;
}

export interface SalaryBreakdownResponse {
  id: string;
  fixed: number;
  piece: number;
  hourly: number;
  bonus: number;
  total: number;
}

export interface SalaryRecordDto {
  id: string;
  userId: string;
  userName: string;
  year: number;
  month: number;
  fixed: number;
  piece: number;
  hourly: number;
  bonus: number;
  total: number;
  status: 'draft' | 'approved' | 'paid';
}

export interface PayrollExportEmployeeDto {
  userId: string;
  name: string;
  position: string;
  role: string;
  totalHours: number;
  workDays: number;
  fixed: number;
  piece: number;
  hourly: number;
  bonus: number;
  salaryTotal: number;
  expensesTotal: number;
  expensesByCurrency: Record<string, number>;
  tripsCount: number;
}

export interface PayrollExportResponse {
  year: number;
  month: number;
  generatedAt: number;
  employees: PayrollExportEmployeeDto[];
}

// ═══════════════════════════════════════════════════════
// 🏖 VACATIONS
// ═══════════════════════════════════════════════════════
export interface VacationDto {
  id: string;
  userId: string;
  start: string;
  end: string;
}

// ═══════════════════════════════════════════════════════
// 🌞 DAY-OFFS
// ═══════════════════════════════════════════════════════
export interface DayOffDto {
  user_id: string;
  date: string;
}

// ═══════════════════════════════════════════════════════
// 🎫 TICKETS
// ═══════════════════════════════════════════════════════
export interface TicketRecipientDto {
  userId: string;
  userName: string;
}

export interface TicketDto {
  id: string;
  projectId: string;
  projectName: string;
  fileName: string;
  fileType: string;
  fileSize: number;
  description: string;
  uploadedAt: number;
  viewedAt: number | null;
  downloadUrl: string;
  sendToAccountant: boolean;
  accountantEmail: string;
  amount: number;
  currency: string;
  recipients: TicketRecipientDto[];
}

// ═══════════════════════════════════════════════════════
// 💵 INCOMES
// ═══════════════════════════════════════════════════════
export interface IncomeDto {
  id: string;
  userId: string;              // 🆕
  projectId: string;
  projectName: string;
  date: string;
  type: string;
  name: string;
  amount: number;
  currency: string;
  comment: string;
  createdAt: number;
}

// ═══════════════════════════════════════════════════════
// 🔔 NOTIFICATION PREFERENCES
// ═══════════════════════════════════════════════════════
export interface NotificationPreferencesDto {
  userId: string;
  tripEnabled: boolean;
  vacationEnabled: boolean;
  dayoffEnabled: boolean;
  expenseEnabled: boolean;
  payrollEnabled: boolean;
  ticketEnabled: boolean;
  telegramEnabled: boolean;
  emailEnabled: boolean;
  email: string;
}

// ═══════════════════════════════════════════════════════
// 👤 PROFILE UPDATE
// ═══════════════════════════════════════════════════════
export interface ProfileUpdateDto {
  firstName?: string;
  lastName?: string;
  middleName?: string;
  position?: string;
  defaultRateType?: string;
  defaultRate?: number;
  defaultCurrency?: string;
  newPassword?: string;
}

// ═══════════════════════════════════════════════════════
// 🔐 PERMISSIONS (RBAC)
// ═══════════════════════════════════════════════════════
export interface PermissionActions {
  canView: boolean;
  canCreate: boolean;
  canEdit: boolean;
  canDelete: boolean;
}

/** Карта прав: permissionKey → действия */
export type UserPermissions = Record<string, PermissionActions>;

// ═══════════════════════════════════════════════════════
// 📊 ANALYTICS — Календарь часов сотрудников
// ═══════════════════════════════════════════════════════
export interface EmployeeHoursEntry {
  userId: string;
  userName: string;
  projectId: string;
  projectName: string;
  hours: number;
  date: string;
}

export interface UserEffectivePermissionDto {
  permission: string;
  canView: boolean;
  canCreate: boolean;
  canEdit: boolean;
  canDelete: boolean;
  isOverride: boolean;
}