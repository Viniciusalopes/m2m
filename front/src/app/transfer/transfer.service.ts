import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface FilterCondition {
  field: string;
  op: string;
  value: string;
}

export interface FilterDefinition {
  match: 'ALL' | 'ANY' | 'NONE';
  caseSensitive: boolean;
  conditions: FilterCondition[];
}

export interface SavedFilter {
  id: number;
  name: string;
  definition: FilterDefinition;
}

export interface Entry {
  name: string;
  path: string;
  type: 'FILE' | 'DIRECTORY' | 'SYMLINK' | 'SPECIAL';
  size: number;
  mtime: string;
  mode: number | null;
  linkTarget: string | null;
}

export interface Listing {
  path: string;
  parent: string | null;
  items: Entry[];
  total: number;
  hidden: number;
}

export interface Conflict {
  srcPath: string;
  dstPath: string;
  srcSize: number;
  srcMtime: number;
  dstSize: number;
  dstMtime: number;
}

export type ConflictAction =
  | 'OVERWRITE'
  | 'OVERWRITE_NEWER'
  | 'OVERWRITE_SIZE'
  | 'OVERWRITE_SIZE_OR_NEWER'
  | 'RESUME'
  | 'RENAME'
  | 'SKIP';

export interface PlanRequest {
  srcMachineId: number;
  srcPaths: string[];
  dstMachineId: number;
  dstDir: string;
  applyFilters: boolean;
  filter: FilterDefinition | null;
  conflictDefault: ConflictAction | null;
  decisions: Record<string, ConflictAction> | null;
}

export interface PlanResult {
  items: number;
  files: number;
  bytes: number;
  skipped: number;
  ignoredByFilter: number;
  conflicts: Conflict[];
  warnings: string[];
  batchId: string | null;
}

export interface TransferView {
  id: number;
  batchId: string;
  op: string;
  kind: string;
  status: string;
  srcMachine: string | null;
  srcPath: string | null;
  dstMachine: string | null;
  dstPath: string;
  size: number;
  bytesDone: number;
  percent: number;
  skipReason: string | null;
  note: string | null;
  errorCode: string | null;
  errorParams: Record<string, unknown> | null;
  resumable: boolean;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

/** Evento do SSE de transferências. */
export interface TransferEvent {
  type: 'item';
  id: number;
  batchId: string;
  status: string;
  bytesDone: number;
  size: number;
  speed: number;
}

export type CompareState =
  'ONLY_HERE' | 'ABSENT' | 'NEWER' | 'OLDER' | 'SIZE_DIFFERENT' | 'TYPE_DIFFERENT' | 'SAME';

export interface CompareEntry {
  name: string;
  leftState: CompareState;
  rightState: CompareState;
}

export interface CompareResult {
  entries: CompareEntry[];
  different: number;
  identical: number;
}

export interface Side {
  machineId: number;
  path: string;
  filter: FilterDefinition | null;
}

@Injectable({ providedIn: 'root' })
export class TransferService {
  private http = inject(HttpClient);

  list(machineId: number, path: string | null, filter: FilterDefinition | null): Promise<Listing> {
    return firstValueFrom(
      this.http.post<Listing>('/api/transfers/list', {
        machineId,
        path,
        filter,
        filterSetId: null,
      }),
    );
  }

  compare(
    left: Side,
    right: Side,
    mode: 'SIZE' | 'MTIME',
    toleranceMinutes: number | null,
  ): Promise<CompareResult> {
    return firstValueFrom(
      this.http.post<CompareResult>('/api/transfers/compare', {
        left,
        right,
        mode,
        toleranceMinutes,
      }),
    );
  }

  plan(req: PlanRequest): Promise<PlanResult> {
    return firstValueFrom(this.http.post<PlanResult>('/api/transfers/plan', req));
  }

  start(req: PlanRequest): Promise<PlanResult> {
    return firstValueFrom(this.http.post<PlanResult>('/api/transfers', req));
  }

  queue(): Promise<TransferView[]> {
    const params = new HttpParams().set('size', 300);
    return firstValueFrom(this.http.get<TransferView[]>('/api/transfers', { params }));
  }

  cancel(id: number): Promise<void> {
    return firstValueFrom(this.http.post<void>(`/api/transfers/${id}/cancel`, null));
  }

  resume(id: number): Promise<void> {
    return firstValueFrom(this.http.post<void>(`/api/transfers/${id}/resume`, null));
  }

  cancelAll(): Promise<{ canceled: number }> {
    return firstValueFrom(this.http.post<{ canceled: number }>('/api/transfers/cancel-all', null));
  }

  resumeAll(statuses: string[]): Promise<{ requeued: number }> {
    return firstValueFrom(
      this.http.post<{ requeued: number }>('/api/transfers/resume-all', { statuses }),
    );
  }

  mkdir(machineId: number, path: string): Promise<void> {
    return firstValueFrom(this.http.post<void>('/api/transfers/fs/mkdir', { machineId, path }));
  }

  rename(machineId: number, from: string, to: string): Promise<void> {
    return firstValueFrom(
      this.http.post<void>('/api/transfers/fs/rename', { machineId, from, to }),
    );
  }

  count(machineId: number, path: string): Promise<{ items: number }> {
    return firstValueFrom(
      this.http.post<{ items: number }>('/api/transfers/fs/count', { machineId, path }),
    );
  }

  remove(machineId: number, path: string): Promise<{ deleted: number }> {
    return firstValueFrom(
      this.http.post<{ deleted: number }>('/api/transfers/fs/delete', {
        machineId,
        path,
        confirmed: true,
      }),
    );
  }

  filters(): Promise<SavedFilter[]> {
    return firstValueFrom(this.http.get<SavedFilter[]>('/api/transfer-filters'));
  }

  saveFilter(name: string, definition: FilterDefinition): Promise<{ id: number }> {
    return firstValueFrom(
      this.http.post<{ id: number }>('/api/transfer-filters', { name, definition }),
    );
  }

  deleteFilter(id: number): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`/api/transfer-filters/${id}`));
  }

  /** Abre o SSE de progresso; devolve a função que o fecha. */
  watch(onEvent: (e: TransferEvent) => void): () => void {
    const source = new EventSource('/api/transfers/events');
    source.addEventListener('progress', (msg) => {
      try {
        onEvent(JSON.parse((msg as MessageEvent<string>).data) as TransferEvent);
      } catch {
        /* evento malformado: ignora */
      }
    });
    return () => source.close();
  }
}
