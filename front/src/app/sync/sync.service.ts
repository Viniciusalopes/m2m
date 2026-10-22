import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { JobView } from '../profiles/profiles.service';

export interface SyncRequest {
  destMachineId: number;
  compareContent: boolean;
  confirmed: boolean;
  confirmEmptySource: boolean;
  confirmDestinationChanges: boolean;
}

export interface SyncPreview {
  directories: {
    dir: number;
    sourcePath: string;
    targetPath: string;
    targetExists: boolean;
    create: number;
    update: number;
    delete: number;
  }[];
  create: number;
  update: number;
  delete: number;
  alreadySynced: boolean;
  emptySourceDeletes: number;
  hasState: boolean;
  destinationChanges: number;
  destinationSample: string[];
  warnings: string[];
  destMachine: string;
}

export interface SyncSchedule {
  id: number;
  destMachineId: number;
  destMachineName: string;
  time: string;
  enabled: boolean;
  maxDeletionsPercent: number;
  maxDeletionsAbs: number;
  hasManualSync: boolean;
}

export interface SyncScheduleRequest {
  destMachineId: number;
  time: string;
  enabled: boolean;
  maxDeletionsPercent: number;
  maxDeletionsAbs: number;
}

@Injectable({ providedIn: 'root' })
export class SyncService {
  private http = inject(HttpClient);

  preview(profileId: number, req: SyncRequest): Promise<SyncPreview> {
    return firstValueFrom(
      this.http.post<SyncPreview>(`/api/profiles/${profileId}/sync/preview`, req),
    );
  }

  start(profileId: number, req: SyncRequest): Promise<JobView> {
    return firstValueFrom(this.http.post<JobView>(`/api/profiles/${profileId}/sync`, req));
  }

  schedules(profileId: number): Promise<SyncSchedule[]> {
    return firstValueFrom(
      this.http.get<SyncSchedule[]>(`/api/profiles/${profileId}/sync-schedules`),
    );
  }

  createSchedule(profileId: number, req: SyncScheduleRequest): Promise<SyncSchedule> {
    return firstValueFrom(
      this.http.post<SyncSchedule>(`/api/profiles/${profileId}/sync-schedules`, req),
    );
  }

  updateSchedule(profileId: number, id: number, req: SyncScheduleRequest): Promise<SyncSchedule> {
    return firstValueFrom(
      this.http.put<SyncSchedule>(`/api/profiles/${profileId}/sync-schedules/${id}`, req),
    );
  }

  deleteSchedule(profileId: number, id: number): Promise<void> {
    return firstValueFrom(
      this.http.delete<void>(`/api/profiles/${profileId}/sync-schedules/${id}`),
    );
  }

  confirm(jobId: number, flags: Partial<SyncRequest> = {}): Promise<JobView> {
    return firstValueFrom(this.http.post<JobView>(`/api/jobs/${jobId}/confirm-sync`, flags));
  }

  undo(archiveId: number): Promise<JobView> {
    return firstValueFrom(this.http.post<JobView>(`/api/backups/${archiveId}/undo-sync`, null));
  }
}
