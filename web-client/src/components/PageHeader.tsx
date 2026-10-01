import { Link, useLocation, useNavigate } from 'react-router-dom';
import { getPageMeta } from '../config/navigation';

export function PageHeader() {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const meta = getPageMeta(pathname);
  const canGoBack = pathname !== '/' && window.history.length > 1;

  return (
    <header className="mb-6 md:mb-8">
      <div className="mb-2 flex items-center gap-2 text-xs font-medium text-slate-400">
        {canGoBack && (
          <button
            type="button"
            onClick={() => navigate(-1)}
            className="mr-1 inline-flex items-center gap-1 rounded-lg px-2 py-1 font-bold text-slate-500 transition-colors hover:bg-slate-100 hover:text-indigo-600 dark:hover:bg-slate-800 dark:hover:text-indigo-400"
            aria-label="Назад"
            title="Вернуться на предыдущую страницу"
          >
            ← Назад
          </button>
        )}
        <Link
          to="/"
          className="transition-colors hover:text-indigo-600 dark:hover:text-indigo-400"
        >
          ProLes
        </Link>

        {meta.parent && (
          <>
            <span aria-hidden="true">/</span>
            <span>{meta.parent}</span>
          </>
        )}

        {pathname !== '/' && (
          <>
            <span aria-hidden="true">/</span>
            <span className="text-slate-600 dark:text-slate-300">
              {meta.title}
            </span>
          </>
        )}
      </div>

      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-bold tracking-tight text-slate-950 dark:text-white md:text-3xl">
          {meta.title}
        </h1>
        <p className="text-sm text-slate-500 dark:text-slate-400">
          {meta.description}
        </p>
      </div>
    </header>
  );
}