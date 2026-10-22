import { Routes } from '@angular/router';
import { authGuard, loginGuard } from './core/auth.guard';
import { About } from './pages/about/about';
import { Audit } from './pages/audit/audit';
import { Home } from './pages/home/home';
import { Layout } from './pages/layout/layout';
import { JobProgressPage } from './jobs/job-progress';
import { JobsRedirect } from './jobs/jobs-redirect';
import { Machines } from './machines/machines';
import { Login } from './pages/login/login';
import { Password } from './pages/password/password';
import { ProfileForm } from './profiles/profile-form';
import { Profiles } from './profiles/profiles';

export const routes: Routes = [
  { path: 'login', component: Login, canActivate: [loginGuard] },
  {
    path: '',
    component: Layout,
    canActivate: [authGuard],
    children: [
      { path: '', component: Home },
      { path: 'maquinas', component: Machines },
      { path: 'perfis', component: Profiles },
      { path: 'perfis/novo', component: ProfileForm },
      { path: 'perfis/:id', component: ProfileForm },
      { path: 'jobs', component: JobsRedirect },
      { path: 'jobs/:id', component: JobProgressPage },
      { path: 'auditoria', component: Audit },
      { path: 'senha', component: Password },
      { path: 'sobre', component: About },
    ],
  },
  { path: '**', redirectTo: '' },
];
