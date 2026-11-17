import { Routes } from '@angular/router';
import { authGuard } from './auth/auth.guard';

export const routes: Routes = [
  {
    path: 'entrar',
    title: 'Entrar — Stockrush',
    loadComponent: () => import('./pages/login/login').then((m) => m.Login),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell').then((m) => m.Shell),
    children: [
      {
        path: 'catalogo',
        title: 'Catálogo — Stockrush',
        loadComponent: () => import('./pages/catalog/catalog').then((m) => m.Catalog),
      },
      { path: '', pathMatch: 'full', redirectTo: 'catalogo' },
    ],
  },
  { path: '**', redirectTo: '' },
];
