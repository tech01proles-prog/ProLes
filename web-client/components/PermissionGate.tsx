import { Navigate, Outlet } from 'react-router-dom';
import { usePermissions } from '../hooks/usePermissions';

export function PermissionGate({ permission }: { permission: string }) {
  const { can, loading } = usePermissions();
  if (loading) return <div className="flex justify-center py-20"><div className="animate-spin text-3xl">⏳</div></div>;
  return can(permission, 'view') ? <Outlet /> : <Navigate to="/" replace />;
}
