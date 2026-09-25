import { Link, useLocation } from 'react-router-dom';
import { getPageMeta } from '../config/navigation';

export function PageHeader() {
  const { pathname } = useLocation();
  const meta = getPageMeta(pathname);

  return (
    <header className="mb-6 md:mb-8">
      <div className="mb-2 flex items-center gap-2 text-xs font-medium text-slate-400">
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