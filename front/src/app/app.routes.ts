import { Routes } from '@angular/router';
import { authGuard, loginGuard } from './core/auth.guard';
import { About } from './pages/about/about';
import { Audit } from './pages/audit/audit';
import { Home } from './pages/home/home';
import { Layout } from './pages/layout/layout';
import { Login } from './pages/login/login';
import { Password } from './pages/password/password';

export const routes: Routes = [
  { path: 'login', component: Login, canActivate: [loginGuard] },
  {
    path: '',
    component: Layout,
    canActivate: [authGuard],
    children: [
      { path: '', component: Home },
      { path: 'auditoria', component: Audit },
      { path: 'senha', component: Password },
      { path: 'sobre', component: About },
    ],
  },
  { path: '**', redirectTo: '' },
];
