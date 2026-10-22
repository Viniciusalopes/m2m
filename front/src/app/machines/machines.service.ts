import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export type Protocol = 'LOCAL' | 'SFTP' | 'FTP' | 'FTPS';

export interface Capabilities {
  preservesMode: boolean;
  preservesLinks: boolean;
  setsMtime: boolean;
  remoteHash: boolean;
  freeSpace: boolean;
  writable: boolean;
}

export interface Fidelity {
  content: boolean;
  mtime: boolean;
  permissions: boolean;
  symlinks: boolean;
}

export interface IdentityView {
  kind: string;
  confirmed: boolean;
  fingerprint: string | null;
  pendingFingerprint: string | null;
  confirmedAt: string | null;
}

export interface Machine {
  id: number;
  name: string;
  description: string | null;
  host: string;
  protocol: Protocol;
  port: number;
  ftpsMode: 'EXPLICIT' | 'IMPLICIT' | null;
  username: string | null;
  authType: 'KEY' | 'PASSWORD' | null;
  hasPassword: boolean;
  maxConnections: number;
  capabilities: Capabilities;
  fidelity: Fidelity;
  warnings: string[];
  identity: IdentityView | null;
  lastTestAt: string | null;
  lastTest: string | null;
}

export interface MachineRequest {
  name: string;
  description?: string | null;
  host?: string | null;
  protocol: Protocol;
  port?: number | null;
  ftpsMode?: string | null;
  username?: string | null;
  authType?: string | null;
  password?: string | null;
  maxConnections?: number | null;
}

export interface TestStep {
  name: string;
  status: 'OK' | 'FAIL' | 'SKIPPED' | 'ACTION';
  code: string | null;
  params: Record<string, unknown>;
}

export interface TestResult {
  ok: boolean;
  readOnly: boolean;
  steps: TestStep[];
  features: string[];
  fingerprint: string | null;
}

export interface SshIdentity {
  algorithm: string;
  publicKey: string;
  suggestedCommand: string;
}

export interface BrowseItem {
  name: string;
  path: string;
  type: 'FILE' | 'DIRECTORY' | 'SYMLINK' | 'SPECIAL';
  size: number;
  mtime: string;
  mode: number | null;
  linkTarget: string | null;
}

export interface BrowseResult {
  path: string;
  parent: string | null;
  items: BrowseItem[];
}

@Injectable({ providedIn: 'root' })
export class MachinesService {
  private http = inject(HttpClient);

  list(): Promise<Machine[]> {
    return firstValueFrom(this.http.get<Machine[]>('/api/machines'));
  }

  create(req: MachineRequest): Promise<Machine> {
    return firstValueFrom(this.http.post<Machine>('/api/machines', req));
  }

  update(id: number, req: MachineRequest): Promise<Machine> {
    return firstValueFrom(this.http.put<Machine>(`/api/machines/${id}`, req));
  }

  remove(id: number): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/machines/${id}`));
  }

  test(id: number): Promise<TestResult> {
    return firstValueFrom(this.http.post<TestResult>(`/api/machines/${id}/test`, null));
  }

  confirmIdentity(id: number, fingerprint: string): Promise<void> {
    return firstValueFrom(
      this.http.post<void>(`/api/machines/${id}/identity/confirm`, { fingerprint }),
    );
  }

  sshIdentity(): Promise<SshIdentity> {
    return firstValueFrom(this.http.get<SshIdentity>('/api/ssh-identity'));
  }

  browse(id: number, path?: string, onlyDirs = false): Promise<BrowseResult> {
    let params = new HttpParams().set('onlyDirs', onlyDirs);
    if (path) {
      params = params.set('path', path);
    }
    return firstValueFrom(this.http.get<BrowseResult>(`/api/machines/${id}/browse`, { params }));
  }
}
