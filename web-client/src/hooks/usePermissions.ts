import { useState, useEffect, useCallback } from 'react';
import api from '../api/client';

type Permission = {
  canView: boolean;
  canCreate: boolean;
  canEdit: boolean;
  canDelete: boolean;
};

type PermissionsMap = Record<string, Permission>;

const CACHE_KEY = 'proles_permissions_cache';
const CACHE_TTL = 5_000; // 5 секунд для отладки

export function usePermissions() {
  const [permissions, setPermissions] = useState<PermissionsMap>({});
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const cached = sessionStorage.getItem(CACHE_KEY);
    if (cached) {
      try {
        const { data, timestamp } = JSON.parse(cached);
        if (Date.now() - timestamp < CACHE_TTL) {
          console.log('📦 Using cached permissions:', data);
          setPermissions(data);
          setLoading(false);
          return;
        }
      } catch (e) {
        console.error('❌ Cache parse error:', e);
      }
    }

    console.log('🔄 Fetching permissions from API...');
    api.get('/rbac/my-permissions')
      .then(response => {
        console.log('✅ API Response:', response.data);
        console.log('✅ Type of response.data:', typeof response.data);
        console.log('✅ Keys in response.data:', Object.keys(response.data || {}));

        setPermissions(response.data);
        sessionStorage.setItem(CACHE_KEY, JSON.stringify({
          data: response.data,
          timestamp: Date.now(),
        }));
      })
      .catch(error => {
        console.error('❌ Failed to fetch permissions:', error);
      })
      .finally(() => {
        setLoading(false);
      });
  }, []);

  const can = useCallback((permission: string, action: 'view' | 'create' | 'edit' | 'delete'): boolean => {
    console.log(`🔍 can('${permission}', '${action}') called`);
    console.log(`🔍 Current permissions state:`, permissions);
    console.log(`🔍 Looking for permission '${permission}' in:`, Object.keys(permissions));

    const perm = permissions[permission];
    console.log(`🔍 Found permission object:`, perm);

    if (!perm) {
      console.log(`❌ Permission '${permission}' not found`);
      return false;
    }

    const key = `can${action.charAt(0).toUpperCase() + action.slice(1)}` as keyof Permission;
    const result = perm[key] || false;
    console.log(`✅ Result: ${result}`);
    return result;
  }, [permissions]);

  const refresh = useCallback(() => {
    sessionStorage.removeItem(CACHE_KEY);
    setLoading(true);
    api.get('/rbac/my-permissions')
      .then(response => {
        setPermissions(response.data);
        sessionStorage.setItem(CACHE_KEY, JSON.stringify({
          data: response.data,
          timestamp: Date.now(),
        }));
      })
      .finally(() => setLoading(false));
  }, []);

  return { permissions, loading, can, refresh };
}