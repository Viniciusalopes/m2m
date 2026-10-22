import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface Mapping {
  machineId: number;
  path: string;
}

export interface DirView {
  id: number;
  position: number;
  path: string;
  mappings: Mapping[];
}

export interface Schedule {
  time: string;
  enabled: boolean;
}

export interface Profile {
  id: number;
  name: string;
  complement: string | null;
  sourceMachineId: number;
  sourceMachineName: string;
  directories: DirView[];
  storage: { machineId: number; path: string };
  storageMachineName: string;
  suspectThreshold: number;
  encryptionEnabled: boolean;
  backupSchedule: Schedule | null;
  createdAt: string;
}

export interface ProfileRequest {
  name: string;
  complement: string | null;
  sourceMachineId: number;
  directories: { path: string; mappings: Mapping[] }[];
  storage: { machineId: number; path: string };
  suspectThreshold: number;
  encryptionEnabled: boolean;
  backupSchedule: Schedule | null;
}

export interface Defaults {
  storageMachineId: number | null;
  storageDir: string;
  suspectThreshold: number;
}

export interface JobView {
  id: number;
  type: string;
  status: string;
  step: string | null;
  progress: JobProgress | null;
  trigger: string;
  profileId: number | null;
  profileName: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  result: Record<string, unknown> | null;
}

export interface JobProgress {
  step: string | null;
  bytesDone: number;
  bytesTotal: number;
  itemsDone: number;
  itemsTotal: number;
  percent: number;
  currentPath: string | null;
  warnings: number;
}

@Injectable({ providedIn: 'root' })
export class ProfilesService {
  private http = inject(HttpClient);

  list(): Promise<Profile[]> {
    return firstValueFrom(this.http.get<Profile[]>('/api/profiles'));
  }

  get(id: number): Promise<Profile> {
    return firstValueFrom(this.http.get<Profile>(`/api/profiles/${id}`));
  }

  defaults(): Promise<Defaults> {
    return firstValueFrom(this.http.get<Defaults>('/api/profiles/defaults'));
  }

  create(req: ProfileRequest): Promise<Profile> {
    return firstValueFrom(this.http.post<Profile>('/api/profiles', req));
  }

  update(id: number, req: ProfileRequest): Promise<Profile> {
    return firstValueFrom(this.http.put<Profile>(`/api/profiles/${id}`, req));
  }

  remove(id: number, keepBackups: boolean): Promise<void> {
    const params = new HttpParams().set('keepBackups', keepBackups);
    return firstValueFrom(this.http.delete<void>(`/api/profiles/${id}`, { params }));
  }

  startBackup(id: number, complement: string | null): Promise<JobView> {
    return firstValueFrom(this.http.post<JobView>(`/api/profiles/${id}/backup`, { complement }));
  }
}
