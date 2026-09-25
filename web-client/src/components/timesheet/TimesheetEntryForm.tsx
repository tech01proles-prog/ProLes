import { useEffect, useState, type FormEvent } from 'react';

export interface TimesheetProjectOption {
  id: string | number;
  name: string;
}

export interface TimesheetEntryFormValue {
  projectId: string;
  date: string;
  hours: string;
  description: string;
}

interface TimesheetEntryFormProps {
  projects: TimesheetProjectOption[];
  initialValue?: Partial<TimesheetEntryFormValue>;
  submitting?: boolean;
  submitLabel?: string;
  onCancel: () => void;
  onSubmit: (value: TimesheetEntryFormValue) => void | Promise<void>;
}

const EMPTY_VALUE: TimesheetEntryFormValue = {
  projectId: '',
  date: '',
  hours: '',
  description: '',
};

const FIELD_CLASS_NAME = [
  'mt-1.5 w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5',
  'text-sm text-slate-950 outline-none transition',
  'placeholder:text-slate-400 focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/10',
  'dark:border-slate-700 dark:bg-slate-950 dark:text-white',
].join(' ');

export function TimesheetEntryForm({
  projects,
  initialValue,
  submitting = false,
  submitLabel = 'Сохранить запись',
  onCancel,
  onSubmit,
}: TimesheetEntryFormProps) {
  const [value, setValue] = useState<TimesheetEntryFormValue>({
    ...EMPTY_VALUE,
    ...initialValue,
  });
  const [error, setError] = useState('');

  useEffect(() => {
    setValue({
      ...EMPTY_VALUE,
      ...initialValue,
    });
    setError('');
  }, [initialValue]);

  const updateField = (
    field: keyof TimesheetEntryFormValue,
    fieldValue: string,
  ) => {
    setValue((current) => ({
      ...current,
      [field]: fieldValue,
    }));
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    const hours = Number(value.hours.replace(',', '.'));

    if (!value.projectId) {
      setError('Выберите проект.');
      return;
    }

    if (!value.date) {
      setError('Укажите дату.');
      return;
    }

    if (!Number.isFinite(hours) || hours <= 0 || hours > 24) {
      setError('Количество часов должно быть больше 0 и не больше 24.');
      return;
    }

    setError('');

    await onSubmit({
      ...value,
      hours: String(hours),
      description: value.description.trim(),
    });
  };

  return (
    <form id="timesheet-entry-form" onSubmit={handleSubmit}>
      <div className="grid gap-5 sm:grid-cols-2">
        <label className="sm:col-span-2">
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Проект
          </span>
          <select
            value={value.projectId}
            onChange={(event) => updateField('projectId', event.target.value)}
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          >
            <option value="">Выберите проект</option>
            {projects.map((project) => (
              <option key={project.id} value={String(project.id)}>
                {project.name}
              </option>
            ))}
          </select>
        </label>

        <label>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Дата
          </span>
          <input
            type="date"
            value={value.date}
            onChange={(event) => updateField('date', event.target.value)}
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          />
        </label>

        <label>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Часы
          </span>
          <input
            type="number"
            min="0.1"
            max="24"
            step="0.1"
            inputMode="decimal"
            value={value.hours}
            onChange={(event) => updateField('hours', event.target.value)}
            placeholder="8"
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          />
        </label>

        <label className="sm:col-span-2">
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Описание работы
          </span>
          <textarea
            rows={4}
            maxLength={1000}
            value={value.description}
            onChange={(event) =>
              updateField('description', event.target.value)
            }
            placeholder="Кратко опишите выполненную работу"
            className={`${FIELD_CLASS_NAME} resize-y`}
            disabled={submitting}
          />

          <span className="mt-1 block text-right text-[11px] text-slate-400">
            {value.description.length} / 1000
          </span>
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
          className="rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-semibold text-slate-700 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
        >
          Отмена
        </button>

        <button
          type="submit"
          disabled={submitting}
          className="rounded-xl bg-indigo-600 px-5 py-2.5 text-sm font-semibold text-white shadow-lg shadow-indigo-500/20 transition-colors hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {submitting ? 'Сохранение…' : submitLabel}
        </button>
      </div>
    </form>
  );
}