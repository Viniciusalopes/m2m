import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export type ArchiveStatus =
  | 'COMPLETED'
  | 'COMPLETED_WITH_WARNINGS'
  | 'SUSPECT'
  | 'FAILED'
  | 'INTERRUPTED'
  | 'CORRUPTED'
  | 'MISSING';

export interface Backup {
  id: number;
  profileId: number | null;
  profileName: string;
  orphan: boolean;
  type: 'REGULAR' | 'PRE_RESTORE' | 'PRE_SYNC';
  status: ArchiveStatus;
  accepted: boolean;
  pinned: boolean;
  storageMachineName: string | null;
  /** Falso quando a máquina de armazenamento foi removida: o arquivo segue no disco dela. */
  storageAvailable: boolean;
  storageDir: string;
  fileName: string;
  sourceMachineName: string | null;
  sourceProtocol: string | null;
  fullFidelity: boolean;
  itemCount: number;
  fileCount: number;
  totalBytes: number;
  archiveBytes: number;
  sha256: string | null;
  encrypted: boolean;
  warnings: { codigo: string; caminho?: string }[] | null;
  error: string | null;
  jobId: number | null;
  destMachineName: string | null;
  createdAt: string;
  finishedAt: string | null;
}

export interface TreeNode {
  name: string;
  path: string;
  type: 'FILE' | 'DIR' | 'SYMLINK';
  size: number;
  mtime: number | null;
  mode: number | null;
  link: string | null;
}

export interface TreePage {
  path: string;
  parent: string | null;
  total: number;
  items: TreeNode[];
}

export interface BackupFilters {
  profileId?: number | null;
  status?: string | null;
  type?: string | null;
  orphan?: boolean | null;
}

@Injectable({ providedIn: 'root' })
export class BackupsService {
  private http = inject(HttpClient);

  list(f: BackupFilters = {}, page = 0, size = 100): Promise<Backup[]> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (f.profileId) params = params.set('profileId', f.profileId);
    if (f.status) params = params.set('status', f.status);
    if (f.type) params = params.set('type', f.type);
    if (f.orphan) params = params.set('orphan', true);
    return firstValueFrom(this.http.get<Backup[]>('/api/backups', { params }));
  }

  pin(id: number, pinned: boolean): Promise<Backup> {
    return firstValueFrom(
      this.http.post<Backup>(`/api/backups/${id}/${pinned ? 'pin' : 'unpin'}`, null),
    );
  }

  accept(id: number): Promise<Backup> {
    return firstValueFrom(this.http.post<Backup>(`/api/backups/${id}/accept`, null));
  }

  remove(id: number): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/backups/${id}`));
  }

  tree(id: number, path: string, page = 0, size = 200): Promise<TreePage> {
    const params = new HttpParams().set('path', path).set('page', page).set('size', size);
    return firstValueFrom(this.http.get<TreePage>(`/api/backups/${id}/tree`, { params }));
  }

  downloadUrl(id: number): string {
    return `/api/backups/${id}/download`;
  }
}
