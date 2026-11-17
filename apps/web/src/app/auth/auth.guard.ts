import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

export const authGuard: CanActivateFn = (_route, state) => {
  const router = inject(Router);
  return inject(AuthService).isAuthenticated()
    ? true
    : router.createUrlTree(['/entrar'], { queryParams: { retorno: state.url } });
};

/** For the admin screens: a signed-in user who is not an admin goes back to the catalog. */
export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isAdmin() ? true : inject(Router).createUrlTree(['/catalogo']);
};
