import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation } from '@tanstack/react-query';
import { Navigate, useNavigate } from 'react-router';
import { z } from 'zod';
import { api, ApiError } from '../../lib/apiClient';
import { authStore, useSession } from '../../lib/authStore';
import { tokenResponseSchema } from '../../types/api';
import { homeOf } from './homeOf';

/** F1 / K1 / A1 — tek giriş ekranı; rol sunucudan gelir, kullanıcı seçmez. */

export const loginSchema = z.object({
  email: z.string().trim().min(1, 'E-posta gerekli.').email('Geçerli bir e-posta gir.'),
  password: z.string().min(1, 'Parola gerekli.'),
});
type LoginForm = z.infer<typeof loginSchema>;

export function LoginPage() {
  const session = useSession();
  const navigate = useNavigate();
  const form = useForm<LoginForm>({ resolver: zodResolver(loginSchema), defaultValues: { email: '', password: '' } });

  const login = useMutation({
    mutationFn: (body: LoginForm) => api('/auth/login', { method: 'POST', body, schema: tokenResponseSchema }),
    onSuccess: (tokens) => {
      authStore.signIn(tokens);
      navigate(homeOf(tokens.role), { replace: true });
    },
  });

  if (session) return <Navigate to={homeOf(session.role)} replace />;

  const { errors } = form.formState;
  return (
    <main className="login">
      <form className="card stack" onSubmit={form.handleSubmit((v) => login.mutate(v))} noValidate>
        <div>
          <h1>RouteWise</h1>
          <p className="muted">Bandırma dağıtım planlama</p>
        </div>
        <label>
          E-posta
          <input type="email" autoComplete="username" {...form.register('email')} aria-invalid={!!errors.email} />
          {errors.email && <span className="field-error">{errors.email.message}</span>}
        </label>
        <label>
          Parola
          <input type="password" autoComplete="current-password" {...form.register('password')} aria-invalid={!!errors.password} />
          {errors.password && <span className="field-error">{errors.password.message}</span>}
        </label>
        {login.error && (
          <div role="alert" className="alert danger">
            {login.error instanceof ApiError ? login.error.message : 'Giriş yapılamadı.'}
          </div>
        )}
        <button type="submit" className="primary" disabled={login.isPending}>
          {login.isPending ? 'Giriş yapılıyor…' : 'Giriş yap'}
        </button>
      </form>
    </main>
  );
}
