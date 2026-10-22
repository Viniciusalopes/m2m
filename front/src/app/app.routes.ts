import { Routes } from '@angular/router';
import { authGuard, loginGuard } from './core/auth.guard';
import { About } from './pages/about/about';
import { Audit } from './pages/audit/audit';
import { DashboardPage } from './dashboard/dashboard';
import { Notifications } from './notifications/notifications';
import { Settings } from './settings/settings';
import { Layout } from './pages/layout/layout';
import { Backups } from './backups/backups';
import { JobProgressPage } from './jobs/job-progress';
import { JobsRedirect } from './jobs/jobs-redirect';
import { Machines } from './machines/machines';
import { Login } from './pages/login/login';
import { Password } from './pages/password/password';
import { RestoreWizard } from './restore/restore-wizard';
import { ProfileForm } from './profiles/profile-form';
import { Profiles } from './profiles/profiles';
import { TransferPage } from './transfer/transfer-page';

export const routes: Routes = [
  { path: 'login', component: Login, canActivate: [loginGuard] },
  {
    path: '',
    component: Layout,
    canActivate: [authGuard],
    children: [
      { path: '', component: DashboardPage },
      { path: 'notificacoes', component: Notifications },
      { path: 'configuracoes', component: Settings },
      { path: 'maquinas', component: Machines },
      { path: 'perfis', component: Profiles },
      { path: 'transferencia', component: TransferPage },
      { path: 'perfis/novo', component: ProfileForm },
      { path: 'perfis/:id', component: ProfileForm },
      { path: 'backups', component: Backups },
      { path: 'restaurar/:id', component: RestoreWizard },
      { path: 'jobs', component: JobsRedirect },
      { path: 'jobs/:id', component: JobProgressPage },
      { path: 'auditoria', component: Audit },
      { path: 'senha', component: Password },
      { path: 'sobre', component: About },
    ],
  },
  { path: '**', redirectTo: '' },
];
