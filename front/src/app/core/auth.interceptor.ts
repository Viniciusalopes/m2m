import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/** 401 em qualquer chamada (fora o login) → sessão expirada: volta para a tela de login. O XSRF é tratado pelo HttpClient. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      const ehLogin = req.url.endsWith('/api/auth/login');
      if (err.status === 401 && !ehLogin && auth.authenticated()) {
        auth.sessionExpired();
        void router.navigate(['/login'], { queryParams: { expirada: 1 } });
      }
      return throwError(() => err);
    }),
  );
};
