import type { TnpaDocumentDto } from '../../types';
import {
  EmptyState,
  StatusBadge,
} from '../ui';

interface TnpaDocumentListProps {
  items: TnpaDocumentDto[];
  canManage?: boolean;
  busyId?: string | null;
  onUpload?: () => void;
  onDownload: (document: TnpaDocumentDto) => void;
  onDelete?: (document: TnpaDocumentDto) => void;
}

function formatFileSize(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 Б';

  const units = ['Б', 'КБ', 'МБ', 'ГБ'];
  const index = Math.min(
    Math.floor(Math.log(bytes) / Math.log(1024)),
    units.length - 1,
  );
  const value = bytes / 1024 ** index;

  return `${new Intl.NumberFormat('ru-RU', {
    maximumFractionDigits: index === 0 ? 0 : 1,
  }).format(value)} ${units[index]}`;
}

function formatTimestamp(value: number) {
  return new Intl.DateTimeFormat('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

export function TnpaDocumentList({
  items,
  canManage = false,
  busyId = null,
  onUpload,
  onDownload,
  onDelete,
}: TnpaDocumentListProps) {
  if (items.length === 0) {
    return (
      <EmptyState
        compact
        title="Документов ТНПА пока нет"
        description="Загрузите нормативный или технический документ проекта."
        action={
          canManage && onUpload ? (
            <button
              type="button"
              onClick={onUpload}
              className="rounded-xl bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white hover:bg-indigo-700"
            >
              Загрузить документ
            </button>
          ) : undefined
        }
      />
    );
  }

  return (
    <div className="divide-y divide-slate-200 dark:divide-slate-800">
      {items.map((document) => {
        const busy = busyId === document.id;

        return (
          <article
            key={document.id}
            className="flex flex-col gap-4 py-4 first:pt-0 last:pb-0 lg:flex-row lg:items-center"
          >
            <div className="flex min-w-0 flex-1 items-start gap-3">
              <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-indigo-50 text-sm font-black text-indigo-600 dark:bg-indigo-950/40 dark:text-indigo-300">
                DOC
              </div>

              <div className="min-w-0 flex-1">
                <h3 className="truncate text-sm font-bold text-slate-950 dark:text-white">
                  {document.originalName}
                </h3>

                {document.description && (
                  <p className="mt-1 line-clamp-2 text-sm text-slate-500 dark:text-slate-400">
                    {document.description}
                  </p>
                )}

                <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-slate-400">
                  <span>{formatFileSize(document.sizeBytes)}</span>
                  <span>•</span>
                  <span>{formatTimestamp(document.uploadedAt)}</span>
                  {document.uploaderName && (
                    <>
                      <span>•</span>
                      <span>{document.uploaderName}</span>
                    </>
                  )}
                </div>

                <div className="mt-2">
                  <StatusBadge
                    tone={document.subprojectId ? 'violet' : 'neutral'}
                  >
                    {document.subprojectName ||
                      'Общий документ проекта'}
                  </StatusBadge>
                </div>
              </div>
            </div>

            <div className="flex shrink-0 gap-2">
              <button
                type="button"
                onClick={() => onDownload(document)}
                disabled={busy}
                className="rounded-xl border border-slate-200 px-3 py-2 text-xs font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
              >
                {busy ? 'Подождите…' : 'Скачать'}
              </button>

              {canManage && onDelete && (
                <button
                  type="button"
                  onClick={() => onDelete(document)}
                  disabled={busy}
                  className="rounded-xl bg-rose-50 px-3 py-2 text-xs font-semibold text-rose-600 hover:bg-rose-100 disabled:opacity-50 dark:bg-rose-950/30 dark:text-rose-400"
                >
                  Удалить
                </button>
              )}
            </div>
          </article>
        );
      })}
    </div>
  );
}