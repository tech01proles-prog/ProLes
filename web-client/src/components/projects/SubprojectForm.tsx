import {
  useEffect,
  useState,
  type FormEvent,
} from 'react';
import type {
  CreateSubprojectRequest,
  SubprojectDto,
} from '../../types';

interface SubprojectFormProps {
  initialValue?: SubprojectDto | null;
  submitting?: boolean;
  onCancel: () => void;
  onSubmit: (
    value: CreateSubprojectRequest,
  ) => void | Promise<void>;
}

const FIELD_CLASS_NAME = [
  'mt-1.5 w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5',
  'text-sm text-slate-950 outline-none transition',
  'placeholder:text-slate-400 focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/10',
  'dark:border-slate-700 dark:bg-slate-950 dark:text-white',
].join(' ');

export function SubprojectForm({
  initialValue,
  submitting = false,
  onCancel,
  onSubmit,
}: SubprojectFormProps) {
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [description, setDescription] = useState('');
  const [sortOrder, setSortOrder] = useState('0');
  const [error, setError] = useState('');

  useEffect(() => {
    setName(initialValue?.name ?? '');
    setCode(initialValue?.code ?? '');
    setDescription(initialValue?.description ?? '');
    setSortOrder(String(initialValue?.sortOrder ?? 0));
    setError('');
  }, [initialValue]);

  const handleSubmit = async (
    event: FormEvent<HTMLFormElement>,
  ) => {
    event.preventDefault();

    const normalizedName = name.trim();
    const normalizedSortOrder = Number(sortOrder);

    if (!normalizedName) {
      setError('Укажите название подпроекта.');
      return;
    }

    if (!Number.isInteger(normalizedSortOrder)) {
      setError('Порядок сортировки должен быть целым числом.');
      return;
    }

    setError('');

    await onSubmit({
      name: normalizedName,
      code: code.trim(),
      description: description.trim(),
      sortOrder: normalizedSortOrder,
    });
  };

  return (
    <form onSubmit={handleSubmit}>
      <div className="grid gap-5 sm:grid-cols-2">
        <label className="sm:col-span-2">
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Название
          </span>
          <input
            autoFocus
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="Этап или направление работ"
            maxLength={250}
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          />
        </label>

        <label>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Код
          </span>
          <input
            value={code}
            onChange={(event) => setCode(event.target.value)}
            placeholder="Например: SP-01"
            maxLength={100}
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          />
        </label>

        <label>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Порядок
          </span>
          <input
            type="number"
            step="1"
            value={sortOrder}
            onChange={(event) =>
              setSortOrder(event.target.value)
            }
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          />
        </label>

        <label className="sm:col-span-2">
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Описание
          </span>
          <textarea
            rows={4}
            value={description}
            onChange={(event) =>
              setDescription(event.target.value)
            }
            placeholder="Состав работ и назначение подпроекта"
            maxLength={2000}
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
          {submitting
            ? 'Сохранение…'
            : initialValue
              ? 'Сохранить изменения'
              : 'Создать подпроект'}
        </button>
      </div>
    </form>
  );
}