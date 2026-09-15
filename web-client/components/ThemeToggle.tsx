import { useTheme } from '../contexts/ThemeContext';

export function ThemeToggle() {
  const { theme, setTheme } = useTheme();

  const cycleTheme = () => {
    const order: Array<'light' | 'dark' | 'system'> = ['light', 'dark', 'system'];
    const next = order[(order.indexOf(theme) + 1) % order.length];
    setTheme(next);
  };

  const icon = {
    light: '☀️',
    dark: '🌙',
    system: '💻',
  }[theme];

  const label = {
    light: 'Светлая',
    dark: 'Тёмная',
    system: 'Системная',
  }[theme];

  return (
    <button
      onClick={cycleTheme}
      title={`Тема: ${label} (клик для смены)`}
      className="btn-ghost w-full justify-start gap-2 text-xs"
    >
      <span className="text-base">{icon}</span>
      <span className="truncate">{label}</span>
    </button>
  );
}