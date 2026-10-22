import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!auth.loaded()) {
    await auth.restore();
  }
  return auth.authenticated() ? true : router.createUrlTree(['/login']);
};

export const loginGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!auth.loaded()) {
    await auth.restore();
  }
  return auth.authenticated() ? router.createUrlTree(['/']) : true;
};
