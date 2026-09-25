import { useState, type FormEvent } from 'react';

export interface ProjectFormValue {
  name: string;
  client: string;
  location: string;
  productService: string;
  quantity: string;
  deliveryDate: string;
  contract: string;
  status: string;
  isActive: boolean;
}

interface ProjectFormProps {
  submitting?: boolean;
  onCancel: () => void;
  onSubmit: (value: ProjectFormValue) => void | Promise<void>;
}

const INITIAL_VALUE: ProjectFormValue = {
  name: '',
  client: '',
  location: '',
  productService: '',
  quantity: '1',
  deliveryDate: '',
  contract: '',
  status: 'new',
  isActive: true,
};

const FIELD_CLASS_NAME = [
  'mt-1.5 h-11 w-full rounded-xl border border-slate-200 bg-white px-3.5',
  'text-sm text-slate-950 outline-none transition',
  'placeholder:text-slate-400 focus:border-indigo-500 focus:ring-4 focus:ring-indigo-500/10',
  'dark:border-slate-700 dark:bg-slate-950 dark:text-white',
].join(' ');

export function ProjectForm({
  submitting = false,
  onCancel,
  onSubmit,
}: ProjectFormProps) {
  const [value, setValue] = useState(INITIAL_VALUE);
  const [error, setError] = useState('');

  const update = <K extends keyof ProjectFormValue>(
    field: K,
    fieldValue: ProjectFormValue[K],
  ) => {
    setValue((current) => ({
      ...current,
      [field]: fieldValue,
    }));
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    if (!value.name.trim()) {
      setError('Укажите название проекта.');
      return;
    }

    const quantity = Number(value.quantity);

    if (!Number.isInteger(quantity) || quantity < 1) {
      setError('Количество должно быть целым числом не меньше 1.');
      return;
    }

    setError('');
    await onSubmit({
      ...value,
      name: value.name.trim(),
      client: value.client.trim(),
      location: value.location.trim(),
      productService: value.productService.trim(),
      contract: value.contract.trim(),
      quantity: String(quantity),
    });
  };

  return (
    <form onSubmit={handleSubmit}>
      <div className="grid gap-5 sm:grid-cols-2">
        <label className="sm:col-span-2">
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Название проекта
          </span>
          <input
            autoFocus
            value={value.name}
            onChange={(event) => update('name', event.target.value)}
            placeholder="Поставка оборудования для заказчика"
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          />
        </label>

        <label>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Клиент
          </span>
          <input
            value={value.client}
            onChange={(event) => update('client', event.target.value)}
            placeholder="Название организации"
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          />
        </label>

        <label>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Локация
          </span>
          <input
            value={value.location}
            onChange={(event) => update('location', event.target.value)}
            placeholder="Город или объект"
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          />
        </label>

        <label className="sm:col-span-2">
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Товар или услуга
          </span>
          <input
            value={value.productService}
            onChange={(event) =>
              update('productService', event.target.value)
            }
            placeholder="Предмет договора или ожидаемый результат"
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          />
        </label>

        <label>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Количество
          </span>
          <input
            type="number"
            min="1"
            step="1"
            value={value.quantity}
            onChange={(event) => update('quantity', event.target.value)}
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          />
        </label>

        <label>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Срок поставки
          </span>
          <input
            type="date"
            value={value.deliveryDate}
            onChange={(event) =>
              update('deliveryDate', event.target.value)
            }
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          />
        </label>

        <label>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Договор
          </span>
          <input
            value={value.contract}
            onChange={(event) => update('contract', event.target.value)}
            placeholder="Номер и дата договора"
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          />
        </label>

        <label>
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            Статус
          </span>
          <select
            value={value.status}
            onChange={(event) => update('status', event.target.value)}
            className={FIELD_CLASS_NAME}
            disabled={submitting}
          >
            <option value="new">Новый</option>
            <option value="in_progress">В работе</option>
            <option value="completed">Завершён</option>
            <option value="cancelled">Отменён</option>
          </select>
        </label>

        <label className="flex items-center gap-3 rounded-xl border border-slate-200 p-4 sm:col-span-2 dark:border-slate-700">
          <input
            type="checkbox"
            checked={value.isActive}
            onChange={(event) => update('isActive', event.target.checked)}
            className="h-4 w-4 accent-indigo-600"
            disabled={submitting}
          />
          <span>
            <span className="block text-sm font-semibold text-slate-700 dark:text-slate-200">
              Активный проект
            </span>
            <span className="mt-0.5 block text-xs text-slate-400">
              Проект будет доступен при добавлении часов и расходов.
            </span>
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
          className="rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50 dark:border-slate-700 dark:text-slate-200 dark:hover:bg-slate-800"
        >
          Отмена
        </button>

        <button
          type="submit"
          disabled={submitting}
          className="rounded-xl bg-indigo-600 px-5 py-2.5 text-sm font-semibold text-white shadow-lg shadow-indigo-500/20 hover:bg-indigo-700 disabled:opacity-50"
        >
          {submitting ? 'Создание…' : 'Создать проект'}
        </button>
      </div>
    </form>
  );
}