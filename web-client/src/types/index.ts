export interface UserDto {
  id: string;
  lastName: string;
  firstName: string;
  middleName: string;
  name: string;
  login: string;
  email: string;
  role: string;
  position: string;
  defaultRateType: string;
  defaultRate: number;
  defaultCurrency: string;
  phone: string;
  telegramUsername: string;
  birthDate: string | null;
  positionId: string | null;
  onVacation?: boolean;
  isRemote: boolean;
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
  subprojectId: string | null;
  subprojectName: string;
  projectName: string;
  date: string;
  hours: number;
  country: string;
  comment: string;
  synced: boolean;
}

export interface SubprojectDto {
  id: string;
  projectId: string;
  name: string;
  code: string;
  description: string;
  isActive: boolean;
  sortOrder: number;
  createdAt: number;
  updatedAt: number;
  archivedAt: number | null;
}

export interface CreateSubprojectRequest {
  name: string;
  code: string;
  description: string;
  sortOrder: number;
}

export interface UpdateSubprojectRequest {
  name?: string;
  code?: string;
  description?: string;
  isActive?: boolean;
  sortOrder?: number;
}

export interface TnpaDocumentDto {
  id: string;
  projectId: string;
  projectName: string;
  subprojectId: string | null;
  subprojectName: string;
  originalName: string;
  mimeType: string;
  sizeBytes: number;
  checksumSha256: string;
  description: string;
  uploadedBy: string;
  uploaderName: string;
  uploadedAt: number;
  downloadUrl: string;
  deletedAt: number | null;
}

export interface ProjectDto {
  id: string;
  name: string;
  isActive: boolean;
  status: string;
  lead: string;
  revenue: number;
  expenses: number;
  cost: number;
  profit: number;
  projectNumber: string;
  subProjectNumber: string;
  client: string;
  location: string;
  productService: string;
  quantity: number;
  deliveryDate: string | null;
  contract: string;
  notes: string;
  projectCode: string;
  completionDate: string | null;
  customer: string;
  productionCost: number;
  transportToClient: number;
  sellingPrice: number;
  materials: number;
  contractors: number;
  creditPercent: number;
  subprojects: SubprojectDto[];
}

export interface ExpenseDto {
  id: string;
  userId: string;
  projectId: string | null;
  subprojectId: string | null;
  subprojectName: string;
  projectName: string;
  date: string;
  type: string;
  name: string;
  amount: number;
  currency: string;
  comment: string;
  receiptSubmitted: boolean;
  hasReceiptPhoto: boolean;
  category: string;
  subcategory: string | null;
  receiptCount: number;
  expenseScope: string;
  creatorRole: string;
  createdAt: number;
}

export interface WaypointDto {
  order: number;
  country?: string;
  city: string;
  address: string;
}

export interface BusinessTripDto {
  id: string;
  userId: string;
  projectId: string | null;
  projectName: string;
  projectNumber: string;
  companyName: string;
  country: string;
  type: 'DEPARTURE' | 'TRANSFER' | 'COMPLETION';
  date: string;
  completedDate?: string | null;
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
  penalty?: number;
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
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  approvedBy: string | null;
  approvedAt: number | null;
  rejectionReason: string;
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
  hasReceipt: boolean;  // 🆕 Флаг наличия чека
  recipients: TicketRecipientDto[];
}

// ═══════════════════════════════════════════════════════
// 💵 INCOMES
// ═══════════════════════════════════════════════════════
export interface IncomeDto {
  id: string;
  userId: string;
  projectId: string | null;
  subprojectId: string | null;
  subprojectName: string;
  projectName: string;
  date: string;
  type: string;
  name: string;
  amount: number;
  currency: string;
  category: string;
  subcategory: string | null;
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
  tripVisibleToAll: boolean;
  tripTelegramBroadcast: boolean;
  tripChangeEnabled: boolean;
  vacationDecisionEnabled: boolean;
  expenseCreatedEnabled: boolean;
  ticketReceiptEnabled: boolean;
  telegramEnabled: boolean;
  telegramLinked: boolean;
  telegramLinkCode: string | null;
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
  email?: string;
  phone?: string;
  telegramUsername?: string;
  birthDate?: string | null;
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

export interface PositionDto {
  id: string;
  name: string;
  parentId: string | null;
  parentName: string | null;
  isActive: boolean;
  sortOrder: number;
}
