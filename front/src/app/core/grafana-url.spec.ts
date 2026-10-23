import { grafanaUrl } from './grafana-url';

describe('grafanaUrl', () => {
  it('usa HTTPS, o host atual e a porta configurada', () => {
    expect(grafanaUrl('localhost', { port: 3000 })).toBe('https://localhost:3000/');
    expect(grafanaUrl('192.168.0.11', { port: '3443' })).toBe('https://192.168.0.11:3443/');
  });

  it('envolve IPv6 em colchetes', () => {
    expect(grafanaUrl('::1', { port: 3000 })).toBe('https://[::1]:3000/');
  });

  it('devolve null sem porta válida', () => {
    expect(grafanaUrl('localhost', null)).toBeNull();
    expect(grafanaUrl('localhost', {})).toBeNull();
    expect(grafanaUrl('localhost', { port: 'abc' })).toBeNull();
    expect(grafanaUrl('localhost', { port: 70000 })).toBeNull();
  });
});
