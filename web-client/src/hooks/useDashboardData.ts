import { useState, useEffect, useCallback } from 'react';
import api from '../api/client';
import type { TimeEntryDto, ProjectDto, ExpenseDto, NotificationDto } from '../types';

interface DashboardData {
  todayHours: number;
  todayEntries: TimeEntryDto[];
  activeProjectsCount: number;
  totalProjects: number;
  todayExpenses: number;
  unreadNotifications: number;
  loading: boolean;
  error: string | null;
}

export function useDashboardData() {
  const [data, setData] = useState<DashboardData>({
    todayHours: 0,
    todayEntries: [],
    activeProjectsCount: 0,
    totalProjects: 0,
    todayExpenses: 0,
    unreadNotifications: 0,
    loading: true,
    error: null,
  });

  const load = useCallback(async () => {
    try {
      const userStr = localStorage.getItem('proles_user');
      if (!userStr) throw new Error('No user session');
      const user = JSON.parse(userStr);
      const today = new Date().toISOString().slice(0, 10);

      const [entriesRes, projectsRes, expensesRes, notifRes] = await Promise.allSettled([
        api.get<TimeEntryDto[]>('/entries', { params: { userId: user.id } }),
        api.get<ProjectDto[]>('/projects'),
        api.get<ExpenseDto[]>('/expenses', { params: { userId: user.id } }),
        api.get<NotificationDto[]>('/notifications'),
      ]);

      const entries = entriesRes.status === 'fulfilled' ? entriesRes.value.data : [];
      const todayEntries = entries.filter((e) => e.date === today);
      const todayHours = todayEntries.reduce((sum, e) => sum + e.hours, 0);

      const projects = projectsRes.status === 'fulfilled' ? projectsRes.value.data : [];
      const activeProjectsCount = projects.filter((p) => p.isActive).length;

      const expenses = expensesRes.status === 'fulfilled' ? expensesRes.value.data : [];
      const todayExpensesTotal = expenses
        .filter((e) => e.date === today)
        .reduce((sum, e) => sum + e.amount, 0);

      const notifications = notifRes.status === 'fulfilled' ? notifRes.value.data : [];
      const unreadNotifications = notifications.filter((n) => !n.isRead).length;

      setData({
        todayHours,
        todayEntries,
        activeProjectsCount,
        totalProjects: projects.length,
        todayExpenses: todayExpensesTotal,
        unreadNotifications,
        loading: false,
        error: null,
      });
    } catch (err: any) {
      setData((prev) => ({ ...prev, loading: false, error: err.message || 'Ошибка загрузки' }));
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  return { ...data, refresh: load };
}