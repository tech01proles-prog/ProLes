import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from 'react';
import type { SubprojectDto } from '../../types';

export interface TnpaUploadValue {
  file: File;
  subprojectId: string;
  description: string;
}

interface TnpaUploadFormProps {
  subprojects: SubprojectDto[];
  submitting?: boolean;
  onCancel: () => void;
  onSubmit: (
    value: TnpaUploadValue,
  ) => void | Promise<void>;
}

const MAX_FILE_SIZE = 1024 * 1024 * 1024;

const FIELD_CLASS_NAME = [
  'mt-1.5 w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5',
  'text-sm text-slate-950 outline-none transition',
  'focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/10',
  'dark:border-slate-700 dark:bg-slate-950 dark:text-white',
].join(' ');

export function TnpaUploadForm({
  subprojects,
  submitting = false,
  onCancel,
  onSubmit,
}: TnpaUploadFormProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [subprojectId, setSubprojectId] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    setFile(null);
    setSubprojectId('');
    setDescription('');
    setError('');

    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  }, []);

  const handleSubmit = async (
    event: FormEvent<HTMLFormElement>,
  ) => {
    event.preventDefault();

    if (!file) {
      setError('Выберите файл.');
      return;
    }

    if (file.size <= 0) {
      setError('Нельзя загрузить пустой файл.');
      return;
    }

    if (file.size > MAX_FILE_SIZE) {
      setError('Размер файла превышает 1 ГБ.');
      return;
    }

    setError('');

    await onSubmit({
      file,
      subprojectId,
      description: description.trim(),
    });
  };

  return (
    <form onSubmit={handleSubmit}>
      <div className="space-y-5">
        <label>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Файл
          </span>
          <input
            ref={fileInputRef}
            type="file"
            onChange={(event) =>
              setFile(event.target.files?.[0] ?? null)
            }
            className={`${FIELD_CLASS_NAME} file:mr-3 file:rounded-lg file:border-0 file:bg-indigo-50 file:px-3 file:py-1.5 file:text-xs file:font-semibold file:text-indigo-700 dark:file:bg-indigo-950/50 dark:file:text-indigo-300`}
            disabled={submitting}
          />
          <span className="mt-1.5 block text-xs text-slate-400">
            Максимальный размер — 1 ГБ.
          </span>
        </label>

        <label>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Подпроект
          </span>
          <select
            value={subprojectId}
            onChange={(event) =>
              setSubprojectId(event.target.value)
            }
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          >
            <option value="">Общий документ проекта</option>
            {subprojects
              .filter((item) => item.isActive)
              .map((item) => (
                <option key={item.id} value={item.id}>
                  {item.code
                    ? `${item.code} — ${item.name}`
                    : item.name}
                </option>
              ))}
          </select>
        </label>

        <label>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Описание
          </span>
          <textarea
            rows={4}
            value={description}
            onChange={(event) =>
              setDescription(event.target.value)
            }
            maxLength={2000}
            placeholder="Назначение и краткое содержание документа"
            className={`${FIELD_CLASS_NAME} resize-y`}
            disabled={submitting}
          />
        </label>
      </div>

      {error && (
        <div
          role="alert"
          className="mt-5 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-medium text-rose-700 dark:border-rose-900 dark:bg-rose-950/30 dark:text-rose-300"
        >
          {error}
        </div>
      )}

      <div className="mt-6 flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
        <button
          type="button"
          onClick={onCancel}
          disabled={submitting}
          className="rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
        >
          Отмена
        </button>

        <button
          type="submit"
          disabled={submitting}
          className="rounded-xl bg-indigo-600 px-5 py-2.5 text-sm font-semibold text-white shadow-lg shadow-indigo-500/20 hover:bg-indigo-700 disabled:opacity-50"
        >
          {submitting ? 'Загрузка…' : 'Загрузить документ'}
        </button>
      </div>
    </form>
  );
}