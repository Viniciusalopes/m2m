import { Routes } from '@angular/router';
import { authGuard, loginGuard } from './core/auth.guard';
import { DashboardPage } from './dashboard/dashboard';
import { Layout } from './pages/layout/layout';
import { JobsRedirect } from './jobs/jobs-redirect';
import { Login } from './pages/login/login';

export const routes: Routes = [
  { path: 'login', component: Login, canActivate: [loginGuard] },
  {
    path: '',
    component: Layout,
    canActivate: [authGuard],
    children: [
      { path: '', component: DashboardPage },
      {
        path: 'notificacoes',
        loadComponent: () => import('./notifications/notifications').then((m) => m.Notifications),
      },
      {
        path: 'configuracoes',
        loadComponent: () => import('./settings/settings').then((m) => m.Settings),
      },
      {
        path: 'maquinas',
        loadComponent: () => import('./machines/machines').then((m) => m.Machines),
      },
      {
        path: 'perfis',
        loadComponent: () => import('./profiles/profiles').then((m) => m.Profiles),
      },
      {
        path: 'transferencia',
        loadComponent: () => import('./transfer/transfer-page').then((m) => m.TransferPage),
      },
      {
        path: 'perfis/novo',
        loadComponent: () => import('./profiles/profile-form').then((m) => m.ProfileForm),
      },
      {
        path: 'perfis/:id',
        loadComponent: () => import('./profiles/profile-form').then((m) => m.ProfileForm),
      },
      { path: 'backups', loadComponent: () => import('./backups/backups').then((m) => m.Backups) },
      {
        path: 'restaurar/:id',
        loadComponent: () => import('./restore/restore-wizard').then((m) => m.RestoreWizard),
      },
      { path: 'jobs', component: JobsRedirect },
      {
        path: 'jobs/:id',
        loadComponent: () => import('./jobs/job-progress').then((m) => m.JobProgressPage),
      },
      {
        path: 'auditoria',
        loadComponent: () => import('./pages/audit/audit').then((m) => m.Audit),
      },
      {
        path: 'senha',
        loadComponent: () => import('./pages/password/password').then((m) => m.Password),
      },
      { path: 'sobre', loadComponent: () => import('./pages/about/about').then((m) => m.About) },
    ],
  },
  { path: '**', redirectTo: '' },
];
