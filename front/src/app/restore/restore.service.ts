import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { JobView } from '../profiles/profiles.service';

export interface DefaultTarget {
  dir: number;
  sourcePath: string;
  targetPath: string | null;
}

export interface Target {
  dir: number;
  path: string;
}

export interface DirPreview {
  dir: number;
  sourcePath: string;
  targetPath: string;
  targetExists: boolean;
  create: number;
  overwrite: number;
  delete: number;
  files: number;
}

export interface Preview {
  directories: DirPreview[];
  create: number;
  overwrite: number;
  delete: number;
  warnings: string[];
  archiveFile: string;
  destMachine: string;
}

export interface Conflict {
  entry: string;
  targetPath: string;
  incomingSize: number;
  incomingMtime: number;
  existingSize: number;
  existingMtime: number;
  typeConflict: boolean;
}

export type ConflictAction =
  | 'OVERWRITE'
  | 'OVERWRITE_NEWER'
  | 'OVERWRITE_SIZE'
  | 'OVERWRITE_SIZE_OR_NEWER'
  | 'RENAME'
  | 'SKIP';

export interface SelectiveRequest {
  machineId: number;
  targetDir: string;
  items: string[];
  defaultAction: ConflictAction;
  decisions: Record<string, ConflictAction>;
}

@Injectable({ providedIn: 'root' })
export class RestoreService {
  private http = inject(HttpClient);

  defaults(archiveId: number, machineId: number): Promise<DefaultTarget[]> {
    const params = new HttpParams().set('machineId', machineId);
    return firstValueFrom(
      this.http.get<DefaultTarget[]>(`/api/backups/${archiveId}/restore/defaults`, { params }),
    );
  }

  preview(archiveId: number, machineId: number, targets: Target[]): Promise<Preview> {
    return firstValueFrom(
      this.http.post<Preview>(`/api/backups/${archiveId}/restore/preview`, {
        machineId,
        targets,
        confirmed: false,
      }),
    );
  }

  start(archiveId: number, machineId: number, targets: Target[]): Promise<JobView> {
    return firstValueFrom(
      this.http.post<JobView>(`/api/backups/${archiveId}/restore`, {
        machineId,
        targets,
        confirmed: true,
      }),
    );
  }

  undo(archiveId: number): Promise<JobView> {
    return firstValueFrom(this.http.post<JobView>(`/api/backups/${archiveId}/undo`, null));
  }

  plan(
    archiveId: number,
    req: Omit<SelectiveRequest, 'defaultAction' | 'decisions'>,
  ): Promise<Conflict[]> {
    return firstValueFrom(
      this.http.post<Conflict[]>(`/api/backups/${archiveId}/selective/plan`, req),
    );
  }

  startSelective(archiveId: number, req: SelectiveRequest): Promise<JobView> {
    return firstValueFrom(this.http.post<JobView>(`/api/backups/${archiveId}/selective`, req));
  }
}
