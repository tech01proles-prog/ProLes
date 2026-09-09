import { useEffect, useMemo, useState } from 'react';
import api from '../api/client';
import type { PositionDto } from '../types';
import { usePermissions } from '../hooks/usePermissions';

export function PositionsPage() {
  const { can } = usePermissions();
  const [items, setItems] = useState<PositionDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState<PositionDto | null>(null);
  const [form, setForm] = useState({ name: '', parentId: '', sortOrder: '0', isActive: true });

  const load = async () => {
    setLoading(true);
    try { setItems((await api.get<PositionDto[]>('/positions')).data); }
    finally { setLoading(false); }
  };
  useEffect(() => { load().catch(console.error); }, []);

  const labels = useMemo(() => {
    const byId = new Map(items.map(x => [x.id, x]));
    const memo = new Map<string, string>();
    const build = (id: string, seen = new Set<string>()): string => {
      if (memo.has(id)) return memo.get(id)!;
      if (seen.has(id)) return byId.get(id)?.name || '';
      const item = byId.get(id);
      if (!item) return '';
      const text = item.parentId ? `${build(item.parentId, new Set(seen).add(id))} → ${item.name}` : item.name;
      memo.set(id, text); return text;
    };
    return new Map(items.map(x => [x.id, build(x.id)]));
  }, [items]);

  const reset = () => { setEditing(null); setForm({ name: '', parentId: '', sortOrder: '0', isActive: true }); };
  const edit = (p: PositionDto) => { setEditing(p); setForm({ name: p.name, parentId: p.parentId || '', sortOrder: String(p.sortOrder), isActive: p.isActive }); };
  const save = async () => {
    if (!form.name.trim()) return;
    const payload: PositionDto = { id: editing?.id || '', name: form.name.trim(), parentId: form.parentId || null, parentName: '', isActive: form.isActive, sortOrder: Number(form.sortOrder) || 0 };
    if (editing) await api.put('/positions', payload); else await api.post('/positions', payload);
    reset(); await load();
  };
  const disable = async (id: string) => { if (!confirm('Скрыть должность?')) return; await api.delete('/positions', { params: { positionId: id } }); await load(); };

  if (loading) return <div className="flex justify-center py-20 text-3xl">⏳</div>;
  return <div className="space-y-6 max-w-5xl mx-auto">
    <div><h1 className="text-2xl font-bold">Иерархия должностей</h1><p className="text-sm text-slate-500 mt-1">Структура подразделений и должностей сотрудников</p></div>
    {can('employees', 'create') || can('employees', 'edit') ? <div className="card p-5 space-y-4">
      <div className="grid grid-cols-1 md:grid-cols-4 gap-3">
        <input className="input md:col-span-2" placeholder="Название должности" value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} />
        <select className="input" value={form.parentId} onChange={e => setForm({ ...form, parentId: e.target.value })}><option value="">Без родителя</option>{items.filter(x => x.isActive && x.id !== editing?.id).map(x => <option key={x.id} value={x.id}>{labels.get(x.id)}</option>)}</select>
        <input className="input" type="number" placeholder="Порядок" value={form.sortOrder} onChange={e => setForm({ ...form, sortOrder: e.target.value })} />
      </div>
      <div className="flex items-center justify-between"><label className="text-sm"><input type="checkbox" checked={form.isActive} onChange={e => setForm({ ...form, isActive: e.target.checked })} className="mr-2" />Активна</label><div className="flex gap-2"><button className="btn-secondary px-4 py-2" onClick={reset}>Очистить</button><button className="btn-primary px-5 py-2" onClick={save}>{editing ? 'Сохранить' : 'Добавить'}</button></div></div>
    </div> : null}
    <div className="card overflow-hidden">
      {items.map(p => <div key={p.id} className="flex items-center gap-3 p-4 border-b last:border-0 border-slate-100 dark:border-slate-800">
        <div className="flex-1"><div className="font-semibold">{p.name}</div><div className="text-xs text-slate-500">{p.parentId ? `Родитель: ${p.parentName}` : 'Верхний уровень'} · {p.isActive ? 'Активна' : 'Неактивна'}</div></div>
        {can('employees', 'edit') && <button className="text-indigo-600 text-sm" onClick={() => edit(p)}>Изменить</button>}
        {can('employees', 'delete') && p.isActive && <button className="text-red-600 text-sm" onClick={() => disable(p.id)}>Скрыть</button>}
      </div>)}
      {items.length === 0 && <div className="p-10 text-center text-slate-500">Должности ещё не заведены.</div>}
    </div>
  </div>;
}
