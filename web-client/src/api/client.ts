import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/api/v1',
  headers: { 'Content-Type': 'application/json' }
});

// Автоматическая подстановка токена
api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = localStorage.getItem('proles_token');
  if (token && config.headers) config.headers['X-Session-Token'] = token;
  return config;
});

// Обработка 401 → редирект на логин
api.interceptors.response.use(
  (res) => res,
  (err: AxiosError) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('proles_token');
      localStorage.removeItem('proles_user');
      window.location.href = '/login';
    }
    return Promise.reject(err);
  }
);

export default api;