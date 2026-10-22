import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AppNotification } from '../notifications/notifications.service';

export interface JobBrief {
  id: number;
  type: string;
  status: string;
  profileName: string | null;
  trigger: string;
  createdAt: string;
}

export interface ProfileStatus {
  id: number;
  name: string;
  sourceMachine: string;
  storage: string;
  lastBackup: {
    id: number;
    status: string;
    at: string;
    archiveBytes: number;
    fileCount: number;
    fileName: string;
  } | null;
  lastSyncs: { destination: string; status: string; at: string }[];
  nextBackupAt: string | null;
  scheduled: boolean;
}

export interface Attention {
  id: number;
  fileName: string;
  profileName: string;
  status: string;
  createdAt: string;
}

export interface StorageUsage {
  machine: string;
  dir: string;
  freeBytes: number | null;
  totalBytes: number | null;
  freePercent: number | null;
  state: 'OK' | 'LOW' | 'UNKNOWN' | 'ERROR';
}

export interface Dashboard {
  running: JobBrief | null;
  queue: JobBrief[];
  profiles: ProfileStatus[];
  unread: number;
  alerts: AppNotification[];
  attention: Attention[];
  awaitingConfirmation: JobBrief[];
  storage: StorageUsage[];
}

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private http = inject(HttpClient);

  get(): Promise<Dashboard> {
    return firstValueFrom(this.http.get<Dashboard>('/api/dashboard'));
  }
}
