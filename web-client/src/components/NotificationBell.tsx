import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import api from '../api/client';
import type { NotificationDto } from '../types';
import { formatDateTime } from '../lib/utils';

export function NotificationBell() {
  const navigate = useNavigate();
  const [notifications, setNotifications] = useState<NotificationDto[]>([]);
  const [showDropdown, setShowDropdown] = useState(false);

  useEffect(() => {
    loadNotifications();
    const interval = setInterval(loadNotifications, 30000); // Polling каждые 30 сек
    return () => clearInterval(interval);
  }, []);

  const loadNotifications = async () => {
    try {
      const response = await api.get('/notifications');
      // Обрабатываем разные возможные форматы ответа
      const data = Array.isArray(response.data)
        ? response.data
        : (response.data?.notifications || response.data?.items || []);
      setNotifications(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to load notifications', err);
      setNotifications([]); // Гарантируем массив даже при ошибке
    }
  };

  const unreadCount = Array.isArray(notifications) ? notifications.filter(n => !n.isRead).length : 0;
  const recent = Array.isArray(notifications) ? notifications.slice(0, 5) : [];

  const handleMarkRead = async (id: string) => {
    await api.post('/notifications/mark-read', { id });
    setNotifications(prev => prev.map(n => n.id === id ? { ...n, isRead: true } : n));
  };

  const handleMarkAllRead = async () => {
    await api.post('/notifications/mark-all-read');
    setNotifications(prev => prev.map(n => ({ ...n, isRead: true })));
  };

  return (
    <div className="relative">
      <button
        onClick={() => setShowDropdown(!showDropdown)}
        className="relative p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
      >
        <span className="text-xl">🔔</span>
        {unreadCount > 0 && (
          <span className="absolute top-0 right-0 min-w-[18px] h-[18px] px-1 rounded-full bg-red-500 text-white text-[10px] font-bold flex items-center justify-center">
            {unreadCount > 99 ? '99+' : unreadCount}
          </span>
        )}
      </button>

      {showDropdown && (
        <>
          <div className="fixed inset-0 z-40" onClick={() => setShowDropdown(false)} />
          <div className="absolute right-0 top-full mt-2 w-80 md:w-96 bg-white dark:bg-slate-900 rounded-xl shadow-xl border border-slate-200 dark:border-slate-700 z-50 max-h-[500px] overflow-hidden flex flex-col">
            <div className="p-4 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between">
              <h3 className="font-bold text-slate-900 dark:text-slate-100">Уведомления</h3>
              {unreadCount > 0 && (
                <button onClick={handleMarkAllRead} className="text-xs text-indigo-600 dark:text-indigo-400 hover:underline">
                  Отметить все
                </button>
              )}
            </div>
            <div className="overflow-y-auto flex-1">
              {recent.length === 0 ? (
                <div className="p-8 text-center text-slate-400 dark:text-slate-500 text-sm">
                  Нет уведомлений
                </div>
              ) : (
                recent.map(notif => (
                  <div
                    key={notif.id}
                    onClick={() => !notif.isRead && handleMarkRead(notif.id)}
                    className={`p-3 border-b border-slate-100 dark:border-slate-800 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors ${
                      !notif.isRead ? 'bg-indigo-50/50 dark:bg-indigo-950/20' : ''
                    }`}
                  >
                    <div className="flex items-start gap-2">
                      <div className="text-lg flex-shrink-0">
                        {notif.type === 'VACATION' ? '🏖' :
                         notif.type === 'TRIP' ? '✈️' :
                         notif.type === 'DAYOFF_WEEKDAY' ? '🌞' :
                         notif.type === 'EXPENSE' ? '💸' :
                         notif.type === 'TICKET' ? '🎫' : '📌'}
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="font-medium text-sm text-slate-900 dark:text-slate-100 truncate">
                          {notif.title}
                        </div>
                        <div className="text-xs text-slate-600 dark:text-slate-400 mt-0.5 line-clamp-2">
                          {notif.message}
                        </div>
                        <div className="text-[10px] text-slate-400 dark:text-slate-500 mt-1">
                          {formatDateTime(notif.createdAt)}
                        </div>
                      </div>
                      {!notif.isRead && (
                        <div className="w-2 h-2 rounded-full bg-indigo-500 flex-shrink-0 mt-2" />
                      )}
                    </div>
                  </div>
                ))
              )}
            </div>
            <div className="p-3 border-t border-slate-200 dark:border-slate-700">
              <button
                onClick={() => { setShowDropdown(false); navigate('/notifications'); }}
                className="w-full text-center text-sm text-indigo-600 dark:text-indigo-400 font-medium hover:underline"
              >
                Все уведомления →
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}