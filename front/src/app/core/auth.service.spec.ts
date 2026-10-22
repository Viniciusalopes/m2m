import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('restore sem sessão deixa o usuário nulo e marca como carregado', async () => {
    const p = auth.restore();
    http.expectOne('/api/me').flush(null, { status: 401, statusText: 'Unauthorized' });
    await p;
    expect(auth.authenticated()).toBe(false);
    expect(auth.loaded()).toBe(true);
  });

  it('login busca o csrf, envia o formulário e carrega o usuário', async () => {
    const p = auth.login('admin', 'segredo');
    http.expectOne('/api/auth/csrf').flush(null);
    await Promise.resolve();
    const req = await vi.waitFor(() => http.expectOne('/api/auth/login'));
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
    expect(req.request.body).toBe('username=admin&password=segredo');
    req.flush(null, { status: 204, statusText: 'No Content' });
    const me = await vi.waitFor(() => http.expectOne('/api/me'));
    me.flush({ id: 1, username: 'admin', email: 'a@b.c', role: 'ADMIN' });
    await p;
    expect(auth.me()?.username).toBe('admin');
  });

  it('logout limpa o usuário mesmo se a chamada falhar', async () => {
    auth.sessionExpired();
    const p = auth.logout().catch(() => undefined);
    http.expectOne('/api/auth/logout').flush(null, { status: 500, statusText: 'Erro' });
    await p;
    expect(auth.authenticated()).toBe(false);
  });
});
