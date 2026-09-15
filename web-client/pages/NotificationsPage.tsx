import { useState, useEffect, useCallback } from 'react';
import api from '../api/client';
import type { NotificationDto } from '../types';
import { formatDateTime } from '../lib/utils';

const TYPE_STYLES: Record<string, { icon: string; color: string }> = {
  VACATION: { icon: '🏖', color: 'bg-blue-50 text-blue-700 border-blue-200' },
  TRIP: { icon: '✈️', color: 'bg-purple-50 text-purple-700 border-purple-200' },
  TRIP_INVITE: { icon: '📢', color: 'bg-indigo-50 text-indigo-700 border-indigo-200' },
  TRIP_UPDATE: { icon: '🔄', color: 'bg-orange-50 text-orange-700 border-orange-200' },
  DAYOFF_WEEKDAY: { icon: '🌞', color: 'bg-yellow-50 text-yellow-700 border-yellow-200' },
  EXPENSE: { icon: '💸', color: 'bg-red-50 text-red-700 border-red-200' },
  TICKET: { icon: '🎫', color: 'bg-green-50 text-green-700 border-green-200' },
  PAYROLL: { icon: '💰', color: 'bg-emerald-50 text-emerald-700 border-emerald-200' },
};

export function NotificationsPage() {
  const [notifications, setNotifications] = useState<NotificationDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<'all' | 'unread'>('all');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await api.get<NotificationDto[]>('/notifications');
      setNotifications(data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const handleMarkRead = async (id: string) => {
    await api.post('/notifications/mark-read', { id });
    setNotifications(prev => prev.map(n => n.id === id ? { ...n, isRead: true } : n));
  };

  const handleMarkAllRead = async () => {
    await api.post('/notifications/mark-all-read');
    setNotifications(prev => prev.map(n => ({ ...n, isRead: true })));
  };

  const filtered = notifications.filter(n => filter === 'all' || !n.isRead);
  const unreadCount = notifications.filter(n => !n.isRead).length;

  return (
    <div className="space-y-6 max-w-4xl mx-auto">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">🔔 Уведомления</h1>
          <p className="text-sm text-slate-500 mt-1">
            {notifications.length} всего • {unreadCount > 0 ? <span className="font-bold text-red-600">{unreadCount} непрочитанных</span> : 'все прочитаны'}
          </p>
        </div>
        {unreadCount > 0 && (
          <button onClick={handleMarkAllRead} className="btn-outline px-4 py-2 text-sm">
            ✓ Отметить все как прочитанные
          </button>
        )}
      </div>

      <div className="flex gap-2">
        <button onClick={() => setFilter('all')} className={`px-3 py-1.5 rounded-lg text-xs font-semibold ${filter === 'all' ? 'bg-slate-900 text-white' : 'bg-white text-slate-600 border border-slate-200'}`}>
          Все ({notifications.length})
        </button>
        <button onClick={() => setFilter('unread')} className={`px-3 py-1.5 rounded-lg text-xs font-semibold ${filter === 'unread' ? 'bg-slate-900 text-white' : 'bg-white text-slate-600 border border-slate-200'}`}>
          Непрочитанные ({unreadCount})
        </button>
      </div>

      {loading ? (
        <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>
      ) : filtered.length === 0 ? (
        <div className="text-center py-20 card border-dashed border-2 border-slate-200 bg-slate-50/50">
          <div className="text-5xl mb-4 opacity-50">🔕</div>
          <h3 className="text-lg font-semibold text-slate-900">Нет уведомлений</h3>
        </div>
      ) : (
        <div className="space-y-2">
          {filtered.map(notif => {
            const style = TYPE_STYLES[notif.type] || { icon: '📌', color: 'bg-slate-50 text-slate-700 border-slate-200' };
            return (
              <div
                key={notif.id}
                className={`card p-4 flex items-start gap-3 transition-all animate-fade-in cursor-pointer ${!notif.isRead ? 'border-l-4 border-l-indigo-500 bg-indigo-50/30' : ''}`}
                onClick={() => !notif.isRead && handleMarkRead(notif.id)}
              >
                <div className={`w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 border ${style.color}`}>
                  <span className="text-lg">{style.icon}</span>
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 flex-wrap mb-1">
                    <div className="font-bold text-slate-900 text-sm">{notif.title}</div>
                    {!notif.isRead && <span className="w-2 h-2 rounded-full bg-indigo-500"></span>}
                  </div>
                  <div className="text-sm text-slate-700 whitespace-pre-line">{notif.message}</div>
                  <div className="flex items-center gap-3 mt-2 text-xs text-slate-400">
                    <span>👤 {notif.senderName}</span>
                    <span>•</span>
                    <span>{formatDateTime(notif.createdAt)}</span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}