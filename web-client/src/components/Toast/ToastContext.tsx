import { createContext, useContext, useState, useCallback, ReactNode } from 'react';

export type ToastType = 'success' | 'error' | 'info' | 'warning';

export interface Toast {
  id: string;
  message: string;
  type: ToastType;
}

interface ToastContextType {
  addToast: (message: string, type?: ToastType) => void;
  removeToast: (id: string) => void;
}

const ToastContext = createContext<ToastContextType | undefined>(undefined);

export const useToast = () => {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used within a ToastProvider');
  }
  return context;
};

interface ToastProviderProps {
  children: ReactNode;
  duration?: number;
}

export const ToastProvider: React.FC<ToastProviderProps> = ({ 
  children, 
  duration = 5000 
}) => {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const addToast = useCallback((message: string, type: ToastType = 'info') => {
    const id = Math.random().toString(36).substring(2, 9);
    const newToast: Toast = { id, message, type };
    
    setToasts(prev => [...prev, newToast]);

    // Auto-remove after duration
    setTimeout(() => {
      setToasts(prev => prev.filter(t => t.id !== id));
    }, duration);
  }, [duration]);

  const removeToast = useCallback((id: string) => {
    setToasts(prev => prev.filter(t => t.id !== id));
  }, []);

  return (
    <ToastContext.Provider value={{ addToast, removeToast }}>
      {children}
      <div className="fixed top-4 right-4 z-50 flex flex-col gap-2 max-w-md">
        {toasts.map((toast, index) => (
          <ToastItem 
            key={toast.id} 
            toast={toast} 
            onRemove={() => removeToast(toast.id)}
            style={{ transform: `translateY(-${index * 10}px)` }}
          />
        ))}
      </div>
    </ToastContext.Provider>
  );
};

interface ToastItemProps {
  toast: Toast;
  onRemove: () => void;
  style?: React.CSSProperties;
}

const ToastItem: React.FC<ToastItemProps> = ({ toast, onRemove, style }) => {
  const bgColor = {
    success: 'bg-green-500',
    error: 'bg-red-500',
    warning: 'bg-yellow-500',
    info: 'bg-blue-500',
  }[toast.type];

  const icon = {
    success: '✓',
    error: '✕',
    warning: '⚠',
    info: 'ℹ',
  }[toast.type];

  return (
    <div
      className={`${bgColor} text-white px-4 py-3 rounded-lg shadow-lg flex items-center gap-3 min-w-[280px] animate-slide-in-right transition-all duration-300`}
      style={style}
    >
      <span className="text-xl font-bold">{icon}</span>
      <span className="flex-1 text-sm font-medium">{toast.message}</span>
      <button
        onClick={onRemove}
        className="ml-2 hover:bg-white/20 rounded p-1 transition-colors"
      >
        ✕
      </button>
    </div>
  );
};
