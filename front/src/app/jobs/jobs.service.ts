import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { JobProgress, JobView } from '../profiles/profiles.service';

export interface ActiveJobs {
  running: JobView | null;
  queued: JobView[];
}

export interface JobMessage {
  id: number;
  at: string;
  level: string;
  code: string;
  params: Record<string, unknown>;
}

/** Evento do SSE: estado do job em tempo real. */
export interface JobEvent extends Partial<JobProgress> {
  type: 'progress' | 'done';
  jobId: number;
  status: string;
  result?: Record<string, unknown> | null;
}

@Injectable({ providedIn: 'root' })
export class JobsService {
  private http = inject(HttpClient);

  active(): Promise<ActiveJobs> {
    return firstValueFrom(this.http.get<ActiveJobs>('/api/jobs/active'));
  }

  get(id: number): Promise<JobView> {
    return firstValueFrom(this.http.get<JobView>(`/api/jobs/${id}`));
  }

  messages(id: number): Promise<JobMessage[]> {
    return firstValueFrom(this.http.get<JobMessage[]>(`/api/jobs/${id}/messages`));
  }

  cancel(id: number): Promise<JobView> {
    return firstValueFrom(this.http.post<JobView>(`/api/jobs/${id}/cancel`, null));
  }

  /** Abre o fluxo de eventos; devolve uma função que o fecha. */
  watch(id: number, onEvent: (e: JobEvent) => void): () => void {
    const source = new EventSource(`/api/jobs/${id}/events`);
    source.addEventListener('progress', (msg) => {
      try {
        onEvent(JSON.parse((msg as MessageEvent<string>).data) as JobEvent);
      } catch {
        /* evento malformado: ignora */
      }
    });
    return () => source.close();
  }
}
