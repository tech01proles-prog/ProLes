import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ProtectedRoute } from './components/ProtectedRoute';
import { PermissionGate } from './components/PermissionGate';
import { Layout } from './components/Layout';
import { LoginPage } from './pages/LoginPage';
import { DashboardPage } from './pages/DashboardPage';
import { TimesheetPage } from './pages/TimesheetPage';
import { ProjectsPage } from './pages/ProjectsPage';
import { AnalyticsPage } from './pages/AnalyticsPage';
import { ExpensesPage } from './pages/ExpensesPage';
import { TripsPage } from './pages/TripsPage';
import { VacationsPage } from './pages/VacationsPage';
import { TicketsPage } from './pages/TicketsPage';
import { PayrollPage } from './pages/PayrollPage';
import { NotificationsPage } from './pages/NotificationsPage';
import { NotificationSettingsPage } from './pages/NotificationSettingsPage';
import { AdminPage } from './pages/AdminPage';
import { ProfilePage } from './pages/ProfilePage';
import { HoursCalendarPage } from './pages/HoursCalendarPage';
import { ManagementPage } from './pages/ManagementPage';
import { PositionsPage } from './pages/PositionsPage';
import { ProjectDetailPage } from './pages/ProjectDetailPage';
import { CostCalculationPage } from './pages/CostCalculationPage';
import { EmployeesPage } from './pages/EmployeesPage';
import { EmployeeStatsPage } from './pages/EmployeeStatsPage';

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<ProtectedRoute />}>
          <Route element={<Layout />}>
            <Route path="/" element={<DashboardPage />} />
            <Route element={<PermissionGate permission="timesheet" />}><Route path="/timesheet" element={<TimesheetPage />} /></Route>
            <Route path="/projects" element={<ProjectsPage />} />
            <Route path="/expenses" element={<ExpensesPage />} />
            <Route path="/trips" element={<TripsPage />} />
            <Route path="/vacations" element={<VacationsPage />} />
            <Route path="/tickets" element={<TicketsPage />} />
            <Route path="/payroll" element={<PayrollPage />} />
            <Route path="/analytics" element={<AnalyticsPage />} />
            <Route path="/notifications" element={<NotificationsPage />} />
            <Route path="/notification-settings" element={<NotificationSettingsPage />} />
            <Route path="/admin" element={<AdminPage />} />
            <Route path="/profile" element={<ProfilePage />} />
            <Route element={<PermissionGate permission="timesheet" />}><Route path="/hours-calendar" element={<HoursCalendarPage />} /></Route>
            <Route path="/employees" element={<EmployeesPage />} />
            <Route path="/positions" element={<PositionsPage />} />
            <Route path="/management" element={<ManagementPage />} />
            <Route path="/projects/:projectId" element={<ProjectDetailPage />} />
            <Route path="/cost-calculation" element={<CostCalculationPage />} />
            <Route path="/employee-stats" element={<EmployeeStatsPage />} />
          </Route>
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;