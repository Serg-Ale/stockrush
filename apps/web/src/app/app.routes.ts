import { Routes } from '@angular/router';
import { adminGuard, authGuard } from './auth/auth.guard';

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
      {
        path: 'pedidos',
        title: 'Pedidos — Stockrush',
        loadComponent: () => import('./pages/orders/orders').then((m) => m.Orders),
      },
      {
        path: 'painel',
        title: 'Painel — Stockrush',
        canActivate: [adminGuard],
        loadComponent: () => import('./pages/panel/panel').then((m) => m.Panel),
      },
      { path: '', pathMatch: 'full', redirectTo: 'catalogo' },
    ],
  },
  { path: '**', redirectTo: '' },
];
